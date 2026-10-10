package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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
