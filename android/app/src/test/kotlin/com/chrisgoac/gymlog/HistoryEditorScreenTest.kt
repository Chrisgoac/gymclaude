package com.chrisgoac.gymlog

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.data.workout.OfflineHistoryRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutLogRepository
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.HistoryDetail
import com.chrisgoac.gymlog.history.HistoryDetailScreen
import com.chrisgoac.gymlog.history.HistoryDetailUiState
import com.chrisgoac.gymlog.history.HistoryDetailViewModel
import com.chrisgoac.gymlog.history.EditorStatus
import com.chrisgoac.gymlog.history.HistoryEditorScreen
import com.chrisgoac.gymlog.history.HistoryEditorViewModel
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import java.time.ZoneOffset

/** The history editor over a real (in-memory) Room stack: what reaches the outbox is what is asserted. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class HistoryEditorScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var now = 1_800_000_000_000L
    private lateinit var db: GymLogDatabase
    private lateinit var workouts: OfflineWorkoutRepository
    private lateinit var exercises: OfflineExerciseRepository
    private lateinit var history: OfflineHistoryRepository
    private lateinit var save: DefaultSaveWorkoutDraftUseCase
    private lateinit var sessionId: String

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java)
            .allowMainThreadQueries().build()
        val manager = Manager(db)
        val runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
        val runnerFor = { _: GymLogDatabase -> runner }
        exercises = OfflineExerciseRepository(manager, runnerFor)
        workouts = OfflineWorkoutRepository(manager, runnerFor, now = { now })
        val log = OfflineWorkoutLogRepository(manager, runnerFor)
        history = OfflineHistoryRepository(manager, runnerFor, exercises, OfflineRoutineRepository(manager, runnerFor, exercises), now = { now })
        save = DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(manager, runnerFor)) { now }

        db.catalogDao().upsertAll(listOf(CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null)))
        runner.run(LocalMutation { put(GymRecord(RecordId("g1"), nextTimestamp(SyncTable.GYMS, RecordId("g1")), null, "Casa", 0, false)) })
        sessionId = workouts.startFreeWorkout("g1").sessionId
        val press = log.addExercise(sessionId, "seed-press")
        log.addSet(press, 60.0, 8)
        log.addSet(press, 62.5, 6)
        now += 45 * 60_000L
        FinishWorkoutUseCase(workouts, workouts)(sessionId, "Bien")
        now += 24 * 60 * 60_000L
    }

    @After fun tearDown() = db.close()

    private fun outboxSize(): Int = runBlocking { db.syncDao().allOutbox().size }

    private fun detail(): HistoryDetail = runBlocking { history.observeDetail(sessionId).first()!! }

    private fun editor(handle: SavedStateHandle = SavedStateHandle()) =
        HistoryEditorViewModel(sessionId, history, workouts, exercises, save, handle, now = { now }, zone = ZoneOffset.UTC)

    private fun show(viewModel: HistoryEditorViewModel, onExit: () -> Unit = {}) {
        rule.setContent { GymLogTheme { HistoryEditorScreen(viewModel, onExit = onExit) } }
        ready(viewModel)
    }

    /** The original is read from Room on its own thread: wait until the editor holds it. */
    private fun ready(viewModel: HistoryEditorViewModel) = await("editor loaded") { viewModel.state.value.status == EditorStatus.Ready }

    /** Room works on its own threads and resumes on the main looper: keep the looper running while waiting. */
    private fun await(what: String, condition: () -> Boolean) {
        repeat(250) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) {
                rule.waitForIdle()
                return
            }
            Thread.sleep(20)
        }
        error("timed out waiting for: $what")
    }

    private val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun weightField(set: Int) = rule.onNodeWithContentDescription("Peso de la serie $set de Press de banca, kilos")

    private fun scrollTo(text: String) = rule.onNode(verticalList).performScrollToNode(hasText(text))

    @Test fun detailOffersTheEditor() {
        var opened = 0
        val viewModel = HistoryDetailViewModel(history, workouts, sessionId)
        rule.setContent { GymLogTheme { HistoryDetailScreen(viewModel, onBack = {}, onEdit = { opened++ }) } }
        await("detail loaded") { viewModel.state.value is HistoryDetailUiState.Found }
        rule.onNodeWithText("Editar entreno").performClick()
        assertEquals(1, opened)
    }

    @Test fun savingAnEditWritesOneOutboxRowAndCloses() {
        var exited = 0
        val before = outboxSize()
        val viewModel = editor()
        show(viewModel, onExit = { exited++ })

        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos"))
        weightField(1).performTextClearance()
        weightField(1).performTextInput("70")
        rule.onNodeWithContentDescription("Quitar la serie 2 de Press de banca").performClick()
        scrollTo("Guardar cambios")
        rule.onNodeWithText("Guardar cambios").performClick()
        await("editor closed: ${'$'}{viewModel.state.value}") { viewModel.exit.value }
        assertEquals(1, exited)

        assertEquals(before + 1, outboxSize())
        val stored = detail().workout.exercises.single().sets
        assertEquals(listOf(70.0), stored.map { it.weight })
    }

    @Test fun cancellingWithChangesAsksAndDiscardingWritesNothing() {
        var exited = 0
        val before = outboxSize()
        val original = detail()
        show(editor(), onExit = { exited++ })

        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos"))
        weightField(1).performTextClearance()
        weightField(1).performTextInput("99")
        rule.onNodeWithText("CANCELAR").performClick()
        rule.onNodeWithText("¿DESCARTAR CAMBIOS?").assertExists()
        rule.onNodeWithText("Seguir editando").performClick()
        rule.onNodeWithText("¿DESCARTAR CAMBIOS?").assertDoesNotExist()
        assertEquals(0, exited)

        rule.onNodeWithText("CANCELAR").performClick()
        rule.onNodeWithText("Descartar").performClick()
        rule.waitForIdle()
        assertEquals(1, exited)
        assertEquals(before, outboxSize())
        assertEquals(original, detail())
    }

    @Test fun invalidValuesAreSummarizedAndNothingIsWritten() {
        val before = outboxSize()
        show(editor())
        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos"))
        weightField(1).performTextClearance()
        weightField(1).performTextInput("1001")
        rule.onNodeWithText("Máximo 1000 kg").assertExists()
        scrollTo("Guardar cambios")
        rule.onNodeWithText("Guardar cambios").performClick()
        rule.waitForIdle()
        scrollTo("REVISA ESTOS CAMPOS")
        rule.onNodeWithText("• Press de banca, serie 1, peso: Máximo 1000 kg").assertExists()
        assertEquals(before, outboxSize())
    }

    /** Rotation: the activity is recreated, the ViewModel (and its draft) is kept, the typed value is still on screen. */
    @Test fun typedEditsSurviveRotation() {
        val before = outboxSize()
        var first: HistoryEditorViewModel? = null
        fun content(activity: ComponentActivity) = activity.setContent {
            GymLogTheme {
                val vm = viewModel { editor(createSavedStateHandle()) }
                if (first == null) first = vm else assertSame(first, vm)
                HistoryEditorScreen(vm, onExit = {})
            }
        }
        rule.activityRule.scenario.onActivity(::content)
        ready(first!!)
        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos"))
        weightField(1).performTextClearance()
        weightField(1).performTextInput("77")

        rule.activityRule.scenario.recreate()
        rule.activityRule.scenario.onActivity(::content)
        rule.waitForIdle()

        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos"))
        rule.onNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos") and hasText("77")).assertExists()
        assertEquals(before, outboxSize())
    }

    /** Process death: a new ViewModel built only from the persisted saved-state values shows and saves the draft. */
    @Test fun draftSurvivesProcessRecreationAndStillSaves() {
        val handle = SavedStateHandle()
        val before = outboxSize()
        val first = editor(handle)
        ready(first)
        first.onWeight(detail().workout.exercises.single().sets[0].id, "80")
        first.onNotes("Tras reinicio")

        var exited = 0
        val restored = editor(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        show(restored, onExit = { exited++ })
        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos"))
        rule.onNode(hasContentDescription("Peso de la serie 1 de Press de banca, kilos") and hasText("80")).assertExists()
        rule.onNodeWithText("Tras reinicio").assertExists()
        assertEquals(before, outboxSize())

        scrollTo("Guardar cambios")
        rule.onNodeWithText("Guardar cambios").performClick()
        await("editor closed: ${'$'}{restored.state.value}") { restored.exit.value }
        assertEquals(1, exited)
        assertEquals(before + 1, outboxSize())
        val stored = detail().workout
        assertEquals("Tras reinicio", stored.session.notes)
        assertEquals(80.0, stored.exercises.single().sets[0].weight, 0.0)
        assertTrue(stored.exercises.single().sets.size == 2)
    }
}
