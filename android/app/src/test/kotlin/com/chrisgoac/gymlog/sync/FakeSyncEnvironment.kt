package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.domain.SyncResult

class FakeSyncEnvironment(override val retryGate: RetryAfterGate = RetryAfterGate(jitterMillis = { 0 })) : SyncEnvironment {
    var readiness: SyncReadiness = SyncReadiness.Ready
    var result: SyncResult = SyncResult.Success(0, 0, 0)
    var syncCalls = 0
    val sessionRequired = mutableListOf<String>()

    override suspend fun readiness(userHash: String): SyncReadiness = readiness

    override suspend fun syncOnce(): SyncResult {
        syncCalls++
        return result
    }

    override suspend fun onSessionRequired(userHash: String) {
        sessionRequired += userHash
    }
}
