package com.chrisgoac.gymlog.auth

import android.util.Log
import com.chrisgoac.gymlog.BuildConfig
import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import com.chrisgoac.gymlog.network.AuthState
import com.clerk.api.Clerk
import com.clerk.api.auth.types.VerificationType
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signin.SignIn
import com.clerk.api.signin.verifyCode
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.prepareVerification
import com.clerk.api.signup.verifyCode
import com.clerk.api.sso.OAuthProvider
import com.clerk.api.sso.OAuthResult
import com.clerk.api.sso.SSOCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/**
 * [AuthGateway] on the Clerk Android SDK. Clerk stores the session/device tokens and enforces rate
 * limits, bot protection settings and recovery; this class only drives its native custom-flow API.
 * The in-progress sign-in/sign-up lives in memory until verified (an app restart starts over).
 */
class ClerkAuthGateway(
    private val session: ClerkAuthSessionProvider,
    scope: CoroutineScope,
) : AuthGateway {
    override val state: StateFlow<AuthSessionState> = session.state
        .map { it.toSessionState() }
        .stateIn(scope, SharingStarted.Eagerly, session.state.value.toSessionState())

    override val serviceUnavailable: StateFlow<Boolean> =
        combine(Clerk.isInitialized, Clerk.initializationError) { initialized, error -> !initialized && error != null }
            .stateIn(scope, SharingStarted.Eagerly, false)

    private var pendingSignIn: SignIn? = null
    private var pendingSignUp: SignUp? = null

    override suspend fun startEmailCodeSignIn(email: String): AuthStep = guarded {
        when (val result = Clerk.auth.signInWithOtp { this.email = email }) {
            is ClerkResult.Success -> {
                pendingSignIn = result.value
                AuthStep.CodeSent
            }
            is ClerkResult.Failure -> result.toStep()
        }
    }

    override suspend fun verifyEmailCodeSignIn(code: String): AuthStep = guarded {
        val signIn = pendingSignIn ?: return@guarded AuthStep.Failed(AuthFailure.ExpiredCode)
        when (val result = signIn.verifyCode(code)) {
            is ClerkResult.Success -> result.value.toStep().also { if (it == AuthStep.Completed) pendingSignIn = null }
            is ClerkResult.Failure -> result.toStep()
        }
    }

    override suspend fun signInWithPassword(email: String, password: String): AuthStep = guarded {
        val result = Clerk.auth.signInWithPassword {
            identifier = email
            this.password = password
        }
        when (result) {
            is ClerkResult.Success -> result.value.toStep()
            is ClerkResult.Failure -> result.toStep()
        }
    }

    override suspend fun startSignUp(email: String, password: String?): AuthStep = guarded {
        val created = Clerk.auth.signUp {
            this.email = email
            this.password = password
        }
        val signUp = when (created) {
            is ClerkResult.Success -> created.value
            is ClerkResult.Failure -> return@guarded created.toStep()
        }
        if (signUp.status == SignUp.Status.COMPLETE) return@guarded AuthStep.Completed
        // Always the 6-digit code: `sendCode` would switch to a magic link when the instance allows it.
        when (val prepared = signUp.prepareVerification(SignUp.PrepareVerificationParams.Strategy.EmailCode())) {
            is ClerkResult.Success -> {
                pendingSignUp = prepared.value
                AuthStep.CodeSent
            }
            is ClerkResult.Failure -> prepared.toStep()
        }
    }

    override suspend fun verifySignUp(code: String): AuthStep = guarded {
        val signUp = pendingSignUp ?: return@guarded AuthStep.Failed(AuthFailure.ExpiredCode)
        when (val result = signUp.verifyCode(code, VerificationType.EMAIL)) {
            is ClerkResult.Success ->
                if (result.value.status == SignUp.Status.COMPLETE) {
                    pendingSignUp = null
                    AuthStep.Completed
                } else {
                    // e.g. the instance requires a username/name this app does not collect yet.
                    AuthStep.Failed(AuthFailure.Unsupported)
                }
            is ClerkResult.Failure -> result.toStep()
        }
    }

    override suspend fun resendSignUpCode(): AuthStep = guarded {
        val signUp = pendingSignUp ?: return@guarded AuthStep.Failed(AuthFailure.ExpiredCode)
        when (val prepared = signUp.prepareVerification(SignUp.PrepareVerificationParams.Strategy.EmailCode())) {
            is ClerkResult.Success -> {
                pendingSignUp = prepared.value
                AuthStep.CodeSent
            }
            is ClerkResult.Failure -> prepared.toStep()
        }
    }

    /**
     * Native Google (Credential Manager) when the instance has a Google One Tap client id; otherwise
     * Clerk's OAuth redirect through a Custom Tab, returning via the SDK's `clerk://<appId>.callback`.
     */
    override suspend fun signInWithGoogle(): AuthStep = guarded {
        val result = if (Clerk.isGoogleOneTapEnabled) {
            Clerk.auth.signUpWithGoogleOneTap() // Resolves to sign-in for existing accounts.
        } else {
            Clerk.auth.signInWithOAuth(OAuthProvider.GOOGLE)
        }
        when (result) {
            is ClerkResult.Success -> result.value.toStep()
            is ClerkResult.Failure -> result.toStep()
        }
    }

    override suspend fun signOut(): AuthStep {
        session.signOutRequested()
        return try {
            val result = Clerk.auth.signOut()
            // Clerk clears its local credentials even when the server call fails: what matters here
            // is that this device no longer has a session.
            if (result is ClerkResult.Success || Clerk.session == null) {
                AuthStep.Completed
            } else {
                session.signOutAbandoned()
                (result as ClerkResult.Failure).toStep()
            }
        } catch (cancelled: CancellationException) {
            session.signOutAbandoned()
            throw cancelled
        } catch (_: Exception) {
            if (Clerk.session == null) {
                AuthStep.Completed
            } else {
                session.signOutAbandoned()
                AuthStep.Failed(AuthFailure.Unknown(null))
            }
        }
    }

    /** Clerk reports almost everything as results, but never let an SDK exception crash the UI. */
    private inline fun guarded(block: () -> AuthStep): AuthStep = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        AuthStep.Failed(AuthFailure.Network)
    } catch (_: Exception) {
        AuthStep.Failed(AuthFailure.Unknown(null))
    }
}

