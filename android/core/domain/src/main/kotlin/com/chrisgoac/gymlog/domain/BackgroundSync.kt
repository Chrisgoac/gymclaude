package com.chrisgoac.gymlog.domain

import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthStep
import kotlinx.coroutines.CancellationException

/** Requests background sync for an account; implemented in `:app` on top of the platform scheduler. */
interface BackgroundSyncScheduler {
    /** Keeps the periodic sync of [userId] scheduled (idempotent). No-op while [userId] is suspended. */
    suspend fun ensurePeriodic(userId: String)

    /**
     * Asks for a sync soon; requests arriving while one is already queued are coalesced. With
     * [queueBehindRunning] false a run already in progress also satisfies the request (startup and
     * foreground triggers); local mutations and manual taps pass true so a change made during a
     * run is not left waiting for the next period. No-op while [userId] is suspended.
     */
    suspend fun requestSync(userId: String, queueBehindRunning: Boolean = true)

    /** Cancels every scheduled and running sync of [userId] (sign-out). */
    suspend fun cancel(userId: String)

    /** Makes every trigger for [userId] a no-op (sign-out in progress) without cancelling existing work. */
    fun suspend(userId: String)

    /** Undoes [suspend]. */
    fun resume(userId: String)
}

/** Local per-user data of the device, as far as sign-out is concerned. */
interface LocalAccountCleaner {
    /**
     * Local changes of [userId] the server does not have (pending and rejected ones). Reads only
     * the database that is currently open for that user and fails otherwise: it never opens one.
     */
    suspend fun unsyncedChangeCount(userId: String): Int

    /** Durably records, before the session ends, that [userId]'s local data must be deleted. */
    suspend fun markPendingDeletion(userId: String)

    /** Forgets [markPendingDeletion] (the sign-out did not happen). */
    suspend fun clearPendingDeletion(userId: String)

    /**
     * Waits for any sync run of the user to finish, then deletes the user's database and every
     * other per-user private file, and finally clears the pending-deletion mark. (There is no
     * per-user DataStore or private cache yet; whatever exists under the user's private
     * directories goes.)
     */
    suspend fun deleteLocalData(userId: String)
}

sealed interface SignOutOutcome {
    /** The session ended and [userId]'s local data is gone. */
    data object SignedOut : SignOutOutcome

    /** Local changes would be lost: ask the user, then [SignOutUseCase.confirmDiscard] or [SignOutUseCase.cancel]. */
    data class ConfirmationRequired(val unsyncedChanges: Int) : SignOutOutcome

    /** Nothing was deleted (or only the files remain); the user may try again. */
    data object Failed : SignOutOutcome
}

/**
 * Sign-out that resolves the outbox first. From the moment it starts, sync triggers for the user
 * are suspended (existing work is left alone until the user decides); unsynced changes are counted
 * and only an explicit confirmation lets them be thrown away. On confirmation the work is
 * cancelled, the deletion is recorded durably, the session ends, and only then the files go, so a
 * failed sign-out never leaves a signed-in user without a database and an interrupted deletion is
 * finished at the next app start.
 */
class SignOutUseCase(
    private val currentUserId: () -> String?,
    private val scheduler: BackgroundSyncScheduler,
    private val cleaner: LocalAccountCleaner,
    private val auth: AuthGateway,
) {
    private var awaiting: String? = null

    suspend fun begin(): SignOutOutcome {
        val userId = currentUserId()
            ?: return if (auth.signOut() == AuthStep.Completed) SignOutOutcome.SignedOut else SignOutOutcome.Failed
        scheduler.suspend(userId)
        val unsynced = try {
            cleaner.unsyncedChangeCount(userId)
        } catch (cancelled: CancellationException) {
            scheduler.resume(userId)
            throw cancelled
        } catch (_: Exception) {
            scheduler.resume(userId)
            return SignOutOutcome.Failed
        }
        if (unsynced > 0) {
            awaiting = userId
            return SignOutOutcome.ConfirmationRequired(unsynced)
        }
        return finish(userId)
    }

    /** The user accepted losing the unsynced changes. */
    suspend fun confirmDiscard(): SignOutOutcome {
        val userId = awaiting ?: return SignOutOutcome.Failed
        awaiting = null
        return finish(userId)
    }

    /** The user kept the changes: triggers resume and a sync is requested. */
    suspend fun cancel() {
        val userId = awaiting ?: return
        awaiting = null
        scheduler.resume(userId)
        scheduler.requestSync(userId)
    }

    /** The confirmation went away without an answer (screen left, owner cleared): keep everything. */
    fun abandon() {
        val userId = awaiting ?: return
        awaiting = null
        scheduler.resume(userId)
    }

    private suspend fun finish(userId: String): SignOutOutcome {
        try {
            scheduler.cancel(userId)
            cleaner.markPendingDeletion(userId)
            if (auth.signOut() != AuthStep.Completed) {
                cleaner.clearPendingDeletion(userId)
                scheduler.resume(userId)
                scheduler.ensurePeriodic(userId)
                scheduler.requestSync(userId)
                return SignOutOutcome.Failed
            }
            cleaner.deleteLocalData(userId)
            return SignOutOutcome.SignedOut
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SignOutOutcome.Failed
        } finally {
            scheduler.resume(userId)
        }
    }
}
