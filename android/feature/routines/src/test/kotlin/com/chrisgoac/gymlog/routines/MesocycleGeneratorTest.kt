package com.chrisgoac.gymlog.routines

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.ai.MesocycleDraft
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftDay
import com.chrisgoac.gymlog.domain.ai.MesocycleDraftExercise
import com.chrisgoac.gymlog.domain.ai.MesocycleGoal
import com.chrisgoac.gymlog.domain.ai.MesocycleParams
import com.chrisgoac.gymlog.domain.ai.MesocycleRepository
import com.chrisgoac.gymlog.domain.ai.MesocycleSaveException
import com.chrisgoac.gymlog.domain.ai.MesocycleWeek
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun draft() = MesocycleDraft(
    name = "Bloque fuerza",
    goal = "fuerza",
    weeks = 4,
    daysPerWeek = 2,
    notes = "Sube carga cada semana",
    progression = listOf(
        MesocycleWeek(1, false, "Base"),
        MesocycleWeek(2, false, "+2,5 kg"),
        MesocycleWeek(3, false, "+2,5 kg"),
        MesocycleWeek(4, true, "Menos volumen"),
    ),
    days = listOf(
        MesocycleDraftDay("d1", "Torso", listOf(MesocycleDraftExercise("e1", "seed-press", "Press banca", 4, 6, 180), MesocycleDraftExercise("e2", "seed-row", "Remo", 3, 10, 120))),
        MesocycleDraftDay("d2", "Pierna", listOf(MesocycleDraftExercise("e3", "seed-squat", "Sentadilla", 5, 5, 180))),
    ),
    omitted = listOf("Press militar"),
)

/** Scripted repository: each generate() takes the next outcome; saves are recorded. */
private class FakeMesocycles : MesocycleRepository {
    val generated = mutableListOf<Pair<String, MesocycleParams>>()
    val outcomes = ArrayDeque<suspend () -> MesocycleDraft>()
    val saved = mutableListOf<MesocycleDraft>()
    var saveFailure: Exception? = null

    override suspend fun generate(clientRequestId: String, params: MesocycleParams): MesocycleDraft {
        generated += clientRequestId to params
        return outcomes.removeFirst()()
    }

    override suspend fun save(draft: MesocycleDraft): String {
        saveFailure?.let { throw it }
        saved += draft
        return "meso-local-1"
    }
}

private class FakeEntitlements(pro: Boolean) : EntitlementReader {
    val pro = MutableStateFlow(pro)
    override fun observeIsPro(): Flow<Boolean> = pro
}

