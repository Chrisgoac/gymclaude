package com.chrisgoac.gymlog.achievements

import com.chrisgoac.gymlog.domain.achievement.AchievementRules
import com.chrisgoac.gymlog.domain.achievement.PersonalRecord
import com.chrisgoac.gymlog.domain.achievement.UnlockedAchievement
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.domain.stats.WeeklyStreak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stats = FakeStats(Streak(2, WeeklyStreak(3, 5), 3))
    private val achievements = FakeAchievements()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AchievementsViewModel(stats, achievements, zone = { ZoneId.of("Europe/Madrid") }, recomputeIntervalMillis = 100)

    @Test fun `the whole catalog is listed, unlocked ones with their date and the rest locked`() = runTest(dispatcher) {
        achievements.unlocked.value = listOf(UnlockedAchievement("sesiones-10", 1_000L), UnlockedAchievement("racha-4", 2_000L), UnlockedAchievement("legacy-key", 3_000L))
        achievements.records.value = listOf(PersonalRecord("e1", "Press de banca", 100.0, 5_000L))
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect { } }
        advanceTimeBy(1_000)

        val state = vm.state.value
        assertEquals(AchievementsStatus.Ready, state.status)
        assertEquals(AchievementRules.catalog.map { it.key }, state.milestones.map { it.key })
        assertEquals(mapOf("sesiones-10" to 1_000L, "racha-4" to 2_000L), state.milestones.filter { it.unlocked }.associate { it.key to it.unlockedAt })
        assertEquals(2, state.unlockedCount)
        assertEquals(3, state.streak!!.weeks.current)
        assertEquals(listOf("Press de banca"), state.records.map { it.name })
    }

    @Test fun `a failing source shows the error state and retry recovers`() = runTest(dispatcher) {
        achievements.failure = IllegalStateException("db")
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect { } }
        advanceTimeBy(1_000)
        assertEquals(AchievementsStatus.Failed, vm.state.value.status)

        achievements.failure = null
        vm.retry()
        advanceTimeBy(1_000)
        assertEquals(AchievementsStatus.Ready, vm.state.value.status)
    }

    @Test fun `a later unlock refreshes the screen`() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect { } }
        advanceTimeBy(1_000)
        assertFalse(vm.state.value.milestones.any { it.unlocked })
        assertNull(vm.state.value.milestones.first().unlockedAt)

        achievements.unlocked.value = listOf(UnlockedAchievement("sesiones-10", 9L))
        advanceTimeBy(1_000)
        assertTrue(vm.state.value.milestones.first { it.key == "sesiones-10" }.unlocked)
    }
}
