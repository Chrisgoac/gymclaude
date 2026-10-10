package com.chrisgoac.gymlog.domain

import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthStep

/** Requests background sync for an account; implemented in `:app` on top of the platform scheduler. */
interface BackgroundSyncScheduler {
    /** Keeps the periodic sync of [userId] scheduled (idempotent). */
    suspend fun ensurePeriodic(userId: String)

    /** Asks for a sync soon; requests arriving while one is already queued are coalesced. */
    suspend fun requestSync(userId: String)

    /** Cancels every scheduled and running sync of [userId] (sign-out). */
    suspend fun cancel(userId: String)
}

/** Local per-user data of the device, as far as sign-out is concerned. */
interface LocalAccountCleaner {
    /** Local changes of [userId] the server does not have (pending and rejected ones). */
    suspend fun unsyncedChangeCount(userId: String): Int

    /**
     * Deletes the user's database and every other per-user private file. (There is no per-user
     * DataStore or private cache yet; whatever exists under the user's private directories goes.)
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
 * Sign-out that resolves the outbox first: background work is cancelled, unsynced changes are
 * counted, and only an explicit confirmation lets them be thrown away. The session is ended before
 * the files are deleted, so a failed sign-out never leaves a signed-in user without a database.
 */
class SignOutUseCase(
    private val currentUserId: () -> String?,
    private val scheduler: BackgroundSyncScheduler,
    private val cleaner: LocalAccountCleaner,
    private val auth: AuthGateway,
) {
    private var awaiting: String? = null

    suspend fun begin(): SignOutOutcome {
        val userId = currentUserId() ?: return if (auth.signOut() == AuthStep.Completed) SignOutOutcome.SignedOut else SignOutOutcome.Failed
        scheduler.cancel(userId)
        val unsynced = cleaner.unsyncedChangeCount(userId)
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

    /** The user kept the changes: sync resumes. */
    suspend fun cancel() {
        val userId = awaiting ?: return
        awaiting = null
        scheduler.ensurePeriodic(userId)
        scheduler.requestSync(userId)
    }

    private suspend fun finish(userId: String): SignOutOutcome {
        if (auth.signOut() != AuthStep.Completed) {
            scheduler.ensurePeriodic(userId)
            scheduler.requestSync(userId)
            return SignOutOutcome.Failed
        }
        return try {
            cleaner.deleteLocalData(userId)
            SignOutOutcome.SignedOut
        } catch (_: Exception) {
            SignOutOutcome.Failed
        }
    }
}
