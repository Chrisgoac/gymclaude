package com.chrisgoac.gymlog.progress

import com.chrisgoac.gymlog.domain.entitlement.EntitlementReader
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
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
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

import kotlinx.coroutines.flow.onStart

internal val EMPTY_WEEKLY = WeeklyInsight(WeeklySummary(0, 0.0, 0.0, null), emptyList())
internal val EMPTY_STREAK = Streak(0, WeeklyStreak(0, 0), 3)
internal val EMPTY_VOLUME = VolumeOverview(PeriodSummary(0, 0.0), emptyList())

/** Stats whose every flow is a settable state, recording which filters were observed. */
internal class FakeStats : StatsRepository {
    val weekly = MutableStateFlow(EMPTY_WEEKLY)
    val streak = MutableStateFlow(EMPTY_STREAK)
    val volume = MutableStateFlow(EMPTY_VOLUME)
    val balance = MutableStateFlow<List<BalanceRow>>(emptyList())
    val stalls = MutableStateFlow<List<Stall>>(emptyList())
    val suggestions = MutableStateFlow<List<ProgressionSuggestion>>(emptyList())
    val series = MutableStateFlow<List<ProgressPoint>>(emptyList())
    val records = MutableStateFlow<ExerciseRecords?>(null)
    val oneRepMax = MutableStateFlow<Double?>(null)

    val volumeFilters = mutableListOf<StatsFilter>()
    val weeklyGyms = mutableListOf<GymFilter>()
    val seriesFilters = mutableListOf<StatsFilter>()
    var volumeSubscriptions = 0
    var failVolume: Throwable? = null

    override fun observeWeeklyInsight(gym: GymFilter): Flow<WeeklyInsight> = weekly.onStart { weeklyGyms += gym }

    override fun observeExerciseSeries(exerciseId: String, filter: StatsFilter): Flow<List<ProgressPoint>> =
        series.onStart { seriesFilters += filter }

    override fun observeExerciseRecords(exerciseId: String, gym: GymFilter): Flow<ExerciseRecords?> = records

    override fun observeEstimatedOneRepMax(exerciseId: String, gym: GymFilter): Flow<Double?> = oneRepMax

    override fun observeVolume(filter: StatsFilter): Flow<VolumeOverview> = flow {
        volumeSubscriptions++
        volumeFilters += filter
        failVolume?.let { throw it }
        emitAll(volume)
    }

    override fun observeMuscleBalance(filter: StatsFilter): Flow<List<BalanceRow>> = balance

    override fun observeStreak(): Flow<Streak> = streak

    override fun observeStalls(gym: GymFilter): Flow<List<Stall>> = stalls

    override fun observeProgressionSuggestions(gym: GymFilter): Flow<List<ProgressionSuggestion>> = suggestions
}

internal class FakeEntitlements(initial: Boolean = false) : EntitlementReader {
    val pro = MutableStateFlow(initial)
    override fun observeIsPro(): Flow<Boolean> = pro
}

internal class GymsOnly(val gyms: List<Gym> = listOf(Gym("g1", "Casa"), Gym("g2", "Fitness"))) : WorkoutRepository {
    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeGyms(): Flow<List<Gym>> = flowOf(gyms)
    override fun observeTrainingSummary(): Flow<TrainingSummary> = flowOf(TrainingSummary(0, 3, 0))
    override suspend fun startFreeWorkout(gymId: String?) = StartedWorkout("x", false)
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = StartedWorkout("x", false)
}

internal fun exercise(id: String, name: String, group: MuscleGroup = MuscleGroup.PECHO) =
    Exercise(id, name, group, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)

internal class FakeExercises(val all: List<Exercise> = emptyList()) : ExerciseRepository {
    val filters = mutableListOf<ExerciseFilter>()
    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = flowOf(all.filter(filter::matches)).onStart { filters += filter }
    override fun observeExercise(id: String): Flow<Exercise?> = flowOf(all.firstOrNull { it.id == id })
    override suspend fun createCustom(input: ExerciseInput): Exercise = error("unused")
    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("unused")
    override suspend fun deleteCustom(id: String) = error("unused")
}
