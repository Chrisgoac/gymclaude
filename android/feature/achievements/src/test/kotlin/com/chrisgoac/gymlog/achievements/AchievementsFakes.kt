package com.chrisgoac.gymlog.achievements

import com.chrisgoac.gymlog.domain.achievement.AchievementRepository
import com.chrisgoac.gymlog.domain.achievement.PersonalRecord
import com.chrisgoac.gymlog.domain.achievement.UnlockedAchievement
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.ExerciseRecords
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.PeriodSummary
import com.chrisgoac.gymlog.domain.stats.ProgressPoint
import com.chrisgoac.gymlog.domain.stats.ProgressionSuggestion
import com.chrisgoac.gymlog.domain.stats.Stall
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.StatsRepository
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.domain.stats.VolumeOverview
import com.chrisgoac.gymlog.domain.stats.WeeklyInsight
import com.chrisgoac.gymlog.domain.stats.WeeklyStreak
import com.chrisgoac.gymlog.domain.stats.WeeklySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Only the streak is read by the Achievements screen. */
class FakeStats(streak: Streak = Streak(0, WeeklyStreak(0, 0), 3)) : StatsRepository {
    val streak = MutableStateFlow(streak)

    override fun observeStreak(): Flow<Streak> = streak
    override fun observeWeeklyInsight(gym: GymFilter): Flow<WeeklyInsight> = flowOf(WeeklyInsight(WeeklySummary(0, 0.0, 0.0, null), emptyList()))
    override fun observeExerciseSeries(exerciseId: String, filter: StatsFilter): Flow<List<ProgressPoint>> = emptyFlow()
    override fun observeExerciseRecords(exerciseId: String, gym: GymFilter): Flow<ExerciseRecords?> = emptyFlow()
    override fun observeEstimatedOneRepMax(exerciseId: String, gym: GymFilter): Flow<Double?> = emptyFlow()
    override fun observeVolume(filter: StatsFilter): Flow<VolumeOverview> = flowOf(VolumeOverview(PeriodSummary(0, 0.0), emptyList()))
    override fun observeMuscleBalance(filter: StatsFilter): Flow<List<BalanceRow>> = emptyFlow()
    override fun observeStalls(gym: GymFilter): Flow<List<Stall>> = emptyFlow()
    override fun observeProgressionSuggestions(gym: GymFilter): Flow<List<ProgressionSuggestion>> = emptyFlow()
}

class FakeAchievements : AchievementRepository {
    val unlocked = MutableStateFlow<List<UnlockedAchievement>>(emptyList())
    val records = MutableStateFlow<List<PersonalRecord>>(emptyList())
    var failure: Throwable? = null

    override fun observeUnlocked(): Flow<List<UnlockedAchievement>> = unlocked
    override fun observePersonalRecords(): Flow<List<PersonalRecord>> = kotlinx.coroutines.flow.flow {
        failure?.let { throw it }
        records.collect { emit(it) }
    }
}
