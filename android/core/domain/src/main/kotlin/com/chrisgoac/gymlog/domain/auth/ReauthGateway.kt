package com.chrisgoac.gymlog.domain.auth

/** Outcome of one step of re-confirming the signed-in user's identity (recent sign-in for sensitive actions). */
sealed interface ReauthStep {
    /**
     * The user must prove it is them. [password]: a password can be checked; [emailCodeTo]: a masked address a
     * one-time code can be sent to. Neither: this app cannot reverify the account (e.g. Google-only sign-in
     * without an email code factor).
     */
    data class Choose(val password: Boolean, val emailCodeTo: String?) : ReauthStep

    /** A one-time code was emailed; [ReauthGateway.verifyEmailCode] finishes. */
    data object CodeSent : ReauthStep

    /** The session's first factor was verified just now. */
    data object Completed : ReauthStep

    data class Failed(val reason: AuthFailure) : ReauthStep
}

/**
 * Reverification of the CURRENT session (same account, same session), implemented in `:app` on the identity
 * provider. Never signs in another account: implementations refuse when the active session is not the signed-in
 * account's. Steps are stateful: `verify…` applies to the most recent [start].
 */
interface ReauthGateway {
    suspend fun start(): ReauthStep
    suspend fun sendEmailCode(): ReauthStep
    suspend fun verifyEmailCode(code: String): ReauthStep
    suspend fun verifyPassword(password: String): ReauthStep
}
