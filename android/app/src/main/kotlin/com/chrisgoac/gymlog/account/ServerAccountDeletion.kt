package com.chrisgoac.gymlog.account

import com.chrisgoac.gymlog.domain.account.AccountDeletionServer
import com.chrisgoac.gymlog.domain.account.LiveSubscription
import com.chrisgoac.gymlog.domain.account.ServerDeletionOutcome
import com.chrisgoac.gymlog.model.account.AccountWire
import com.chrisgoac.gymlog.network.AccountApi
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.CancellationException

/** [AccountDeletionServer] on `DELETE /api/v1/account`. */
class ServerAccountDeletion(private val api: AccountApi) : AccountDeletionServer {
    override suspend fun requestDeletion(userId: String, freshToken: Boolean): ServerDeletionOutcome = try {
        val answer = api.deleteAccount(userId, freshToken)
        ServerDeletionOutcome.Accepted(
            completed = answer.status == AccountWire.STATUS_DELETED,
            subscription = answer.activeSubscription?.let { LiveSubscription(it.productId, it.willRenew, it.validUntil, it.manageUrl) },
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
