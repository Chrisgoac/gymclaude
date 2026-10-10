package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.domain.SyncResult

class FakeSyncEnvironment(override val retryGate: RetryAfterGate = RetryAfterGate(jitterMillis = { 0 })) : SyncEnvironment {
    var readiness: SyncReadiness = SyncReadiness.Ready(USER)
    var result: SyncResult = SyncResult.Success(0, 0, 0)
    var syncCalls = 0
    val syncedUsers = mutableListOf<String>()
    val sessionRequired = mutableListOf<String>()
    val unavailable = mutableListOf<String>()
    val requeued = mutableListOf<String>()

    override suspend fun readiness(userHash: String): SyncReadiness = readiness

    override suspend fun syncOnce(userId: String): SyncResult {
        syncCalls++
        syncedUsers += userId
        return result
    }

    override suspend fun requeueAfterRetryWindow(userHash: String) {
        requeued += userHash
    }

    override suspend fun onSessionRequired(userHash: String) {
        sessionRequired += userHash
    }

    override suspend fun onUnavailable(userHash: String) {
        unavailable += userHash
    }

    companion object {
        const val USER = "user_ready"
    }
}
