package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns app events into sync requests (spec 10.5): the account becoming [RootState.Ready]
 * (login/bootstrap done, or app start), the app returning to the foreground, and local mutations
 * (debounced). Connectivity and the 15 minute period are carried by the WorkManager constraints.
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
                    scheduler.requestSync(userId)
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
        scope.launch { scheduler.requestSync(userId) }
    }

    private fun readyUser(): String? = (rootState.value as? RootState.Ready)?.userId

    companion object {
        const val MUTATION_DEBOUNCE_MILLIS = 2_000L
    }
}

/** Tells [onCommitted] after every local mutation that committed; failures propagate untouched. */
class NotifyingLocalMutationRunner(
    private val delegate: LocalMutationRunner,
    private val onCommitted: () -> Unit,
) : LocalMutationRunner {
    override suspend fun <T> run(mutation: LocalMutation<T>): T = delegate.run(mutation).also { onCommitted() }
}

/**
 * When bootstrap failed transiently and connectivity comes back, retries it once per
 * offline-to-online transition; otherwise the user presses "Reintentar".
 */
class BootstrapAutoRetry(
    private val rootState: Flow<RootState>,
    private val online: Flow<Boolean>,
    private val retry: () -> Unit,
) {
    suspend fun run() {
        var previouslyOnline: Boolean? = null
        combine(rootState, online) { state, isOnline -> state to isOnline }.collect { (state, isOnline) ->
            val cameBack = isOnline && previouslyOnline == false
            previouslyOnline = isOnline
            if (cameBack && state is RootState.Fatal && state.canRetry) retry()
        }
    }
}
