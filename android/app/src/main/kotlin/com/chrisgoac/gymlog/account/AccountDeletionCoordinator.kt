package com.chrisgoac.gymlog.account

import com.chrisgoac.gymlog.domain.account.LiveSubscription
import com.chrisgoac.gymlog.domain.account.LocalAccountDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the device stands with an account deletion; anything but [None] keeps the product UI away. */
sealed interface AccountDeletionStatus {
    data object None : AccountDeletionStatus

    /** The server holds the deletion; the account's local data is being removed. */
    data object Running : AccountDeletionStatus

    /** The local wipe (or the sign-out before it) failed; the mark stays and [AccountDeletionCoordinator.retry] resumes. */
    data object Failed : AccountDeletionStatus

    /** Everything local is gone. [subscription]: a Play subscription the user still has to cancel in Play. */
    data class Done(val subscription: LiveSubscription?) : AccountDeletionStatus
}

/** Durable, hash-only record of accounts whose deletion the server accepted and whose local data is not gone yet. */
interface AccountDeletionMarks {
    suspend fun pending(): Set<String>
    suspend fun add(hash: String)
    suspend fun remove(hash: String)
}

/**
 * The device side of an account deletion (server accepted it, or any route answered `ACCOUNT_DELETING`):
 * 1. the account's hash is written to [marks] BEFORE anything else, so after process death the app resumes
 *    here ([resumePending], awaited by the root's startup gate) instead of showing the product;
 * 2. its background work stops ([stopWork]: sync, photo uploads);
 * 3. the session ends ([endSession]: identity provider sign-out, which drops its stored credentials);
 * 4. [wipe] deletes exactly that account's database, private files, caches and per-account preferences;
 * 5. the mark is removed.
 * A failure leaves the mark (status [AccountDeletionStatus.Failed]); [retry] runs the same steps again.
 */
class AccountDeletionCoordinator(
    private val marks: AccountDeletionMarks,
    private val hashOf: (userId: String) -> String,
    private val stopWork: suspend (hash: String) -> Unit,
    /** True once this device no longer holds a session. */
    private val endSession: suspend () -> Boolean,
    private val wipe: suspend (hash: String) -> Unit,
    private val scope: CoroutineScope,
) : LocalAccountDeletion {
    private val mutableStatus = MutableStateFlow<AccountDeletionStatus>(AccountDeletionStatus.None)
    val status: StateFlow<AccountDeletionStatus> = mutableStatus.asStateFlow()

    private val mutex = Mutex()
    private var subscription: LiveSubscription? = null

    /** At startup, before the first auth state is read: finishes a deletion the previous process left behind. */
    suspend fun resumePending() {
        if (marks.pending().isEmpty()) return
        mutableStatus.value = AccountDeletionStatus.Running
        runCleanup()
    }

    override suspend fun begin(userId: String, subscription: LiveSubscription?) {
        marks.add(hashOf(userId))
        if (subscription != null) this.subscription = subscription
        mutableStatus.value = AccountDeletionStatus.Running
        runCleanup()
    }

    /** A v1 route answered `ACCOUNT_DELETING` (any thread): the deletion was started, here or elsewhere. */
    fun onAccountDeleting(userId: String) {
        scope.launch { begin(userId, null) }
    }

    fun retry() {
        if (mutableStatus.value != AccountDeletionStatus.Failed) return
        mutableStatus.value = AccountDeletionStatus.Running
        scope.launch { runCleanup() }
    }

    /** The user read the final notice. Only a finished deletion can be dismissed. */
    fun acknowledge() {
        if (mutableStatus.value !is AccountDeletionStatus.Done) return
        subscription = null
        mutableStatus.value = AccountDeletionStatus.None
    }

    private suspend fun runCleanup() = mutex.withLock {
        try {
            val hashes = marks.pending()
            hashes.forEach { stopWork(it) }
            if (hashes.isNotEmpty() && !endSession()) {
                mutableStatus.value = AccountDeletionStatus.Failed
                return@withLock
            }
            hashes.forEach { hash ->
                wipe(hash)
                marks.remove(hash)
            }
            mutableStatus.value = AccountDeletionStatus.Done(subscription)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableStatus.value = AccountDeletionStatus.Failed
        }
    }
}
