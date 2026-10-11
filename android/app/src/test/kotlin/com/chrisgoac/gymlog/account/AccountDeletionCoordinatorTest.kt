package com.chrisgoac.gymlog.account

import com.chrisgoac.gymlog.domain.account.LiveSubscription
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionCoordinatorTest {
    /** Survives a "process death": a new coordinator reads the same set. */
    private class MemoryMarks : AccountDeletionMarks {
        val hashes = linkedSetOf<String>()
        override suspend fun pending(): Set<String> = hashes.toSet()
        override suspend fun add(hash: String) { hashes += hash }
        override suspend fun remove(hash: String) { hashes -= hash }
    }

    private val marks = MemoryMarks()
    private val log = mutableListOf<String>()
    private var sessionEnds = true
    private var wipeFailures = 0

    private fun TestScope.coordinator() = AccountDeletionCoordinator(
        marks = marks,
        hashOf = { "h-$it" },
        stopWork = { log += "stop:$it" },
        endSession = { log += "signOut"; sessionEnds },
        wipe = { hash ->
            log += "wipe:$hash:marked=${hash in marks.hashes}"
            if (wipeFailures-- > 0) error("disk")
        },
        scope = backgroundScope,
    )

    @Test fun `an accepted deletion is recorded first, then work stops, the session ends and only that account is wiped`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        val sub = LiveSubscription("gymlog_pro_monthly", willRenew = true, validUntil = 1L, manageUrl = "https://play.example")
        coordinator.begin("user_a", sub)

        assertEquals(listOf("stop:h-user_a", "signOut", "wipe:h-user_a:marked=true"), log)
        assertTrue(marks.hashes.isEmpty())
        assertEquals(AccountDeletionStatus.Done(sub), coordinator.status.value)

        coordinator.acknowledge()
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
    }

    @Test fun `a failed wipe keeps the mark and blocks until a retry finishes it`() = runTest(UnconfinedTestDispatcher()) {
        wipeFailures = 1
        val coordinator = coordinator()
        coordinator.begin("user_a", null)
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)
        assertEquals(setOf("h-user_a"), marks.hashes)

        coordinator.acknowledge() // not dismissible while the data is still there
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)

        coordinator.retry()
        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
        assertTrue(marks.hashes.isEmpty())
    }

    @Test fun `a session that does not end keeps the data and the mark`() = runTest(UnconfinedTestDispatcher()) {
        sessionEnds = false
        val coordinator = coordinator()
        coordinator.begin("user_a", null)
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)
        assertEquals(listOf("stop:h-user_a", "signOut"), log)
        assertEquals(setOf("h-user_a"), marks.hashes)
    }

    @Test fun `after process death the pending deletion is finished before anything else`() = runTest(UnconfinedTestDispatcher()) {
        marks.hashes += "h-user_a" // the previous process died right after the server accepted
        val coordinator = coordinator()
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)

        coordinator.resumePending()

        assertEquals(listOf("stop:h-user_a", "signOut", "wipe:h-user_a:marked=true"), log)
        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
    }

    @Test fun `nothing pending at startup leaves the app alone`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        coordinator.resumePending()
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
        assertTrue(log.isEmpty())
    }

    @Test fun `an ACCOUNT_DELETING answer from any API starts the same cleanup`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = coordinator()
        coordinator.onAccountDeleting("user_a")
        assertEquals(listOf("stop:h-user_a", "signOut", "wipe:h-user_a:marked=true"), log)
        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
    }
}
