package com.chrisgoac.gymlog.progress

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.PeriodSummary
import com.chrisgoac.gymlog.domain.stats.ProgressPoint
import com.chrisgoac.gymlog.domain.stats.ExerciseRecords
import com.chrisgoac.gymlog.domain.stats.Stall
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.domain.stats.VolumeOverview
import com.chrisgoac.gymlog.domain.stats.WeeklyInsight
import com.chrisgoac.gymlog.domain.stats.WeeklyStreak
import com.chrisgoac.gymlog.domain.stats.WeeklySummary
import com.chrisgoac.gymlog.domain.stats.WeeklyVolumeDelta
import com.chrisgoac.gymlog.domain.workout.Gym
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

private val MADRID = ZoneId.of("Europe/Madrid")
private fun day(d: Int) = LocalDate.of(2026, 8, d).atStartOfDay(MADRID).toInstant().toEpochMilli()
private val NOW = day(12)

/** TalkBack-facing contract of the Progress screens on a real device/emulator, at 200% font. */
@RunWith(AndroidJUnit4::class)
class ProgressAccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private val data = ProgressData(
        weekly = WeeklyInsight(WeeklySummary(2, 8_250.0, 6_000.0, 38), emptyList()),
        streak = Streak(3, WeeklyStreak(2, 4), 3),
        volume = VolumeOverview(
            PeriodSummary(5, 14_250.0),
            listOf(WeeklyVolumeDelta(day(3), 6_000.0, null), WeeklyVolumeDelta(day(10), 8_250.0, 38)),
        ),
        balance = listOf(BalanceRow(MuscleGroup.PECHO, 6_000.0, 2, false, 80)),
        stalls = listOf(Stall("e2", "Sentadilla", 4, day(3))),
        suggestions = emptyList(),
    )

    private val state = ProgressUiState(
        status = ProgressStatus.Ready,
        gyms = listOf(Gym("g1", "Casa")),
        data = data,
        clock = ProgressClock(NOW, MADRID),
    )

    private fun show(fontScale: Float = 2f, content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { GymLogTheme { content() } }
        }
        rule.waitForIdle()
    }

    private val verticalList = SemanticsMatcher("vertical list") { SemanticsProperties.VerticalScrollAxisRange in it.config }

    private fun progress(s: ProgressUiState = state) = show {
        ProgressContent(s, {}, {}, {}, {}, {}, onOpenReport = null)
    }

    @Test
    fun chartIsSummarisedPointwiseAndHasATableAlternativeAtDoubleFont() {
        progress()
        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Volumen por semana", substring = true))
        rule.onNodeWithContentDescription("Volumen por semana (kg·rep), 2 puntos", substring = true).assertExists()
        rule.onNodeWithContentDescription("03/08: 6 t").assertExists()
        rule.onNode(verticalList).performScrollToNode(hasText("Ver como tabla"))
        rule.onNodeWithText("Ver como tabla").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithContentDescription("10/08: 8,3 t, 38 % más que la anterior").assertCountEquals(2)
    }

    @Test
    fun interactiveControlsMeetTheMinimumTouchTarget() {
        progress()
        listOf("4 sem, seleccionado", "3 meses").forEach { label ->
            val bounds = rule.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertTrue("$label is at least 48dp: $bounds", bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        }
        rule.onNode(verticalList).performScrollToNode(hasText("Ver como tabla"))
        val toggle = rule.onNodeWithText("Ver como tabla").getUnclippedBoundsInRoot()
        assertTrue("table toggle is at least 48dp high: $toggle", toggle.bottom - toggle.top >= 48.dp)
    }

    @Test
    fun lockedReportIsAnnouncedAsRequiringPro() {
        progress()
        rule.onNode(verticalList).performScrollToNode(hasContentDescription("Informe inteligente", substring = true))
        rule.onNodeWithContentDescription("Informe inteligente. Requiere Pro").assertIsNotEnabled()
    }

    @Test
    fun flatSeriesDrillDownDoesNotCollapse() {
        val flat = ExerciseProgressUiState(
            status = ProgressStatus.Ready,
            exerciseName = "Press banca",
            series = listOf(ProgressPoint(day(3), 80.0, 100.0, 800.0), ProgressPoint(day(10), 80.0, 100.0, 800.0)),
            records = ExerciseRecords(80.0, 100.0),
            estimatedOneRepMax = 100.0,
            clock = ProgressClock(NOW, MADRID),
        )
        show { ExerciseProgressContent(flat, {}, {}, {}, {}, {}) }
        rule.onNode(verticalList).performScrollToNode(hasContentDescription("1RM estimado por sesión, 2 puntos", substring = true))
        rule.onNodeWithContentDescription("1RM estimado por sesión, 2 puntos", substring = true).assertIsDisplayed()
        rule.onAllNodesWithContentDescription("03/08: 100").assertCountEquals(1)
    }
}
