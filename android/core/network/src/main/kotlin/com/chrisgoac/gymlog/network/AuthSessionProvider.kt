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
     * What a v1 answer revealed about [userId]'s account (see [AccountSignal]). Called from every API client,
     * on any thread, after the response; implementations must return quickly.
     */
    fun onAccountSignal(userId: String, signal: AccountSignal) {}
}

/** Facts about an account learned from v1 answers, used to resolve an unconfirmed account deletion. */
enum class AccountSignal {
    /** A 2xx from an ordinary route: the account is not locked for deletion. */
    ACTIVE,

    /** `403 ACCOUNT_DELETING`: the server has started deleting the account. */
    DELETING,

    /** 401/403 even with a fresh token while the identity provider still holds the session. */
    SESSION_REJECTED,
}
