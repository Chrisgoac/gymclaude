package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTriggersTest {
    private class RecordingScheduler : BackgroundSyncScheduler {
        val periodic = mutableListOf<String>()
        val requests = mutableListOf<String>()
        override suspend fun ensurePeriodic(userId: String) { periodic += userId }
        val soft = mutableListOf<Boolean>()
        override suspend fun requestSync(userId: String, queueBehindRunning: Boolean) { requests += userId; soft += !queueBehindRunning }
        override suspend fun cancel(userId: String) = Unit
        override fun suspend(userId: String) = Unit
        override fun resume(userId: String) = Unit
    }

    private val root = MutableStateFlow<RootState>(RootState.Loading)
    private val scheduler = RecordingScheduler()

    @Test fun readyAccountSchedulesPeriodicAndSyncsOnceEvenIfStateRepeats() = runTest {
        SyncTriggers(backgroundScope, root, scheduler).start()
        runCurrent()
        assertTrue(scheduler.requests.isEmpty())

        root.value = RootState.Bootstrapping("u1", 0.5f)
        runCurrent()
        assertTrue(scheduler.requests.isEmpty())

        root.value = RootState.Ready("u1")
        runCurrent()
        assertEquals(listOf("u1"), scheduler.periodic)
        assertEquals(listOf("u1"), scheduler.requests)
    }

    @Test fun nothingIsScheduledWhileSignedOutOrExpired() = runTest {
        SyncTriggers(backgroundScope, root, scheduler).start()
        root.value = RootState.SignedOut
        runCurrent()
        root.value = RootState.ReauthenticationRequired("u1")
        runCurrent()
        assertTrue(scheduler.requests.isEmpty() && scheduler.periodic.isEmpty())
    }

    @Test fun burstOfMutationsSyncsOnceTwoSecondsAfterTheLastOne() = runTest {
        root.value = RootState.Ready("u1")
        val triggers = SyncTriggers(backgroundScope, root, scheduler)
        triggers.start()
        runCurrent()
        scheduler.requests.clear()

        triggers.onLocalMutation()
        advanceTimeBy(1_500)
        triggers.onLocalMutation()
        advanceTimeBy(1_999)
        runCurrent()
        assertTrue("still debouncing", scheduler.requests.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("u1"), scheduler.requests)
    }

    @Test fun foregroundRequestsSyncOnlyForAReadyAccount() = runTest {
        val triggers = SyncTriggers(backgroundScope, root, scheduler)
        triggers.start()
        runCurrent()
        triggers.onForeground()
        runCurrent()
        assertTrue(scheduler.requests.isEmpty())

        root.value = RootState.Ready("u1")
        runCurrent()
        scheduler.requests.clear()
        triggers.onForeground()
        runCurrent()
        assertEquals(listOf("u1"), scheduler.requests)
    }

    @Test fun startupAndForegroundAreSoftButMutationsQueueBehindARunningSync() = runTest {
        root.value = RootState.Ready("u1")
        val triggers = SyncTriggers(backgroundScope, root, scheduler)
        triggers.start()
        runCurrent()
        triggers.onForeground()
        runCurrent()
        triggers.onLocalMutation()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf(true, true, false), scheduler.soft)
    }

    @Test fun notifyingRunnerSignalsOnlyCommittedMutations() = runTest {
        var signals = 0
        val ok = object : LocalMutationRunner {
            override suspend fun <T> run(mutation: LocalMutation<T>): T = "done" as T
        }
        val failing = object : LocalMutationRunner {
            override suspend fun <T> run(mutation: LocalMutation<T>): T = error("rolled back")
        }
        NotifyingLocalMutationRunner(ok) { signals++ }.run(LocalMutation { })
        runCatching { NotifyingLocalMutationRunner(failing) { signals++ }.run(LocalMutation { }) }
        assertEquals(1, signals)
    }
}
