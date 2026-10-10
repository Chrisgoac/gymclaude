package com.chrisgoac.gymlog.auth

import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import com.clerk.api.Clerk
import com.clerk.api.network.serialization.ClerkResult
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

    /** A Clerk session JWT, only while the active session belongs to the signed-in user. */
    override suspend fun accessToken(): String? {
        val signedIn = state.value as? AuthState.SignedIn ?: return null
        val session = Clerk.activeSession ?: return null
        if ((session.user?.id ?: session.publicUserData?.userId) != signedIn.userId) return null
        return when (val result = session.fetchToken()) {
            is ClerkResult.Success -> result.value.jwt
            is ClerkResult.Failure -> null
        }
    }

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
            SessionPhase.Ended -> expiredOrSignedOut(userId ?: lastUserId)
            SessionPhase.None -> expiredOrSignedOut(lastUserId)
        }
    }

    private fun expiredOrSignedOut(userId: String?): AuthState =
        if (signingOut || userId == null) {
            lastUserId = null
            signingOut = false
            AuthState.SignedOut
        } else {
            AuthState.SessionExpired(userId)
        }

    @Synchronized fun signOutRequested() { signingOut = true }

    @Synchronized fun signOutAbandoned() { signingOut = false }
}
