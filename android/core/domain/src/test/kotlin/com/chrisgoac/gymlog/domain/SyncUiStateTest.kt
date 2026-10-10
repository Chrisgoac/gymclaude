package com.chrisgoac.gymlog.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncUiStateTest {
    private fun status(pending: Int = 0, quarantined: Int = 0, last: SyncResult? = null) = SyncStatus(
        running = false,
        pendingMutations = pending,
        quarantinedMutations = quarantined,
        restoreComplete = true,
        lastResult = last,
    )

    @Test fun nothingWaitingIsSynced() = assertEquals(SyncUiState.Synced, status().toUiState())

    @Test fun pendingChangesAreCounted() = assertEquals(SyncUiState.Pending(3), status(pending = 3).toUiState())

    @Test fun retryableLastResultMeansOfflineWithPendingCount() {
        assertEquals(SyncUiState.Offline(2), status(pending = 2, last = SyncResult.Retryable(null, 0)).toUiState())
    }

    @Test fun failedAttemptWithNothingWaitingIsNotOffline() {
        assertEquals(SyncUiState.Synced, status(last = SyncResult.Retryable(null, 0)).toUiState())
    }

    @Test fun rejectedChangesNeedActionEvenWhenOffline() {
        assertEquals(SyncUiState.NeedsAction(1), status(pending = 2, quarantined = 1, last = SyncResult.Retryable(null, 0)).toUiState())
    }

    @Test fun expiredSessionOutranksEverything() {
        assertEquals(SyncUiState.SessionRequired, status(pending = 2, quarantined = 1, last = SyncResult.SessionRequired(0)).toUiState())
    }

    @Test fun successfulRunWithNothingLeftIsSynced() {
        assertEquals(SyncUiState.Synced, status(last = SyncResult.Success(1, 0, 0)).toUiState())
    }
}
