package com.chrisgoac.gymlog.progress

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.ai.IntelligentReport
import com.chrisgoac.gymlog.domain.ai.IntelligentReportRepository
import com.chrisgoac.gymlog.domain.coach.CoachException
import com.chrisgoac.gymlog.domain.coach.CoachFailure
import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import java.time.ZoneId
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// 2026-01-01T10:30:00Z
private const val GENERATED = 1_767_263_400_000L

private fun report(cached: Boolean = false, id: String = "rep-1") = IntelligentReport(
    id = id,
    generatedAt = GENERATED,
    cached = cached,
    summary = "Semana sólida: cumples el objetivo.",
    strengths = listOf("Constancia"),
    improvements = listOf("Press banca estancado"),
    recommendations = listOf("Cambia el rango de reps"),
)

private class FakeReports(var last: IntelligentReport? = null) : IntelligentReportRepository {
    val outcomes = ArrayDeque<() -> IntelligentReport>()
    var calls = 0

    override suspend fun generate(): IntelligentReport {
        calls++
        return outcomes.removeFirst()().also { last = it }
    }

    override fun lastReport(): IntelligentReport? = last
}

private class Pro(value: Boolean) : EntitlementReader {
    val pro = MutableStateFlow(value)
    override fun observeIsPro(): Flow<Boolean> = pro
}

@OptIn(ExperimentalCoroutinesApi::class)
class IntelligentReportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val online = MutableStateFlow(true)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(repo: FakeReports, pro: Boolean = true) =
        IntelligentReportViewModel(repo, Pro(pro), online).also { advanceUntilIdle() }

    @Test fun `not Pro is locked and nothing is requested`() = runTest(dispatcher) {
        val repo = FakeReports()
        val vm = vm(repo, pro = false)
        assertEquals(ReportAccess.LOCKED, vm.state.value.access)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(0, repo.calls)
    }

    @Test fun `opening fetches once and Actualizar asks again and the server may answer from its cache`() = runTest(dispatcher) {
        val repo = FakeReports().apply {
            outcomes += { report() }
            outcomes += { report(cached = true) }
        }
        val vm = vm(repo)
        assertEquals(1, repo.calls)
        assertEquals(report(), vm.state.value.report)
        vm.refresh()
        advanceUntilIdle()
        assertEquals(2, repo.calls)
        assertEquals(true, vm.state.value.report?.cached)
    }

    @Test fun `offline shows the last report with its date and requests nothing`() = runTest(dispatcher) {
        online.value = false
        val repo = FakeReports(last = report())
        val vm = vm(repo)
        assertEquals(report(), vm.state.value.report)
        assertFalse(vm.state.value.canRefresh)
        assertEquals(0, repo.calls)
        // Back online: fetched then.
        repo.outcomes += { report(id = "rep-2") }
        online.value = true
        advanceUntilIdle()
        assertEquals("rep-2", vm.state.value.report?.id)
    }

    @Test fun `a failure keeps the previous report and PRO_REQUIRED locks`() = runTest(dispatcher) {
        val repo = FakeReports(last = report()).apply { outcomes += { throw CoachException(CoachFailure.UNAVAILABLE) } }
        val vm = vm(repo)
        assertEquals(CoachFailure.UNAVAILABLE, vm.state.value.failure)
        assertEquals(report(), vm.state.value.report)

        repo.outcomes += { throw CoachException(CoachFailure.PRO_REQUIRED) }
        vm.refresh()
        advanceUntilIdle()
        assertEquals(ReportAccess.LOCKED, vm.state.value.access)
    }

    @Test fun `without a previous report nothing is shown until fetched`() = runTest(dispatcher) {
        online.value = false
        val vm = vm(FakeReports())
        assertNull(vm.state.value.report)
    }
}

/** The report screen at 200% font. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class IntelligentReportScreenTest {
    @get:Rule val rule = createComposeRule()
    private val log = mutableListOf<String>()

    private fun show(state: IntelligentReportUiState) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                GymLogTheme { IntelligentReportContent(state, ZoneId.of("Europe/Madrid"), onBack = { log += "back" }, onRefresh = { log += "refresh" }) }
            }
        }
        rule.waitForIdle()
    }

    private fun scrollTo(text: String) = rule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))

    @Test fun `locked explains Pro and still shows the disclaimer`() {
        show(IntelligentReportUiState(access = ReportAccess.LOCKED))
        rule.onNodeWithText("INFORME PRO").assertIsDisplayed()
        rule.onNodeWithText("Actualizar informe").assertDoesNotExist()
        scrollTo("no consejo médico")
        rule.onNodeWithText("no consejo médico", substring = true).assertIsDisplayed()
    }

    @Test fun `the report shows its sections, date in the device zone, cache note and disclaimer`() {
        show(IntelligentReportUiState(access = ReportAccess.AVAILABLE, report = report(cached = true)))
        rule.onNodeWithText("Generado el 1 ene 2026, 11:30", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Sin cambios en tus datos", substring = true).assertIsDisplayed()
        scrollTo("Cambia el rango de reps")
        rule.onNodeWithText("• Cambia el rango de reps").assertIsDisplayed()
        scrollTo("Actualizar informe")
        rule.onNodeWithText("Actualizar informe").assertIsEnabled().performClick()
        scrollTo("no consejo médico")
        rule.onNodeWithText("no consejo médico", substring = true).assertIsDisplayed()
        assertEquals(listOf("refresh"), log)
    }

    @Test fun `offline with a cached report says so and cannot refresh`() {
        show(IntelligentReportUiState(access = ReportAccess.AVAILABLE, online = false, report = report()))
        rule.onNodeWithText("Sin conexión: este es el último informe", substring = true).assertIsDisplayed()
        scrollTo("Actualizar informe")
        rule.onNodeWithText("Actualizar informe").assertIsNotEnabled()
    }

    @Test fun `offline without a report explains the network is needed`() {
        show(IntelligentReportUiState(access = ReportAccess.AVAILABLE, online = false))
        rule.onNodeWithText("Sin conexión. El informe necesita internet.").assertIsDisplayed()
    }

    @Test fun `a retryable failure offers Reintentar`() {
        show(IntelligentReportUiState(access = ReportAccess.AVAILABLE, failure = CoachFailure.UNAVAILABLE))
        rule.onNodeWithText("No se pudo generar el informe", substring = true).assertIsDisplayed()
        scrollTo("Reintentar")
        rule.onNodeWithText("Reintentar").performClick()
        assertEquals(listOf("refresh"), log)
    }
}