@OptIn(ExperimentalCoroutinesApi::class)
class MesocycleGeneratorTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeMesocycles()
    private val entitlements = FakeEntitlements(pro = true)
    private val online = MutableStateFlow(true)
    private var ids = 0

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(saved: SavedStateHandle = SavedStateHandle()) =
        MesocycleGeneratorViewModel(repo, entitlements, online, saved, newRequestId = { "req-${++ids}" }).also { advanceUntilIdle() }

    @Test fun `not Pro locks generation and nothing is requested`() = runTest(dispatcher) {
        entitlements.pro.value = false
        val vm = vm()
        assertEquals(MesocycleAccess.LOCKED, vm.state.value.access)
        vm.generate()
        advanceUntilIdle()
        assertTrue(repo.generated.isEmpty())
    }

    @Test fun `offline says so and sends nothing`() = runTest(dispatcher) {
        online.value = false
        val vm = vm()
        vm.generate()
        advanceUntilIdle()
        assertTrue(vm.state.value.offlineNotice)
        assertTrue(repo.generated.isEmpty())
        online.value = true
        advanceUntilIdle()
        assertEquals(false, vm.state.value.offlineNotice)
    }

    @Test fun `parameters stay inside the server's ranges`() = runTest(dispatcher) {
        val vm = vm()
        repeat(10) { vm.onDays(1); vm.onWeeks(-1); vm.onMinutes(1) }
        vm.onGoal(MesocycleGoal.FUERZA)
        assertEquals(MesocycleParams(MesocycleGoal.FUERZA, 6, 4, 90), vm.state.value.params)
    }

    @Test fun `review before save - the draft is shown and nothing is saved until Guardar`() = runTest(dispatcher) {
        repo.outcomes += { draft() }
        val vm = vm()
        vm.generate()
        assertEquals(MesocyclePhase.GENERATING, vm.state.value.phase)
        advanceUntilIdle()
        assertEquals(MesocyclePhase.REVIEW, vm.state.value.phase)
        assertEquals(draft(), vm.state.value.draft)
        assertTrue("nothing stored while reviewing", repo.saved.isEmpty())

        vm.onRename("Mi bloque")
        vm.onRemoveExercise("d1", "e2")
        vm.onSets("d2", "e3", 1)
        vm.onReps("d1", "e1", -1)
        vm.save()
        advanceUntilIdle()
        val saved = repo.saved.single()
        assertEquals("Mi bloque", saved.name)
        assertEquals(listOf("e1"), saved.days[0].exercises.map { it.key })
        assertEquals(5, saved.days[0].exercises[0].reps)
        assertEquals(6, saved.days[1].exercises[0].sets)
        assertEquals(MesocyclePhase.SAVED, vm.state.value.phase)
        assertEquals("meso-local-1", vm.state.value.savedMesocycleId)
    }

    @Test fun `a day emptied in review or a blank name blocks saving`() = runTest(dispatcher) {
        repo.outcomes += { draft() }
        val vm = vm()
        vm.generate()
        advanceUntilIdle()
        vm.onRemoveExercise("d2", "e3")
        assertEquals(false, vm.state.value.canSave)
        vm.save()
        advanceUntilIdle()
        assertTrue(repo.saved.isEmpty())
    }

    @Test fun `malformed model output is a retryable failure and the retry reuses the request id`() = runTest(dispatcher) {
        repo.outcomes += { throw CoachException(CoachFailure.UNAVAILABLE) }
        repo.outcomes += { draft() }
        val vm = vm()
        vm.generate()
        advanceUntilIdle()
        assertEquals(MesocyclePhase.FORM, vm.state.value.phase)
        assertEquals(CoachFailure.UNAVAILABLE, vm.state.value.failure)
        vm.generate()
        advanceUntilIdle()
        assertEquals(listOf("req-1", "req-1"), repo.generated.map { it.first })
        assertEquals(MesocyclePhase.REVIEW, vm.state.value.phase)
    }

    @Test fun `the pending request id survives process death so the server replays instead of regenerating`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        repo.outcomes += { draft() }
        val first = vm(handle)
        first.onDays(-1)
        first.generate()
        advanceUntilIdle()
        // Process death: a new ViewModel over the same saved state.
        repo.outcomes += { draft() }
        val restored = vm(handle)
        assertEquals(3, restored.state.value.params.daysPerWeek)
        assertNull(restored.state.value.draft)
        restored.generate()
        advanceUntilIdle()
        assertEquals(listOf("req-1", "req-1"), repo.generated.map { it.first })
    }

    @Test fun `changed parameters, Generar otra or a save start a new request id`() = runTest(dispatcher) {
        repeat(4) { repo.outcomes += { draft() } }
        val vm = vm()
        vm.generate()
        advanceUntilIdle()
        vm.regenerate()
        advanceUntilIdle()
        vm.discard()
        vm.onWeeks(1)
        vm.generate()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        val ids = repo.generated.map { it.first }
        assertEquals(3, ids.distinct().size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test fun `server PRO_REQUIRED locks the screen`() = runTest(dispatcher) {
        repo.outcomes += { throw CoachException(CoachFailure.PRO_REQUIRED) }
        val vm = vm()
        vm.generate()
        advanceUntilIdle()
        assertEquals(MesocycleAccess.LOCKED, vm.state.value.access)
    }

    @Test fun `a failed save keeps the draft for another try`() = runTest(dispatcher) {
        repo.outcomes += { draft() }
        repo.saveFailure = MesocycleSaveException("gone")
        val vm = vm()
        vm.generate()
        advanceUntilIdle()
        vm.save()
        advanceUntilIdle()
        assertEquals(MesocyclePhase.REVIEW, vm.state.value.phase)
        assertTrue(vm.state.value.saveFailed)
        assertEquals(draft(), vm.state.value.draft)
    }

    @Test fun `a second generate while one is running is ignored`() = runTest(dispatcher) {
        val gate = CompletableDeferred<MesocycleDraft>()
        repo.outcomes += { gate.await() }
        val vm = vm()
        vm.generate()
        vm.generate()
        gate.complete(draft())
        advanceUntilIdle()
        assertEquals(1, repo.generated.size)
    }
}

/** The screens at 200% font: locked, form, offline/failure notices, and the review with its edit actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class MesocycleScreensTest {
    @get:Rule val rule = createComposeRule()

    private val log = mutableListOf<String>()
    private val actions = MesocycleActions(
        onBack = { log += "back" },
        onGoal = { log += "goal:$it" },
        onDays = { log += "days:$it" },
        onWeeks = { log += "weeks:$it" },
        onMinutes = { log += "minutes:$it" },
        onGenerate = { log += "generate" },
        onDismissNotice = { log += "dismiss" },
        onRename = { log += "rename:$it" },
        onSets = { d, e, delta -> log += "sets:$d/$e/$delta" },
        onReps = { d, e, delta -> log += "reps:$d/$e/$delta" },
        onRemove = { d, e -> log += "remove:$d/$e" },
        onSave = { log += "save" },
        onRegenerate = { log += "regenerate" },
        onDiscard = { log += "discard" },
    )

    private fun show(state: MesocycleGeneratorUiState) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) { GymLogTheme { MesocycleContent(state, actions) } }
        }
        rule.waitForIdle()
    }

    private fun scrollTo(text: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    private val available = MesocycleGeneratorUiState(access = MesocycleAccess.AVAILABLE)

    @Test fun `locked explains Pro and offers no generation`() {
        show(MesocycleGeneratorUiState(access = MesocycleAccess.LOCKED))
        rule.onNodeWithText("MESOCICLO PRO").assertIsDisplayed()
        rule.onNodeWithText("Generar propuesta").assertDoesNotExist()
    }

    @Test fun `the form has labelled 48dp steppers, the goal choice and the disclaimer`() {
        show(available)
        rule.onNodeWithContentDescription("Más Días por semana").performClick()
        rule.onNodeWithContentDescription("Menos Semanas").performClick()
        val bounds = rule.onNodeWithContentDescription("Más Días por semana").getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp && bounds.right - bounds.left >= 48.dp)
        rule.onNodeWithContentDescription("Fuerza").performClick()
        scrollTo("Generar propuesta")
        rule.onNodeWithText("Generar propuesta").assertIsEnabled().performClick()
        scrollTo("no consejo médico")
        rule.onNodeWithText("no consejo médico", substring = true).assertIsDisplayed()
        assertEquals(listOf("days:1", "weeks:-1", "goal:FUERZA", "generate"), log)
    }

    @Test fun `a retryable failure notice offers Reintentar`() {
        show(available.copy(failure = CoachFailure.UNAVAILABLE))
        scrollTo("Reintenta")
        rule.onNodeWithText("No se pudo generar la propuesta", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Reintentar").performClick()
        assertEquals(listOf("generate"), log)
    }

    @Test fun `offline notice`() {
        show(available.copy(online = false, offlineNotice = true))
        scrollTo("Sin conexión")
        rule.onNodeWithText("Sin conexión", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Reintentar").assertDoesNotExist()
    }

    @Test fun `review shows the draft, its progression, omitted exercises and the edit actions`() {
        show(available.copy(phase = MesocyclePhase.REVIEW, draft = draft()))
        rule.onNodeWithText("REVISA LA PROPUESTA").assertIsDisplayed()
        scrollTo("Semana 4 (descarga)")
        rule.onNodeWithText("Semana 4 (descarga): Menos volumen").assertIsDisplayed()
        scrollTo("Press militar")
        scrollTo("Día 1 · Torso")
        rule.onNodeWithContentDescription("Press banca, 4 series de 6 reps, descanso 180 s").assertExists()
        for (action in listOf("Quitar Remo", "Una serie más en Press banca", "Una rep menos en Press banca")) {
            rule.onNode(hasScrollAction()).performScrollToNode(hasContentDescription(action))
            rule.onNodeWithContentDescription(action).performClick()
        }
        scrollTo("Guardar mesociclo")
        rule.onNodeWithText("Guardar mesociclo").assertIsEnabled().performClick()
        assertEquals(listOf("remove:d1/e2", "sets:d1/e1/1", "reps:d1/e1/-1", "save"), log)
    }

    @Test fun `an emptied day disables saving and says why`() {
        val d = draft()
        show(available.copy(phase = MesocyclePhase.REVIEW, draft = d.copy(days = listOf(d.days[0], d.days[1].copy(exercises = emptyList())))))
        scrollTo("no tiene ejercicios")
        rule.onNodeWithText("Este día no tiene ejercicios", substring = true).assertIsDisplayed()
        scrollTo("Guardar mesociclo")
        rule.onNodeWithText("Guardar mesociclo").assertIsNotEnabled()
    }

    @Test fun `while saving the edit actions are gone`() {
        show(available.copy(phase = MesocyclePhase.SAVING, draft = draft()))
        rule.onNodeWithContentDescription("Quitar Remo").assertDoesNotExist()
    }

    @Test fun `the routine list entry is locked without Pro`() {
        rule.setContent { GymLogTheme { MesocycleAction(isPro = false, onGenerate = { log += "open" }) } }
        rule.onNodeWithText("Mesociclo con IA · Pro").assertIsNotEnabled()
    }
}
