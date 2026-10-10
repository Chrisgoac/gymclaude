package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns app events into sync requests (spec 10.5): the account becoming [RootState.Ready]
 * (login/bootstrap done, or app start), the app returning to the foreground, and local mutations
 * (debounced). Connectivity and the 15 minute period are carried by the WorkManager constraints.
 * Ready and foreground are "soft" requests: a run already in progress satisfies them, so a process
 * woken for a background run does not sync twice. Local mutations queue behind a running run.
 */
class SyncTriggers(
    private val scope: CoroutineScope,
    private val rootState: StateFlow<RootState>,
    private val scheduler: BackgroundSyncScheduler,
    private val debounceMillis: Long = MUTATION_DEBOUNCE_MILLIS,
) {
    private val mutations = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            rootState.map { (it as? RootState.Ready)?.userId }.distinctUntilChanged().collect { userId ->
                if (userId != null) {
                    scheduler.ensurePeriodic(userId)
                    scheduler.requestSync(userId, queueBehindRunning = false)
                }
            }
        }
        scope.launch {
            mutations.debounce(debounceMillis).collect { readyUser()?.let { scheduler.requestSync(it) } }
        }
    }

    /** A local mutation was committed (its outbox row exists). */
    fun onLocalMutation() {
        mutations.tryEmit(Unit)
    }

    fun onForeground() {
        val userId = readyUser() ?: return
        scope.launch { scheduler.requestSync(userId, queueBehindRunning = false) }
    }

    private fun readyUser(): String? = (rootState.value as? RootState.Ready)?.userId

    companion object {
        const val MUTATION_DEBOUNCE_MILLIS = 2_000L
    }
}
