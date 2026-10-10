package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncRepository
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
) : SyncEnvironment {
    override suspend fun readiness(userHash: String): SyncReadiness {
        if (scheduler.isSuspended(userHash)) return SyncReadiness.NotYet // sign-out in progress: no syncing.
        val settled = withTimeoutOrNull(startupTimeoutMillis) {
            rootState.first { it is RootState.Ready || it is RootState.SignedOut || it is RootState.ReauthenticationRequired || it is RootState.Fatal }
        } ?: return SyncReadiness.NotYet
        return when (settled) {
            is RootState.Ready -> if (matches(settled.userId, userHash)) SyncReadiness.Ready else SyncReadiness.Unavailable
            is RootState.ReauthenticationRequired -> if (matches(settled.userId, userHash)) SyncReadiness.SessionRequired else SyncReadiness.Unavailable
            RootState.SignedOut -> SyncReadiness.SessionRequired
            is RootState.Fatal -> if (settled.canRetry) SyncReadiness.NotYet else SyncReadiness.Unavailable
            else -> SyncReadiness.NotYet
        }
    }

    override suspend fun syncOnce(): SyncResult = repository.syncOnce()

    override suspend fun requeueAfterRetryWindow(userHash: String) {
        scheduler.requestSyncForHash(userHash)
    }

    override suspend fun onSessionRequired(userHash: String) {
        scheduler.cancelPeriodic(userHash)
    }

    private fun matches(userId: String, userHash: String) = UserDatabaseManagerImpl.userHash(userId) == userHash

    private companion object {
        const val STARTUP_TIMEOUT_MILLIS = 60_000L
    }
}
