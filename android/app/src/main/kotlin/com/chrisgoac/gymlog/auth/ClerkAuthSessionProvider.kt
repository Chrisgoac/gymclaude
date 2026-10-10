package com.chrisgoac.gymlog.auth

import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.GetTokenOptions
import com.clerk.api.session.Session
import com.clerk.api.session.fetchToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * [AuthSessionProvider] backed by the Clerk SDK, which owns session storage and refresh. Only an
 * ACTIVE session counts as signed in; a Clerk user whose session is pending stays signed out.
 * Requires `Clerk.initialize` to have been called.
 */
class ClerkAuthSessionProvider(scope: CoroutineScope) : AuthSessionProvider {
    private val reducer = ClerkAuthStateReducer()

    override val state: StateFlow<AuthState> =
        combine(Clerk.isInitialized, Clerk.initializationError, Clerk.sessionFlow) { initialized, error, session ->
            reducer.reduce(
                ClerkSnapshot(
                    initialized = initialized,
                    initializationFailed = error != null,
                    userId = session?.user?.id ?: session?.publicUserData?.userId,
                    phase = session?.status.toPhase(),
                ),
            )
        }.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    /**
     * A Clerk session JWT, only while [expectedUserId] is the signed-in user and owns the active
     * session, both before and after the fetch (an account switch during the fetch returns null).
     * [forceRefresh] skips Clerk's token cache (`GetTokenOptions.skipCache`).
     */
    override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? {
        if (!isActiveFor(expectedUserId)) return null
        val session = Clerk.activeSession ?: return null
        if (session.ownerId() != expectedUserId) return null
        val jwt = when (val result = session.fetchToken(GetTokenOptions(skipCache = forceRefresh))) {
            is ClerkResult.Success -> result.value.jwt
            is ClerkResult.Failure -> null
        }
        return jwt?.takeIf { isActiveFor(expectedUserId) && Clerk.activeSession?.ownerId() == expectedUserId }
    }

    private fun isActiveFor(userId: String) = state.value == AuthState.SignedIn(userId)

    private fun Session.ownerId(): String? = user?.id ?: publicUserData?.userId

    /** Call right before a deliberate sign-out so the session disappearing is not read as expiry. */
    internal fun signOutRequested() = reducer.signOutRequested()

    internal fun signOutAbandoned() = reducer.signOutAbandoned()
}

internal enum class SessionPhase { None, Pending, Active, Ended }

internal fun Session.SessionStatus?.toPhase(): SessionPhase = when (this) {
    null -> SessionPhase.None
    Session.SessionStatus.ACTIVE -> SessionPhase.Active
    Session.SessionStatus.PENDING, Session.SessionStatus.UNKNOWN -> SessionPhase.Pending
    Session.SessionStatus.ABANDONED, Session.SessionStatus.ENDED, Session.SessionStatus.EXPIRED,
    Session.SessionStatus.REMOVED, Session.SessionStatus.REPLACED, Session.SessionStatus.REVOKED,
    -> SessionPhase.Ended
}

internal data class ClerkSnapshot(
    val initialized: Boolean,
    val initializationFailed: Boolean,
    val userId: String?,
    val phase: SessionPhase,
)

/**
 * Pure mapping from Clerk's state to [AuthState]. It remembers the last signed-in user so that a
 * session vanishing without a deliberate sign-out (server-side expiry/revocation, which Clerk
 * reports by dropping the session) becomes [AuthState.SessionExpired] and local data is kept.
 */
internal class ClerkAuthStateReducer {
    private var lastUserId: String? = null
    private var signingOut = false

    @Synchronized
    fun reduce(snapshot: ClerkSnapshot): AuthState {
        if (!snapshot.initialized) {
            // First launch offline: Clerk has nothing cached, so there is nobody to sign in as.
            return if (snapshot.initializationFailed) AuthState.SignedOut else AuthState.Loading
        }
        val userId = snapshot.userId
        return when (snapshot.phase) {
            SessionPhase.Active ->
                if (userId == null) AuthState.SignedOut
                else {
                    lastUserId = userId
                    signingOut = false
                    AuthState.SignedIn(userId)
                }
            SessionPhase.Pending -> AuthState.SignedOut
            // A deliberate sign-out stays a sign-out while the session winds down (ended, then gone).
            SessionPhase.Ended -> if (signingOut) AuthState.SignedOut else expiredOrSignedOut(userId ?: lastUserId)
            SessionPhase.None ->
                if (signingOut) {
                    // The sign-out has finished: forget the account so later empty states aren't "expired".
                    signingOut = false
                    lastUserId = null
                    AuthState.SignedOut
                } else {
                    expiredOrSignedOut(lastUserId)
                }
        }
    }

    private fun expiredOrSignedOut(userId: String?): AuthState =
        if (userId == null) AuthState.SignedOut else AuthState.SessionExpired(userId)

    @Synchronized fun signOutRequested() { signingOut = true }

    @Synchronized fun signOutAbandoned() { signingOut = false }
}
