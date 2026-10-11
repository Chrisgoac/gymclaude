package com.chrisgoac.gymlog.domain.stats

import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.SaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** Period + gym selection of the Progress screen. */
data class StatsFilter(val period: Period, val gym: GymFilter)

/** This week against the last one, plus the exercises that beat a record this week (Progress "Esta semana"). */
data class WeeklyInsight(val summary: WeeklySummary, val records: List<WeekRecord>)

/** Volume of the selected period: totals and per week (each week compared with the previous bar). */
data class VolumeOverview(val summary: PeriodSummary, val weekly: List<WeeklyVolumeDelta>)

/** Day streak (from today back) and week streak against [weeklyTarget] sessions (setting `objetivoSemanal`, default 3). */
data class Streak(val days: Int, val weeks: WeeklyStreak, val weeklyTarget: Int)

/**
 * Derived progress data over the open account's history, recomputed on every relevant change
 * (sync pulls, logging, historical edits). Filters follow the PWA: the weekly insight, records,
 * stalls and suggestions honour the gym but not the period; streaks count every gym; the clock is
 * the server-corrected one and the zone the device's.
 */
interface StatsRepository {
    fun observeWeeklyInsight(gym: GymFilter): Flow<WeeklyInsight>

    /** One point per training date of [exerciseId] in the period (best 1RM, top weight, volume), oldest first. */
    fun observeExerciseSeries(exerciseId: String, filter: StatsFilter): Flow<List<ProgressPoint>>

    /** Heaviest weight and best estimated 1RM ever (in that gym); null if never trained there. */
    fun observeExerciseRecords(exerciseId: String, gym: GymFilter): Flow<ExerciseRecords?>

    /** Best estimated 1RM ever (Epley, in that gym); null if never trained there. */
    fun observeEstimatedOneRepMax(exerciseId: String, gym: GymFilter): Flow<Double?>

    fun observeVolume(filter: StatsFilter): Flow<VolumeOverview>

    /** Muscle balance rows for the period, with weekly goal progress (setting `objetivosVolumen`). */
    fun observeMuscleBalance(filter: StatsFilter): Flow<List<BalanceRow>>

    fun observeStreak(): Flow<Streak>

    fun observeStalls(gym: GymFilter): Flow<List<Stall>>

    fun observeProgressionSuggestions(gym: GymFilter): Flow<List<ProgressionSuggestion>>
}

/**
 * Broadcasts which derived data a write made stale ([InvalidationScope], from [SaveResult.Saved]).
 * Room already re-runs a stats query when its tables change; these events additionally force a
 * recompute of exactly the flows that depend on the scopes (e.g. after an edit whose rows another
 * flow does not read, or for consumers outside Room such as the Coach snapshot).
 */
class StatsInvalidation {
    private val events = MutableSharedFlow<Set<InvalidationScope>>(extraBufferCapacity = 64)

    val scopes: SharedFlow<Set<InvalidationScope>> = events.asSharedFlow()

    /** Never suspends; empty sets are ignored. */
    fun publish(scopes: Set<InvalidationScope>) {
        if (scopes.isNotEmpty()) events.tryEmit(scopes)
    }

    /** Emits once per published event that touches any of [dependsOn]. */
    fun changes(dependsOn: Set<InvalidationScope>): Flow<Unit> = scopes.filter { event -> event.any { it in dependsOn } }.map { }
}

/** Publishes the scopes of every successful historical save to [invalidation]. */
class InvalidatingSaveWorkoutDraftUseCase(
    private val inner: SaveWorkoutDraftUseCase,
    private val invalidation: StatsInvalidation,
) : SaveWorkoutDraftUseCase {
    override suspend fun invoke(original: WorkoutAggregate, draft: WorkoutDraft): SaveResult =
        inner(original, draft).also { if (it is SaveResult.Saved) invalidation.publish(it.invalidatedScopes) }
}
