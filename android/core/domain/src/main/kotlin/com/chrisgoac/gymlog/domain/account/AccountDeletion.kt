package com.chrisgoac.gymlog.domain.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A Google Play subscription that is still live while the account is deleted: the server does NOT cancel it,
 * the user does so in Play ([manageUrl]). [validUntil] is server epoch ms.
 */
data class LiveSubscription(val productId: String, val willRenew: Boolean, val validUntil: Long?, val manageUrl: String)

/** What the server answered to a deletion request. */
sealed interface ServerDeletionOutcome {
    /**
     * The server holds the deletion: the account is locked from now on ([completed] false = it finishes on the
     * server by itself). Either way the account is gone for this device.
     */
    data class Accepted(val completed: Boolean, val subscription: LiveSubscription?) : ServerDeletionOutcome

    /** The sign-in is too old: reverify, then ask again with a fresh token. Nothing was locked. */
    data object ReauthRequired : ServerDeletionOutcome

    /** The request did not reach the server (or its answer was lost). Nothing changed that we know of. */
    data object Offline : ServerDeletionOutcome

    /** No usable session for the account any more. */
    data object SessionEnded : ServerDeletionOutcome

    data class Failed(val retryable: Boolean) : ServerDeletionOutcome
}

/** `DELETE /api/v1/account` for [userId]; implemented in `:app` on the network client. */
fun interface AccountDeletionServer {
    suspend fun requestDeletion(userId: String, freshToken: Boolean): ServerDeletionOutcome
}

/**
 * The device side of an account deletion. Implemented in `:app`.
 * - [intend]: durably records, BEFORE the request is sent, that a deletion may be accepted by the server
 *   (an answer can be lost). An intent alone never wipes anything; later evidence resolves it.
 * - [withdraw]: the server definitively did not accept it (nothing was locked).
 * - [begin]: the server accepted it: durably remembers it (the app never returns to the product, even after
 *   process death), stops the account's work, ends the session and removes every local file of that account.
 */
interface LocalAccountDeletion {
    suspend fun intend(userId: String)
    suspend fun withdraw(userId: String)
    suspend fun begin(userId: String, subscription: LiveSubscription?)
}

sealed interface AccountDeletionOutcome {
    /** The server accepted it; the device is wiping the account (the root leaves the product UI). */
    data object Started : AccountDeletionOutcome

    /** The typed confirmation is not exactly [AccountDeletion.CONFIRMATION]. Nothing was sent. */
    data object ConfirmationMismatch : AccountDeletionOutcome

    data object ReauthRequired : AccountDeletionOutcome
    data object Offline : AccountDeletionOutcome
    data object SessionEnded : AccountDeletionOutcome
    data class Failed(val retryable: Boolean) : AccountDeletionOutcome
}

/**
 * Deletes the signed-in account: typed confirmation → server request → (accepted) local wipe. The server
 * requires a recent sign-in; after a reverification the caller passes [afterReauth] so a fresh token is used.
 */
class AccountDeletion(
    private val currentUserId: () -> String?,
    private val server: AccountDeletionServer,
    private val local: LocalAccountDeletion,
) {
    suspend fun request(typedConfirmation: String, afterReauth: Boolean = false): AccountDeletionOutcome {
        if (typedConfirmation.trim() != CONFIRMATION) return AccountDeletionOutcome.ConfirmationMismatch
        val userId = currentUserId() ?: return AccountDeletionOutcome.SessionEnded
        // Recorded before sending: if the answer is lost, the device still knows the server may hold a deletion.
        try {
            local.intend(userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AccountDeletionOutcome.Failed(retryable = true) // nothing was sent
        }
        val answer = server.requestDeletion(userId, freshToken = afterReauth)
        return withContext(NonCancellable) {
            when (answer) {
                is ServerDeletionOutcome.Accepted -> {
                    // Once the server holds the deletion there is no way back: finish locally even if the screen goes away.
                    local.begin(userId, answer.subscription)
                    AccountDeletionOutcome.Started
                }
                // Definitive refusals: nothing was locked on the server.
                ServerDeletionOutcome.ReauthRequired -> AccountDeletionOutcome.ReauthRequired.also { withdrawQuietly(userId) }
                ServerDeletionOutcome.SessionEnded -> AccountDeletionOutcome.SessionEnded.also { withdrawQuietly(userId) }
                is ServerDeletionOutcome.Failed -> {
                    if (!answer.retryable) withdrawQuietly(userId) // a retryable failure may have locked it: keep the intent
                    AccountDeletionOutcome.Failed(answer.retryable)
                }
                // Ambiguous (the request may have arrived): the intent stays until evidence resolves it.
                ServerDeletionOutcome.Offline -> AccountDeletionOutcome.Offline
            }
        }
    }

    /** A leftover intent is harmless (later evidence clears it), so a failure to clear it is not reported. */
    private suspend fun withdrawQuietly(userId: String) {
        try {
            local.withdraw(userId)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CONFIRMATION = "ELIMINAR"
    }
}
