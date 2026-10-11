package com.chrisgoac.gymlog

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.designsystem.chart.BarChart
import com.chrisgoac.gymlog.designsystem.chart.ChartPoint
import com.chrisgoac.gymlog.designsystem.chart.ChartText
import com.chrisgoac.gymlog.designsystem.chart.LineChart
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.ExerciseRecords
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.PeriodSummary
import com.chrisgoac.gymlog.domain.stats.ProgressPoint
import com.chrisgoac.gymlog.domain.stats.ProgressionSuggestion
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.domain.stats.Stall
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.VolumeOverview
import com.chrisgoac.gymlog.domain.stats.WeekRecord
import com.chrisgoac.gymlog.domain.stats.WeeklyInsight
import com.chrisgoac.gymlog.domain.stats.WeeklySummary
import com.chrisgoac.gymlog.domain.stats.WeeklyVolumeDelta
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.progress.ExerciseProgressScreen
import com.chrisgoac.gymlog.progress.ExerciseProgressViewModel
import com.chrisgoac.gymlog.progress.ProgressClock
import com.chrisgoac.gymlog.progress.ProgressScreen
import com.chrisgoac.gymlog.progress.ProgressViewModel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

private val MADRID = ZoneId.of("Europe/Madrid")
private val NOW = LocalDate.of(2026, 8, 12).atStartOfDay(MADRID).toInstant().toEpochMilli()
private fun week(day: Int) = LocalDate.of(2026, 8, day).atStartOfDay(MADRID).toInstant().toEpochMilli()

/** The screen's LazyColumn (the filter chip rows scroll horizontally). */
private val VERTICAL_LIST = SemanticsMatcher("vertical scroll container") { SemanticsProperties.VerticalScrollAxisRange in it.config }

