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

    /**
     * Bearer token of [expectedUserId]'s active session, or null when there is none: no session,
     * or the active session belongs to another account (a request for one account never carries
     * another account's token). [forceRefresh] bypasses any cached token.
     */
    suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean = false): String?

    /**
     * A v1 route answered `403 ACCOUNT_DELETING` for [userId]: the server has started deleting that account.
     * The app stops using it and removes its local data. Called from any API client, on any thread.
     */
    fun onAccountDeleting(userId: String) {}
}
