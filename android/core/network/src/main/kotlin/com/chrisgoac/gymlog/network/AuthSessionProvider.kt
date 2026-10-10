package com.chrisgoac.gymlog.network

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String) : AuthState

    /** The account is known but the session can no longer be used; local data stays intact. */
    data class SessionExpired(val userId: String) : AuthState
}

interface AuthSessionProvider {
    val state: StateFlow<AuthState>

    /** Current bearer token, or null when there is no usable session. */
    suspend fun accessToken(): String?
}