private fun AuthState.toSessionState(): AuthSessionState = when (this) {
    AuthState.Loading -> AuthSessionState.Loading
    AuthState.SignedOut -> AuthSessionState.SignedOut
    is AuthState.SignedIn -> AuthSessionState.SignedIn(userId)
    is AuthState.SessionExpired -> AuthSessionState.SessionExpired(userId)
}

private fun SignIn.toStep(): AuthStep = when (status) {
    SignIn.Status.COMPLETE -> AuthStep.Completed
    // Second factor / forced password reset: not offered natively yet.
    else -> AuthStep.Failed(AuthFailure.Unsupported)
}

private fun OAuthResult.toStep(): AuthStep = when {
    signIn?.status == SignIn.Status.COMPLETE || signUp?.status == SignUp.Status.COMPLETE -> AuthStep.Completed
    else -> AuthStep.Failed(AuthFailure.Unsupported)
}

private fun ClerkResult.Failure<ClerkErrorResponse>.toStep(): AuthStep {
    val throwable = this.throwable
    if (throwable is SSOCancellationException || throwable.isCredentialCancellation()) return AuthStep.Cancelled
    val first = error?.errors?.firstOrNull()
    // Clerk's text is English-only, so the UI shows its own copy; keep the provider detail for debugging.
    if (BuildConfig.DEBUG) Log.d(TAG, "Clerk failure code=${first?.code} http=$code: ${first?.longMessage ?: first?.message ?: throwable}")
    return AuthStep.Failed(clerkFailure(code = first?.code, httpStatus = code, isNetwork = throwable is IOException))
}

/** Credential Manager cancellation, which Clerk wraps in an internal `CredentialFlowException.UserCancelled`. */
private fun Throwable?.isCredentialCancellation(): Boolean =
    this != null && javaClass.name.endsWith("CredentialFlowException\$UserCancelled")

/** Maps Clerk FAPI error codes (https://clerk.com/docs/errors/overview) to what the UI explains. */
internal fun clerkFailure(code: String?, httpStatus: Int?, isNetwork: Boolean): AuthFailure = when {
    isNetwork -> AuthFailure.Network
    httpStatus == 429 || code == "too_many_requests" -> AuthFailure.RateLimited
    code == "form_code_incorrect" -> AuthFailure.InvalidCode
    code == "verification_expired" || code == "verification_failed" -> AuthFailure.ExpiredCode
    code == "form_password_incorrect" -> AuthFailure.InvalidCredentials
    code == "form_identifier_not_found" -> AuthFailure.AccountNotFound
    code == "form_identifier_exists" -> AuthFailure.AccountExists
    code != null && code.startsWith("form_password_") -> AuthFailure.WeakPassword
    code == "strategy_for_user_invalid" -> AuthFailure.Unsupported
    else -> AuthFailure.Unknown(code)
}

private const val TAG = "GymLogAuth"
