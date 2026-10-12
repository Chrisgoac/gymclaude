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
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides WHEN to reconcile. Every trigger is just a signal (a finished workout, a history edit and a
 * sync pull publish [InvalidationScope.ACHIEVEMENTS] on [StatsInvalidation]; an account becoming ready
 * is [extraSignals]). Only a LOCAL history edit or workout delete (published with `localEdit`) lets the run
 * tombstone; all other triggers only unlock/retain; this turns bursts of them into one run:
 * - signals are debounced by [debounceMillis], so a pull of many pages or several quick edits cost one run;
 * - the run waits until [canRun] (an account is Ready and no workout is being recorded), so a partially
 *   bootstrapped database is never judged and set logging never competes with the whole-history aggregates;
 * - runs are sequential and a failing run is reported to [onError], never stops the scheduler;
 * - the "may tombstone" flag belongs to the account that made the edit ([currentAccount] when it was
 *   published) and is honoured only by a run for that same account; a run that throws puts it back,
 *   so the next trigger still may tombstone.
 * Reconciliation itself is idempotent and writes nothing when nothing changed, so extra signals are cheap.
 */
class AchievementReconcileScheduler(
    invalidation: StatsInvalidation,
    extraSignals: Flow<Unit>,
    private val canRun: Flow<Boolean>,
    /** Hash of the account open right now (null = none); scopes the revoke flag. */
    private val currentAccount: () -> String?,
    private val reconcile: suspend (allowTombstone: Boolean) -> Unit,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
    private val onError: (Throwable) -> Unit = {},
) {
    /** The account whose local edit/delete may tombstone in the next run; null = none pending. */
    private val revokeFor = AtomicReference<String?>(null)

    private val signals: Flow<Unit> = merge(
        invalidation.changes(setOf(InvalidationScope.ACHIEVEMENTS)),
        invalidation.localEditChanges(setOf(InvalidationScope.ACHIEVEMENTS)).onEach { currentAccount()?.let(revokeFor::set) },
        extraSignals,
    )

    /** Collects forever; cancel the calling coroutine to stop. */
    @OptIn(FlowPreview::class)
    suspend fun run() {
        signals.debounce(debounceMillis).collect {
            canRun.first { it }
            // Only a local edit/delete seen in this window, by THIS account, may tombstone; everything else
            // is unlock/retain. A flag left by another account is dropped: its database is not the one open.
            val account = currentAccount()
            val pending = revokeFor.getAndSet(null)
            val allowTombstone = pending != null && pending == account
            try {
                reconcile(allowTombstone)
            } catch (cancelled: CancellationException) {
                if (allowTombstone) revokeFor.compareAndSet(null, pending)
                throw cancelled
            } catch (e: Exception) {
                if (allowTombstone) revokeFor.compareAndSet(null, pending)
                onError(e)
            }
        }
    }

    companion object {
        const val DEBOUNCE_MILLIS = 3_000L
    }
}
