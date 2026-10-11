package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncRepository
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [SyncEnvironment] over the live app. A worker started in a cold process has to wait for the root
 * state machine (Clerk init, database open, bootstrap resume) before it can tell whether the
 * account is usable, so it never mistakes "not opened yet" for "signed out".
 */
class AppSyncEnvironment(
    private val rootState: StateFlow<RootState>,
    private val repository: SyncRepository,
    private val scheduler: WorkManagerSyncScheduler,
    override val retryGate: RetryAfterGate,
    private val startupTimeoutMillis: Long = STARTUP_TIMEOUT_MILLIS,
    /** Runs after every sync run of [userId] (e.g. evicting photos a pulled tombstone deleted); failures are logged, never fail the run. */
    private val afterSync: suspend (userId: String) -> Unit = {},
) : SyncEnvironment {
    override suspend fun readiness(userHash: String): SyncReadiness {
        if (scheduler.isSuspended(userHash)) return SyncReadiness.NotYet // sign-out in progress: no syncing.
        val settled = withTimeoutOrNull(startupTimeoutMillis) {
            rootState.first { it is RootState.Ready || it is RootState.SignedOut || it is RootState.ReauthenticationRequired || it is RootState.Fatal }
        } ?: return SyncReadiness.NotYet
        return when (settled) {
            is RootState.Ready -> if (matches(settled.userId, userHash)) SyncReadiness.Ready(settled.userId) else SyncReadiness.Unavailable
            is RootState.ReauthenticationRequired -> if (matches(settled.userId, userHash)) SyncReadiness.SessionRequired else SyncReadiness.Unavailable
            RootState.SignedOut -> SyncReadiness.SessionRequired
            is RootState.Fatal -> if (settled.canRetry) SyncReadiness.NotYet else SyncReadiness.Unavailable
            else -> SyncReadiness.NotYet
        }
    }

    override suspend fun syncOnce(userId: String): SyncResult {
        val result = repository.syncOnce(userId)
        try {
            afterSync(userId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("GymLogSync", "Post-sync cleanup failed", e)
        }
        return result
    }

    override suspend fun requeueAfterRetryWindow(userHash: String) {
        scheduler.requestSyncForHash(userHash)
    }

    override suspend fun onSessionRequired(userHash: String) {
        // A token that could not be fetched while the account is still signed in is not a sign-out.
        val current = rootState.value
        if (current is RootState.Ready && matches(current.userId, userHash)) return
        scheduler.cancelPeriodic(userHash)
    }

    override suspend fun onUnavailable(userHash: String) {
        scheduler.cancelPeriodic(userHash)
    }

    private fun matches(userId: String, userHash: String) = UserDatabaseManagerImpl.userHash(userId) == userHash

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 60_000L
    }
}
