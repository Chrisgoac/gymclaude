package com.chrisgoac.gymlog

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import com.chrisgoac.gymlog.achievements.AchievementsScreen
import com.chrisgoac.gymlog.achievements.AchievementsViewModel
import com.chrisgoac.gymlog.designsystem.theme.GymLogTheme
import com.chrisgoac.gymlog.domain.achievement.AchievementRepository
import com.chrisgoac.gymlog.domain.achievement.PersonalRecord
import com.chrisgoac.gymlog.domain.achievement.UnlockedAchievement
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.domain.stats.WeeklyStreak
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import java.time.ZoneId

private val LIST = SemanticsMatcher("vertical scroll container") { SemanticsProperties.VerticalScrollAxisRange in it.config }
private val MADRID = ZoneId.of("Europe/Madrid")

private class AchievementsTestRepository : AchievementRepository {
    val unlocked = MutableStateFlow<List<UnlockedAchievement>>(emptyList())
    val records = MutableStateFlow<List<PersonalRecord>>(emptyList())
    var failure: Throwable? = null
    override fun observeUnlocked(): Flow<List<UnlockedAchievement>> = unlocked
    override fun observePersonalRecords(): Flow<List<PersonalRecord>> = flow {
        failure?.let { throw it }
        records.collect { emit(it) }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h800dp-xhdpi")
class AchievementsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val stats = ProgFakeStats().apply { streak.value = Streak(2, WeeklyStreak(3, 5), 3) }
    private val repo = AchievementsTestRepository()

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        rule.waitForIdle()
    }

    private fun show(fontScale: Float = 2f, onBack: () -> Unit = {}) {
        val viewModel = AchievementsViewModel(stats, repo, { MADRID }, recomputeIntervalMillis = 10)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GymLogTheme { AchievementsScreen(viewModel, onBack = onBack) }
            }
        }
        idle()
    }

    private fun scrollTo(text: String) {
        rule.onNode(LIST).performScrollToNode(hasText(text, substring = true))
    }

    @Test fun streakIsOneStopThatSaysCurrentBestTargetAndDays() {
        show()
        rule.onNodeWithContentDescription("Racha actual. 3 semanas. Mejor racha: 5 semanas. Objetivo: 3 entrenos por semana. 2 días seguidos entrenando").assertExists()
        rule.onNodeWithText("3 semanas").assertIsDisplayed()
    }

    @Test fun lockedAndUnlockedMilestonesSayTheirStateInWordsAtDoubleFontScale() {
        repo.unlocked.value = listOf(UnlockedAchievement("sesiones-10", java.time.LocalDate.of(2026, 6, 12).atStartOfDay(MADRID).toInstant().toEpochMilli()))
        show()
        scrollTo("Calentando")
        rule.onNodeWithContentDescription("Calentando. 10 entrenos. Desbloqueado el 12/06/26").assertExists()
        rule.onNodeWithText("Desbloqueado el 12/06/26").assertIsDisplayed()
        scrollTo("Constante")
        rule.onNodeWithContentDescription("Constante. 50 entrenos. Bloqueado").assertExists()
        rule.onNodeWithText("1 de 11 desbloqueados").assertExists()
    }

    @Test fun recordsListWeightAndDateOrAnEmptyHint() {
        show()
        scrollTo("Aún no hay récords")
        rule.onNodeWithText("Aún no hay récords. ¡A entrenar!").assertExists()

        repo.records.value = listOf(PersonalRecord("e1", "Press de banca", 102.5, java.time.LocalDate.of(2026, 5, 3).atStartOfDay(MADRID).toInstant().toEpochMilli()))
        idle()
        scrollTo("Press de banca")
        rule.onNodeWithContentDescription("Press de banca: 102,5 kg, conseguido el 03/05/26").assertExists()
        rule.onNodeWithText("102,5 kg · 03/05/26").assertExists()
    }

    @Test fun errorStateOffersRetryAndBackWorks() {
        repo.failure = IllegalStateException("db")
        var backs = 0
        show(onBack = { backs++ })
        rule.onNodeWithText("No se pudieron cargar los logros", ignoreCase = true).assertExists()
        repo.failure = null
        rule.onNodeWithText("Reintentar").performClick()
        idle()
        rule.onNodeWithText("Racha actual", ignoreCase = true).assertExists()
        rule.onNodeWithText("Atrás", ignoreCase = true).performClick()
        assertEquals(1, backs)
    }
}
