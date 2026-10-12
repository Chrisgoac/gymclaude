package com.chrisgoac.gymlog.auth

import android.util.Log
import com.chrisgoac.gymlog.BuildConfig
import com.chrisgoac.gymlog.domain.auth.AuthFailure
import com.chrisgoac.gymlog.domain.auth.ReauthGateway
import com.chrisgoac.gymlog.domain.auth.ReauthStep
import com.clerk.api.Clerk
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.session.Session
import com.clerk.api.session.SessionVerification
import com.clerk.api.session.sendEmailCode
import com.clerk.api.session.startVerification
import com.clerk.api.session.verifyWithEmailCode
import com.clerk.api.session.verifyWithPassword
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * [ReauthGateway] on Clerk's session reverification (`Session.startVerification(FIRST_FACTOR)` and its
 * first-factor attempts): the SAME session of the SAME user proves a fresh first factor, which Clerk then
 * reports in new session tokens (`fva`). It never signs in or switches accounts: every step re-checks that the
 * active session still belongs to [currentUserId]. A second factor is not offered natively ([AuthFailure.Unsupported]).
 */
class ClerkReauthGateway(private val currentUserId: () -> String?) : ReauthGateway {
    private var emailAddressId: String? = null

    override suspend fun start(): ReauthStep = guarded { session ->
        emailAddressId = null
        session.startVerification(SessionVerification.Level.FIRST_FACTOR).toStep { verification ->
            when (verification.status) {
                SessionVerification.Status.COMPLETE -> ReauthStep.Completed
                SessionVerification.Status.NEEDS_FIRST_FACTOR -> {
                    val factors = verification.supportedFirstFactors.orEmpty()
                    val email = factors.firstOrNull { it.strategy == STRATEGY_EMAIL_CODE && it.emailAddressId != null }
                    emailAddressId = email?.emailAddressId
                    ReauthStep.Choose(
                        password = factors.any { it.strategy == STRATEGY_PASSWORD },
                        emailCodeTo = email?.let { it.safeIdentifier ?: EMAIL_FALLBACK },
                    )
                }
                else -> ReauthStep.Failed(AuthFailure.Unsupported)
            }
        }
    }

    override suspend fun sendEmailCode(): ReauthStep = guarded { session ->
        val id = emailAddressId ?: return@guarded ReauthStep.Failed(AuthFailure.ExpiredCode)
        session.sendEmailCode(id).toStep { ReauthStep.CodeSent }
    }

    override suspend fun verifyEmailCode(code: String): ReauthStep = guarded { session ->
        session.verifyWithEmailCode(code).toStep(::completion)
    }

    override suspend fun verifyPassword(password: String): ReauthStep = guarded { session ->
        session.verifyWithPassword(password).toStep(::completion)
    }

    private fun completion(verification: SessionVerification): ReauthStep = when (verification.status) {
        SessionVerification.Status.COMPLETE -> ReauthStep.Completed
        // The instance also asks for a second factor: not offered natively.
        else -> ReauthStep.Failed(AuthFailure.Unsupported)
    }

    /** The active session must still be the signed-in account's; SDK exceptions never reach the UI. */
    private inline fun guarded(block: (Session) -> ReauthStep): ReauthStep = try {
        val session = Clerk.activeSession
        val owner = session?.user?.id ?: session?.publicUserData?.userId
        if (session == null || owner == null || owner != currentUserId()) {
            ReauthStep.Failed(AuthFailure.Unsupported)
        } else {
            block(session)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        ReauthStep.Failed(AuthFailure.Network)
    } catch (_: Exception) {
        ReauthStep.Failed(AuthFailure.Unknown(null))
    }

    private inline fun ClerkResult<SessionVerification, ClerkErrorResponse>.toStep(onSuccess: (SessionVerification) -> ReauthStep): ReauthStep =
        when (this) {
            is ClerkResult.Success -> onSuccess(value)
            is ClerkResult.Failure -> {
                val first = error?.errors?.firstOrNull()
                if (BuildConfig.DEBUG) Log.d(TAG, "Clerk reverification failure code=${first?.code} http=$code")
                ReauthStep.Failed(clerkFailure(code = first?.code, httpStatus = code, isNetwork = throwable is IOException))
            }
        }

    private companion object {
        const val TAG = "GymLogReauth"
        const val STRATEGY_PASSWORD = "password"
        const val STRATEGY_EMAIL_CODE = "email_code"

        /** Shown when Clerk gives no masked address for the email factor. */
        const val EMAIL_FALLBACK = "tu correo"
    }
}
