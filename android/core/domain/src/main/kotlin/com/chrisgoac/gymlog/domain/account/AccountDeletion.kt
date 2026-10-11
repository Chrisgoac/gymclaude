package com.chrisgoac.gymlog.domain.account

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
 * The device side of a deletion the server accepted: durably remembers it (so the app never returns to the
 * product, even after process death), stops the account's work, ends the session and removes every local
 * file of that account. Implemented in `:app`.
 */
fun interface LocalAccountDeletion {
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
        return when (val answer = server.requestDeletion(userId, freshToken = afterReauth)) {
            is ServerDeletionOutcome.Accepted -> {
                // Once the server holds the deletion there is no way back: finish locally even if the screen goes away.
                withContext(NonCancellable) { local.begin(userId, answer.subscription) }
                AccountDeletionOutcome.Started
            }
            ServerDeletionOutcome.ReauthRequired -> AccountDeletionOutcome.ReauthRequired
            ServerDeletionOutcome.Offline -> AccountDeletionOutcome.Offline
            ServerDeletionOutcome.SessionEnded -> AccountDeletionOutcome.SessionEnded
            is ServerDeletionOutcome.Failed -> AccountDeletionOutcome.Failed(answer.retryable)
        }
    }

    companion object {
        const val CONFIRMATION = "ELIMINAR"
    }
}

