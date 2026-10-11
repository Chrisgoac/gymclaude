package com.chrisgoac.gymlog.domain.stats

import com.chrisgoac.gymlog.domain.SyncRepository
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.SaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
 * The Room-backed [StatsRepository] flows do not need it (Room invalidation already re-runs them,
 * once per edit); it is for derived data kept outside those queries, such as the Coach snapshot and
 * achievement reconciliation, which subscribe to [changes] for the scopes they depend on.
 */
class StatsInvalidation {
    private class Event(val scopes: Set<InvalidationScope>, val localEdit: Boolean)

    private val events = MutableSharedFlow<Event>(extraBufferCapacity = 64)

    val scopes: Flow<Set<InvalidationScope>> = events.map { it.scopes }

    /**
     * Never suspends; empty sets are ignored. [localEdit] marks a change the user made on this device to
     * existing history (a saved edit, a deleted workout): the only kind of event allowed to revoke achievements.
     */
    fun publish(scopes: Set<InvalidationScope>, localEdit: Boolean = false) {
        if (scopes.isNotEmpty()) events.tryEmit(Event(scopes, localEdit))
    }

    /** Like [changes] but only for local edits/deletes (see [publish]). */
    fun localEditChanges(dependsOn: Set<InvalidationScope>): Flow<Unit> =
        events.filter { it.localEdit && it.scopes.any { s -> s in dependsOn } }.map { }

    /** Emits once per published event that touches any of [dependsOn]. */
    fun changes(dependsOn: Set<InvalidationScope>): Flow<Unit> = events.filter { event -> event.scopes.any { it in dependsOn } }.map { }
}

/** Publishes the scopes of every successful historical save to [invalidation]. */
class InvalidatingSaveWorkoutDraftUseCase(
    private val inner: SaveWorkoutDraftUseCase,
    private val invalidation: StatsInvalidation,
) : SaveWorkoutDraftUseCase {
    override suspend fun invoke(original: WorkoutAggregate, draft: WorkoutDraft): SaveResult =
        inner(original, draft).also { if (it is SaveResult.Saved) invalidation.publish(it.invalidatedScopes, localEdit = true) }
}

/**
 * Publishes every [InvalidationScope] after a sync run that pulled rows: a pull can change any
 * workout, setting or achievement, so derived data outside the Room stats queries (achievement
 * reconciliation, the Coach snapshot) must re-check. Only a fully applied run counts (Success), never a
 * retryable or failed one, and a run that pulled nothing publishes nothing.
 */
class InvalidatingSyncRepository(
    private val inner: SyncRepository,
    private val invalidation: StatsInvalidation,
) : SyncRepository by inner {
    override suspend fun syncOnce(userId: String): SyncResult =
        inner.syncOnce(userId).also { result ->
            if (result is SyncResult.Success && result.pulled > 0) invalidation.publish(InvalidationScope.entries.toSet())
        }
}

/** Publishes a LOCAL edit event (every scope) after a workout is deleted on this device: the delete path of derived-data invalidation. */
class InvalidatingHistoryRepository(
    private val inner: HistoryRepository,
    private val invalidation: StatsInvalidation,
) : HistoryRepository by inner {
    override suspend fun deleteSession(sessionId: String) {
        inner.deleteSession(sessionId)
        invalidation.publish(InvalidationScope.entries.toSet(), localEdit = true)
    }
}

/**
 * Publishes [InvalidationScope.ACHIEVEMENTS] after the weekly target changes: it is achievement
 * evidence (weekly-streak achievements count weeks that met it), and reconciliation listens to
 * invalidation events, not to the settings table. NOT a local edit of history: reconciliation may
 * unlock but never revokes because of a settings change. The other settings feed no achievement.
 */
class InvalidatingSettingsRepository(
    private val inner: SettingsRepository,
    private val invalidation: StatsInvalidation,
) : SettingsRepository by inner {
    override suspend fun setWeeklyTarget(sessionsPerWeek: Int) {
        inner.setWeeklyTarget(sessionsPerWeek)
        invalidation.publish(setOf(InvalidationScope.ACHIEVEMENTS), localEdit = false)
    }
}
