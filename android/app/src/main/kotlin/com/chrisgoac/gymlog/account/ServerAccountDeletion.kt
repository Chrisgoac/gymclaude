package com.chrisgoac.gymlog.account

import com.chrisgoac.gymlog.domain.account.AccountDeletionServer
import com.chrisgoac.gymlog.domain.account.LiveSubscription
import com.chrisgoac.gymlog.domain.account.ServerDeletionOutcome
import com.chrisgoac.gymlog.model.account.AccountWire
import com.chrisgoac.gymlog.network.AccountApi
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.CancellationException

/**
 * [AccountDeletionServer] on `DELETE /api/v1/account`. The server's `manageUrl` is used only when it is Play's
 * subscription page; anything else is replaced by [fallbackManageUrl] (the app's own Play link).
 */
class ServerAccountDeletion(
    private val api: AccountApi,
    private val fallbackManageUrl: () -> String,
) : AccountDeletionServer {
    override suspend fun requestDeletion(userId: String, freshToken: Boolean): ServerDeletionOutcome = try {
        val answer = api.deleteAccount(userId, freshToken)
        ServerDeletionOutcome.Accepted(
            completed = answer.status == AccountWire.STATUS_DELETED,
            subscription = answer.activeSubscription?.let {
                LiveSubscription(it.productId, it.willRenew, it.validUntil, it.manageUrl.takeIf(::isPlaySubscriptionsUrl) ?: fallbackManageUrl())
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: SyncApiException.Api) {
        when (e.rawCode) {
            AccountWire.REAUTH_REQUIRED -> ServerDeletionOutcome.ReauthRequired
            // Only other routes answer this, but if it ever comes back here the account is already locked.
            AccountWire.ACCOUNT_DELETING -> ServerDeletionOutcome.Accepted(completed = false, subscription = null)
            else -> ServerDeletionOutcome.Failed(e.retryable)
        }
    } catch (_: SyncApiException.Transport) {
        ServerDeletionOutcome.Offline
    } catch (_: SyncApiException.SessionRequired) {
        ServerDeletionOutcome.SessionEnded
    } catch (_: SyncApiException.SessionRejected) {
        ServerDeletionOutcome.Failed(retryable = true)
    } catch (_: SyncApiException.Malformed) {
        // A 2xx whose body could not be read: the server did accept it (the account is locked from now on).
        ServerDeletionOutcome.Accepted(completed = false, subscription = null)
    }
}

private const val PLAY_SUBSCRIPTIONS = "https://play.google.com/store/account/subscriptions"

/** Exactly Play's subscription page (optionally with a query string), nothing else. */
internal fun isPlaySubscriptionsUrl(url: String): Boolean = url == PLAY_SUBSCRIPTIONS || url.startsWith("$PLAY_SUBSCRIPTIONS?")
