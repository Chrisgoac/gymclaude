package com.chrisgoac.gymlog.domain.achievement

import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decides WHEN to reconcile. Every trigger is just a signal (a finished workout, a history edit and a
 * sync pull publish [InvalidationScope.ACHIEVEMENTS] on [StatsInvalidation]; an account becoming ready
 * is [extraSignals]). Only a LOCAL history edit or workout delete (published with `localEdit`) lets the run
 * tombstone; all other triggers only unlock/retain; this turns bursts of them into one run:
 * - signals are debounced by [debounceMillis], so a pull of many pages or several quick edits cost one run;
 * - the run waits until [canRun] (an account is Ready and no workout is being recorded), so a partially
 *   bootstrapped database is never judged and set logging never competes with the whole-history aggregates;
 * - runs are sequential and a failing run is reported to [onError], never stops the scheduler.
 * Reconciliation itself is idempotent and writes nothing when nothing changed, so extra signals are cheap.
 */
class AchievementReconcileScheduler(
    invalidation: StatsInvalidation,
    extraSignals: Flow<Unit>,
    private val canRun: Flow<Boolean>,
    private val reconcile: suspend (allowTombstone: Boolean) -> Unit,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val onError: (Throwable) -> Unit = {},
) {
    private val revoke = AtomicBoolean(false)

    private val signals: Flow<Unit> = merge(
        invalidation.changes(setOf(InvalidationScope.ACHIEVEMENTS)),
        invalidation.localEditChanges(setOf(InvalidationScope.ACHIEVEMENTS)).onEach { revoke.set(true) },
        extraSignals,
    )

    /** Collects forever; cancel the calling coroutine to stop. */
    @OptIn(FlowPreview::class)
    suspend fun run() {
        signals.debounce(debounceMillis).collect {
            canRun.first { it }
            // Only a local edit/delete seen in this window may tombstone; everything else is unlock/retain.
            val allowTombstone = revoke.getAndSet(false)
            try {
                reconcile(allowTombstone)
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