private val CHART_TEXT = ChartText("Ver como tabla", "Ocultar tabla", "Fecha", "Valor", "Sin datos")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class ProgressScreensTest {
    @get:Rule val rule = createComposeRule()

    private val stats = ProgFakeStats()
    private val pro = ProgFakeEntitlements()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        rule.waitForIdle()
    }

    private fun trained() {
        stats.volume.value = VolumeOverview(
            PeriodSummary(5, 14_250.0),
            listOf(WeeklyVolumeDelta(week(3), 6_000.0, null), WeeklyVolumeDelta(week(10), 8_250.0, 38)),
        )
        stats.weekly.value = WeeklyInsight(WeeklySummary(2, 8_250.0, 6_000.0, 38), listOf(WeekRecord("e1", "Press banca", RecordKind.ONE_REP_MAX)))
        stats.stalls.value = listOf(Stall("e2", "Sentadilla", 4, week(3)))
        stats.suggestions.value = listOf(ProgressionSuggestion("e2", "Sentadilla", 4, "Baja el peso un 10 % y vuelve a subir."))
        stats.balance.value = listOf(BalanceRow(MuscleGroup.PECHO, 6_000.0, 2, false, 80), BalanceRow(MuscleGroup.ESPALDA, 100.0, 40, true, null))
    }

    private fun showProgress(fontScale: Float = 2f, onReport: (() -> Unit)? = null, opened: MutableList<String> = mutableListOf()) {
        val viewModel = ProgressViewModel(
            stats, ProgGyms(), ProgFakeExercises(listOf(progExercise("e1", "Press banca"))), pro,
            SavedStateHandle(), flowOf(Unit), { ProgressClock(NOW, MADRID) },
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GymLogTheme { ProgressScreen(viewModel, onOpenExercise = { id, _ -> opened += id }, onOpenReport = onReport) }
            }
        }
        idle()
    }

    private fun scrollTo(matcher: androidx.compose.ui.test.SemanticsMatcher) {
        rule.onNode(VERTICAL_LIST).performScrollToNode(matcher)
    }

    @Test fun weeklySummaryCardsReadAsOneStopEachAtDoubleFont() {
        trained()
        showProgress()
        rule.onNodeWithContentDescription("Sesiones esta semana: 2 de 3").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volumen frente a la semana anterior: Sube un 38 %").assertExists()
        scrollTo(hasContentDescription("Volumen: 14,3 t kg·rep"))
        rule.onNodeWithContentDescription("Sesiones: 5").assertExists()
    }

    @Test fun volumeChartHasSummaryPointNodesAndATableFromTheSamePoints() {
        trained()
        showProgress()
        scrollTo(hasContentDescription("Volumen por semana", substring = true))
        val summary = rule.onNodeWithContentDescription("Volumen por semana (kg·rep), 2 puntos", substring = true)
        summary.assertExists()
        // One semantic node per bar, with the same text the table will show.
        rule.onNodeWithContentDescription("03/08: 6 t").assertExists()
        rule.onNodeWithContentDescription("10/08: 8,3 t, 38 % más que la anterior").assertExists()
        scrollTo(hasText("Ver como tabla"))
        rule.onNodeWithText("Ver como tabla").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Ocultar tabla").assertExists()
        rule.onAllNodesWithContentDescription("10/08: 8,3 t, 38 % más que la anterior").assertCountEquals(2)
    }

    @Test fun stallShowsTheSuggestionAndOpensTheExercise() {
        trained()
        val opened = mutableListOf<String>()
        showProgress(opened = opened)
        scrollTo(hasContentDescription("Sentadilla", substring = true))
        rule.onNodeWithContentDescription("Sentadilla. 4 sesiones sin mejorar", substring = true).assertHasClickAction().performClick()
        assertEquals(listOf("e2"), opened)
        rule.onNodeWithContentDescription("Sugerencia: Baja el peso un 10 % y vuelve a subir.", substring = true).assertExists()
    }

    @Test fun balanceRowsAreTextFirstAndFlagTheNeglectedGroup() {
        trained()
        showProgress()
        scrollTo(hasContentDescription("Espalda:", substring = true))
        rule.onNodeWithContentDescription("Espalda: 100 kg·rep, hace 40 d, descuidado").assertExists()
        rule.onNodeWithContentDescription("Pecho: 6000 kg·rep, 80 % de la meta semanal, hace 2 d").assertExists()
    }

    @Test fun emptyHistoryExplainsHowToStart() {
        showProgress()
        rule.onNodeWithText("AÚN NO HAY PROGRESO QUE MOSTRAR").assertIsDisplayed()
    }

    @Test fun periodChipsAreSelectableAndReQuery() {
        trained()
        showProgress()
        rule.onNodeWithContentDescription("4 sem, seleccionado").assertExists()
        rule.onNodeWithContentDescription("Año").performClick()
        idle()
        rule.onNodeWithContentDescription("Año, seleccionado").assertExists()
        assertEquals(Period.YEAR, stats.volumeFilters.last().period)
    }

    @Test fun reportActionIsLockedWithoutProAndEnabledWithProAndADestination() {
        showProgress()
        scrollTo(hasContentDescription("Informe inteligente", substring = true))
        rule.onNodeWithContentDescription("Informe inteligente. Requiere Pro").assertIsNotEnabled()
        rule.onNodeWithText("Informe inteligente · Pro").assertExists()
    }

    @Test fun reportActionWithProButNoDestinationYetStaysDisabled() {
        pro.pro.value = true
        showProgress(onReport = null)
        scrollTo(hasContentDescription("Informe inteligente", substring = true))
        rule.onNodeWithContentDescription("Informe inteligente. Disponible próximamente").assertIsNotEnabled()
    }

    @Test fun reportActionOpensItsDestinationForPro() {
        pro.pro.value = true
        var opened = 0
        showProgress(onReport = { opened++ })
        scrollTo(hasContentDescription("Informe inteligente"))
        rule.onNodeWithContentDescription("Informe inteligente").assertIsEnabled().performClick()
        assertEquals(1, opened)
    }

    // ---- Drill-down ----

    private fun showExercise(fontScale: Float = 2f) {
        val viewModel = ExerciseProgressViewModel(
            "e1", stats, ProgFakeExercises(listOf(progExercise("e1", "Press banca"))), ProgGyms(), SavedStateHandle(), flowOf(Unit),
            { ProgressClock(NOW, MADRID) }, StatsFilter(Period.THREE_MONTHS, GymFilter.All),
        )
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GymLogTheme { ExerciseProgressScreen(viewModel, onBack = {}) }
            }
        }
        idle()
    }

    @Test fun drillDownShowsRecordsAndALineChartWithATable() {
        stats.records.value = ExerciseRecords(85.0, 95.0)
        stats.oneRepMax.value = 96.5
        stats.series.value = listOf(ProgressPoint(week(3), 80.0, 90.0, 800.0), ProgressPoint(week(10), 85.0, 95.0, 850.0))
        showExercise()
        rule.onNodeWithContentDescription("Peso máximo: 85 kg").assertExists()
        rule.onNodeWithContentDescription("Mejor 1RM estimado: 96,5 kg").assertExists()
        scrollTo(hasContentDescription("1RM estimado por sesión, 2 puntos", substring = true))
        rule.onNodeWithContentDescription("03/08: 90").assertExists()
        scrollTo(hasText("Ver como tabla"))
        rule.onNodeWithText("Ver como tabla").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("10/08: 95").assertCountEquals(2)
    }

    @Test fun drillDownWithASingleSessionStillDrawsAndExplains() {
        stats.records.value = ExerciseRecords(60.0, 70.0)
        stats.series.value = listOf(ProgressPoint(week(3), 60.0, 70.0, 600.0))
        showExercise()
        scrollTo(hasContentDescription("un punto", substring = true))
        rule.onNodeWithText("Necesitas al menos 2 registros para ver la evolución.").assertExists()
    }

    @Test fun drillDownWithoutHistorySaysSo() {
        showExercise()
        rule.onNodeWithText("Sin datos todavía para este ejercicio.").assertIsDisplayed()
    }

    // ---- Chart components ----

    private fun showChart(fontScale: Float = 2f, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { GymLogTheme { content() } }
        }
        rule.waitForIdle()
    }

    @Test fun equalValuesDrawAFlatLineWithOneAxisLabelAndAllNodes() {
        val points = listOf(ChartPoint("01/08", 80.0), ChartPoint("08/08", 80.0), ChartPoint("15/08", 80.0))
        showChart { LineChart(points, "Resumen plano", { "${it.toInt()} kg" }, CHART_TEXT) }
        rule.onNodeWithContentDescription("Resumen plano").assertExists()
        rule.onAllNodesWithContentDescription("80 kg", substring = true).assertCountEquals(3)
    }

    @Test fun emptyChartShowsItsFallbackText() {
        showChart { LineChart(emptyList(), "x", { "$it" }, CHART_TEXT) }
        rule.onNodeWithText("Sin datos").assertIsDisplayed()
    }

    @Test fun nonFinitePointsNeverReachTheChart() {
        val points = listOf(ChartPoint("a", Double.NaN), ChartPoint("b", 10.0), ChartPoint("c", Double.POSITIVE_INFINITY))
        showChart { BarChart(points, "Barras", { "${it.toInt()}" }, CHART_TEXT) }
        rule.onNodeWithContentDescription("b: 10").assertExists()
        rule.onNodeWithText("Ver como tabla").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("a: ", substring = true).assertCountEquals(0)
    }

    @Test fun longSeriesSkipPointNodesButKeepTheTable() {
        val points = (1..90).map { ChartPoint("d$it", it.toDouble()) }
        showChart { LineChart(points, "Larga", { "${it.toInt()}" }, CHART_TEXT) }
        rule.onAllNodesWithContentDescription("d5: 5").assertCountEquals(0)
        rule.onNodeWithText("Ver como tabla").performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("d5: 5").assertCountEquals(1)
    }

    @Test fun selectingAPointReadsItOut() {
        val points = listOf(ChartPoint("01/08", 60.0), ChartPoint("08/08", 70.0))
        showChart { LineChart(points, "Serie", { "${it.toInt()} kg" }, CHART_TEXT) }
        rule.onAllNodesWithContentDescription("08/08: 70 kg").onFirst().performClick()
        rule.waitForIdle()
        // The polite live readout repeats the point as visible text.
        rule.onNodeWithText("08/08: 70 kg").assertIsDisplayed()
    }

    @Test fun chartAndToggleStayInsideTheScreenAtDoubleFont() {
        val points = (1..8).map { ChartPoint("0$it/08", it * 10.0) }
        showChart { LineChart(points, "Serie", { "${it.toInt()} kg" }, CHART_TEXT) }
        val bounds = rule.onNodeWithText("Ver como tabla").getUnclippedBoundsInRoot()
        assertTrue("toggle inside the screen: $bounds", bounds.right <= 360.dp && bounds.left >= 0.dp)
    }
}
