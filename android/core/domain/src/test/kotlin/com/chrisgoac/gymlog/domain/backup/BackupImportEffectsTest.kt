package com.chrisgoac.gymlog.domain.backup

import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupImportEffectsTest {
    @Test fun `an import invalidates achievements as a non-local edit and requests a sync`() = runTest(UnconfinedTestDispatcher()) {
        val invalidation = StatsInvalidation()
        var changes = 0
        var localEdits = 0
        var syncs = 0
        val a = launch { invalidation.changes(setOf(InvalidationScope.ACHIEVEMENTS)).collect { changes++ } }
        val b = launch { invalidation.localEditChanges(setOf(InvalidationScope.ACHIEVEMENTS)).collect { localEdits++ } }

        BackupImportEffects(invalidation, requestSync = { syncs++ })()

        assertEquals(1, changes)
        assertEquals(0, localEdits) // reconciliation may unlock/retain, never tombstone
        assertEquals(1, syncs)
        a.cancel()
        b.cancel()
    }
}
