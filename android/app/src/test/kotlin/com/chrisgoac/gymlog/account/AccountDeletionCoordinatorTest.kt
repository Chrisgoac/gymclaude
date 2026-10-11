package com.chrisgoac.gymlog.account

import com.chrisgoac.gymlog.domain.account.LiveSubscription
import com.chrisgoac.gymlog.network.AccountSignal
import com.chrisgoac.gymlog.network.AuthState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionCoordinatorTest {
    /** Survives a "process death": a new coordinator reads the same sets. */
    private class MemoryMarks : AccountDeletionMarks {
        val pending = linkedSetOf<String>()
        val intents = linkedSetOf<String>()
        val unconfirmed = linkedSetOf<String>()
        override suspend fun pending(): Set<String> = pending.toSet()
        override suspend fun intents(): Set<String> = intents.toSet()
        override suspend fun addIntent(hash: String) { intents += hash }
        override suspend fun removeIntent(hash: String) { intents -= hash }
        override suspend fun unconfirmed(): Set<String> = unconfirmed.toSet()
        override suspend fun promote(hash: String, confirmed: Boolean) {
            pending += hash
            intents -= hash
            if (confirmed) unconfirmed -= hash else unconfirmed += hash
        }
        override suspend fun remove(hash: String) { pending -= hash; unconfirmed -= hash }
    }

    private val marks = MemoryMarks()
    private val log = mutableListOf<String>()
    private val auth = MutableStateFlow<AuthState>(AuthState.SignedIn(A))
    private var identityReady = true
    private var signOutWorks = true
    private var wipeFailures = 0

    private fun TestScope.coordinator() = AccountDeletionCoordinator(
        marks = marks,
        hashOf = { "h-$it" },
        stopWork = { log += "stop:$it" },
        auth = auth,
        identityReady = { identityReady },
        signOut = {
            log += "signOut"
            if (signOutWorks) auth.value = AuthState.SignedOut
            signOutWorks
        },
        wipe = { hash, userId ->
            log += "wipe:$hash:$userId:marked=${hash in marks.pending}"
            if (wipeFailures-- > 0) error("disk")
        },
        scope = backgroundScope,
        sessionWaitMillis = 1_000L,
    )

    private suspend fun TestScope.started() = coordinator().also { it.start() }

    // ── Confirmed deletion ──

    @Test fun `an accepted deletion is recorded first, then work stops, the session ends and only that account is wiped`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        val sub = LiveSubscription("gymlog_pro_monthly", willRenew = true, validUntil = 1L, manageUrl = "https://play.google.com/store/account/subscriptions")
        coordinator.intend(A)
        coordinator.begin(A, sub)

        assertEquals(listOf("stop:h-$A", "signOut", "wipe:h-$A:$A:marked=true"), log)
        assertTrue(marks.pending.isEmpty())
        assertTrue(marks.intents.isEmpty())
        assertEquals(AccountDeletionStatus.Done(sub, serverConfirmed = true), coordinator.status.value)
        assertTrue(coordinator.isWiped(A))

        coordinator.acknowledge()
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
    }

    @Test fun `a failed wipe keeps the mark and blocks until a retry finishes it`() = runTest(UnconfinedTestDispatcher()) {
        wipeFailures = 1
        val coordinator = started()
        coordinator.begin(A, null)
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)
        assertEquals(setOf("h-$A"), marks.pending)

        coordinator.acknowledge() // not dismissible while the data is still there
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)

        coordinator.retry()
        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
        assertTrue(marks.pending.isEmpty())
    }

    @Test fun `a session that does not end keeps the data and the mark`() = runTest(UnconfinedTestDispatcher()) {
        signOutWorks = false
        val coordinator = started()
        coordinator.begin(A, null)
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)
        assertEquals(listOf("stop:h-$A", "signOut"), log)
        assertEquals(setOf("h-$A"), marks.pending)
    }

    @Test fun `after process death a confirmed deletion is shown at once and finished`() = runTest(UnconfinedTestDispatcher()) {
        marks.pending += "h-$A"
        val coordinator = coordinator()
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)

        coordinator.start()

        assertEquals(listOf("stop:h-$A", "signOut", "wipe:h-$A:null:marked=true"), log)
        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
    }

    @Test fun `ACCOUNT_DELETING from any API starts the same cleanup, and a late duplicate is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.onAccountSignal(A, AccountSignal.DELETING)
        assertEquals(listOf("stop:h-$A", "signOut", "wipe:h-$A:$A:marked=true"), log)
        coordinator.acknowledge()

        coordinator.onAccountSignal(A, AccountSignal.DELETING)
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
        assertEquals(3, log.size)
    }

    // ── Session loading (I3) ──

    @Test fun `the session is ended only once the provider has loaded it`() = runTest(UnconfinedTestDispatcher()) {
        auth.value = AuthState.Loading
        marks.pending += "h-$A"
        val coordinator = coordinator()
        coordinator.start()
        assertEquals(AccountDeletionStatus.Running, coordinator.status.value)
        assertEquals("no sign-out nor wipe before the provider loads", listOf("stop:h-$A"), log)

        auth.value = AuthState.SignedIn(A) // Clerk restored the deleted account's stored session
        assertEquals(listOf("stop:h-$A", "signOut", "wipe:h-$A:null:marked=true"), log)
        assertEquals(AuthState.SignedOut, auth.value)
        assertEquals(AccountDeletionStatus.Done(null), coordinator.status.value)
    }

    @Test fun `a provider that never loads fails the cleanup instead of skipping the sign-out`() = runTest(UnconfinedTestDispatcher()) {
        auth.value = AuthState.Loading
        marks.pending += "h-$A"
        val coordinator = coordinator()
        coordinator.start()
        advanceTimeBy(1_001L)
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)
        assertFalse(log.any { it.startsWith("wipe") })
        assertEquals(setOf("h-$A"), marks.pending)
    }

    @Test fun `a provider that failed to load (no real session state) fails the cleanup`() = runTest(UnconfinedTestDispatcher()) {
        auth.value = AuthState.SignedOut
        identityReady = false
        marks.pending += "h-$A"
        val coordinator = started()
        assertEquals(AccountDeletionStatus.Failed, coordinator.status.value)
        assertFalse(log.any { it.startsWith("wipe") })
    }

    @Test fun `a wiped account restored later by the provider is signed out again and never reopened`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.begin(A, null)
        log.clear()

        auth.value = AuthState.SignedIn(A)
        assertEquals(listOf("signOut"), log)
        assertTrue(coordinator.isWiped(A))
        assertFalse(coordinator.isWiped(B))
    }

    // ── Unconfirmed deletion (intent, I1) ──

    @Test fun `an intent alone never wipes at startup`() = runTest(UnconfinedTestDispatcher()) {
        marks.intents += "h-$A"
        val coordinator = started()
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
        assertTrue(log.isEmpty())
    }

    @Test fun `a lost answer followed by an expired session wipes the data as unconfirmed`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.intend(A) // the DELETE went out; its answer was lost (Offline)
        auth.value = AuthState.SessionExpired(A) // the server deleted the Clerk user

        assertEquals(listOf("stop:h-$A", "signOut", "wipe:h-$A:$A:marked=true"), log)
        assertEquals(AccountDeletionStatus.Done(null, serverConfirmed = false), coordinator.status.value)
        assertTrue(marks.intents.isEmpty())
    }

    @Test fun `an intent plus an ordinary 2xx keeps the data and clears the intent`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.intend(A)
        coordinator.onAccountSignal(A, AccountSignal.ACTIVE)
        assertTrue(marks.intents.isEmpty())
        assertTrue(log.isEmpty())

        auth.value = AuthState.SessionExpired(A) // a normal expiry later must not wipe anything
        assertTrue(log.isEmpty())
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
    }

    @Test fun `an intent plus ACCOUNT_DELETING becomes a confirmed deletion`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.intend(A)
        coordinator.onAccountSignal(A, AccountSignal.DELETING)
        assertEquals(AccountDeletionStatus.Done(null, serverConfirmed = true), coordinator.status.value)
        assertTrue(marks.intents.isEmpty())
    }

    @Test fun `an intent plus a rejected session is wiped`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.intend(A)
        coordinator.onAccountSignal(A, AccountSignal.SESSION_REJECTED)
        assertEquals(AccountDeletionStatus.Done(null, serverConfirmed = false), coordinator.status.value)
    }

    @Test fun `a rejected session without an intent changes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.onAccountSignal(A, AccountSignal.SESSION_REJECTED)
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
        assertTrue(log.isEmpty())
    }

    @Test fun `withdrawing (reauth required, deliberate sign-out) clears the intent`() = runTest(UnconfinedTestDispatcher()) {
        val coordinator = started()
        coordinator.intend(A)
        coordinator.withdraw(A)
        assertTrue(marks.intents.isEmpty())
        auth.value = AuthState.SignedOut
        assertTrue(log.isEmpty())
    }

    @Test fun `an intent whose session ended once the provider loaded is wiped by hash`() = runTest(UnconfinedTestDispatcher()) {
        marks.intents += "h-$A" // previous process: answer lost, then died
        auth.value = AuthState.Loading
        val coordinator = started()
        auth.value = AuthState.SignedOut
        assertEquals(listOf("stop:h-$A", "wipe:h-$A:null:marked=true"), log)
        assertEquals(AccountDeletionStatus.Done(null, serverConfirmed = false), coordinator.status.value)
    }

    @Test fun `an unconfirmed wipe interrupted by process death keeps its wording`() = runTest(UnconfinedTestDispatcher()) {
        marks.pending += "h-$A"
        marks.unconfirmed += "h-$A"
        val coordinator = started()
        assertEquals(AccountDeletionStatus.Done(null, serverConfirmed = false), coordinator.status.value)
    }

    @Test fun `an ended session is not trusted before the provider has loaded`() = runTest(UnconfinedTestDispatcher()) {
        identityReady = false
        auth.value = AuthState.SignedOut
        marks.intents += "h-$A"
        val coordinator = started()
        assertEquals(AccountDeletionStatus.None, coordinator.status.value)
        assertEquals(setOf("h-$A"), marks.intents)
    }

    private companion object {
        const val A = "user_a"
        const val B = "user_b"
    }
}

