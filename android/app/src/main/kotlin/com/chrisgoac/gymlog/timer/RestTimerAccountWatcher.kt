package com.chrisgoac.gymlog.timer

import com.chrisgoac.gymlog.RootState
import kotlinx.coroutines.flow.Flow

/**
 * Detaches the rest timer when the account changes: on sign-out and when a different user becomes
 * Ready. Transient states of the same user (bootstrapping, re-authentication) do nothing. Storage
 * is untouched: each account's run stays in its own file and is restored when its workout opens.
 */
class RestTimerAccountWatcher(private val states: Flow<RootState>, private val detach: suspend () -> Unit) {
    suspend fun run() {
        var last: String? = null
        states.collect { state ->
            when (state) {
                RootState.SignedOut -> {
                    if (last != null) detach()
                    last = null
                }
                is RootState.Ready -> {
                    if (last != null && last != state.userId) detach()
                    last = state.userId
                }
                else -> Unit
            }
        }
    }
}
