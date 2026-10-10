package com.chrisgoac.gymlog.domain.auth

import kotlinx.coroutines.flow.StateFlow

/** What the identity provider currently says about this device's session. */
sealed interface AuthSessionState {
    data object Loading : AuthSessionState
    data object SignedOut : AuthSessionState
    data class SignedIn(val userId: String) : AuthSessionState

    /** The account is known but its session is no longer usable; local data is kept. */
    data class SessionExpired(val userId: String) : AuthSessionState
}

/** Outcome of one authentication step. */
sealed interface AuthStep {
    /** A session is now active (the root state machine takes over from here). */
    data object Completed : AuthStep

    /** A one-time code was emailed; the matching `verify…` call finishes the flow. */
    data object CodeSent : AuthStep

    /** The user dismissed a system sheet or browser (Google); not an error. */
    data object Cancelled : AuthStep

    data class Failed(val reason: AuthFailure) : AuthStep
}

sealed interface AuthFailure {
    data object Network : AuthFailure
    data object InvalidCode : AuthFailure
    data object ExpiredCode : AuthFailure
    data object InvalidCredentials : AuthFailure
    data object AccountNotFound : AuthFailure
    data object AccountExists : AuthFailure
    data object WeakPassword : AuthFailure
    data object RateLimited : AuthFailure

    /** The account needs a step this app does not offer (second factor, provider not configured…). */
    data object Unsupported : AuthFailure

    /** [message] is the provider's own, already localized, human-readable text when it has one. */
    data class Unknown(val message: String?) : AuthFailure
}

/**
 * Native sign-in/sign-up operations. Implemented in `:app` on top of the identity provider SDK so
 * feature modules never depend on it. Rate limiting, credential storage and account recovery are
 * the provider's responsibility; implementations must not persist secrets themselves.
 *
 * Code flows are stateful: `verify…` applies to the most recent matching `start…` call.
 */
interface AuthGateway {
    val state: StateFlow<AuthSessionState>

    suspend fun startEmailCodeSignIn(email: String): AuthStep
    suspend fun verifyEmailCodeSignIn(code: String): AuthStep
    suspend fun signInWithPassword(email: String, password: String): AuthStep

    /** Creates the account and emails a verification code. [password] may be null if the instance does not require one. */
    suspend fun startSignUp(email: String, password: String?): AuthStep
    suspend fun verifySignUp(code: String): AuthStep

    suspend fun signInWithGoogle(): AuthStep

    /** Ends the provider session only. Local data handling is the caller's job. */
    suspend fun signOut(): AuthStep
}
