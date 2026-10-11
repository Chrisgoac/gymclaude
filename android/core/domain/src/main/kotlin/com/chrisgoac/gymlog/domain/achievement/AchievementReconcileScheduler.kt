package com.chrisgoac.gymlog.domain.achievement

import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge

/**
 * Decides WHEN to reconcile. Every trigger is just a signal (a finished workout, a history edit and a
 * sync pull publish [InvalidationScope.ACHIEVEMENTS] on [StatsInvalidation]; an account becoming ready
 * is [extraSignals]); this turns bursts of them into one run:
 * - signals are debounced by [debounceMillis], so a pull of many pages or several quick edits cost one run;
 * - while a workout is being recorded ([workoutActive]) the run waits for it to end, so set logging is
 *   never competing with the whole-history aggregates (finishing publishes its own signal);
 * - runs are sequential and a failing run is reported to [onError], never stops the scheduler.
 * Reconciliation itself is idempotent and writes nothing when nothing changed, so extra signals are cheap.
 */
class AchievementReconcileScheduler(
    invalidation: StatsInvalidation,
    extraSignals: Flow<Unit>,
    private val workoutActive: Flow<Boolean>,
    private val reconcile: suspend () -> Unit,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val onError: (Throwable) -> Unit = {},
) {
    private val signals: Flow<Unit> = merge(invalidation.changes(setOf(InvalidationScope.ACHIEVEMENTS)), extraSignals)

    /** Collects forever; cancel the calling coroutine to stop. */
    @OptIn(FlowPreview::class)
    suspend fun run() {
        signals.debounce(debounceMillis).collect {
            workoutActive.first { active -> !active }
            try {
                reconcile()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 3_000L
    }
}
