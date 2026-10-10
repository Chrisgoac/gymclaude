package com.chrisgoac.gymlog.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.chrisgoac.gymlog.domain.SyncResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val env = FakeSyncEnvironment()
    private val hash = "a".repeat(32)

    private fun run(input: String? = hash): Result = runBlocking {
        TestListenableWorkerBuilder<SyncWorker>(context)
            .setWorkerFactory(SyncWorkerFactory(env))
            .setInputData(if (input == null) workDataOf() else workDataOf(SyncWorker.KEY_USER_HASH to input))
            .build()
            .doWork()
    }

    @Test fun successfulRunSucceeds() {
        assertEquals(Result.success(), run())
        assertEquals(1, env.syncCalls)
    }

    @Test fun theRunIsForTheUserResolvedFromTheMatchingReadyState() {
        env.readiness = SyncReadiness.Ready("user_42")
        run()
        assertEquals(listOf("user_42"), env.syncedUsers)
    }

    @Test fun aRejectedSessionStopsTheRunButKeepsThePeriodicWork() {
        env.result = SyncResult.SessionRejected(quarantined = 0)
        assertEquals(Result.failure(), run())
        assertTrue(env.sessionRequired.isEmpty())
        assertTrue(env.unavailable.isEmpty())
    }

    @Test fun quarantinedRowsDoNotMakeTheRunFail() {
        env.result = SyncResult.Success(pushed = 2, pulled = 0, quarantined = 1)
        assertEquals(Result.success(), run())
    }

    @Test fun transientFailureRetries() {
        env.result = SyncResult.Retryable(retryAfterMillis = null, quarantined = 0)
        assertEquals(Result.retry(), run())
    }

    @Test fun retryAfterIsRememberedAndLaterRunsInsideTheWindowQueueOneRunInsteadOfCallingTheServer() {
        env.result = SyncResult.Retryable(retryAfterMillis = 60_000, quarantined = 0)
        assertEquals(Result.retry(), run())
        assertTrue(env.retryGate.remainingMillis(hash) in 1..60_000)

        // The WorkManager retry (or any other trigger) inside the window ends cleanly after
        // queueing one run for when the window closes; the server is not called.
        assertEquals(Result.success(), run())
        assertEquals(1, env.syncCalls)
        assertEquals(listOf(hash), env.requeued)
    }

    @Test fun permanentFailureStopsWithoutRetry() {
        env.result = SyncResult.Failed("PULL_REJECTED", quarantined = 0)
        assertEquals(Result.failure(), run())
    }

    @Test fun missingSessionStopsAndCancelsThePeriodicWork() {
        env.result = SyncResult.SessionRequired(quarantined = 0)
        assertEquals(Result.failure(), run())
        assertEquals(listOf(hash), env.sessionRequired)
    }

    @Test fun signedOutBeforeTheRunStopsWithoutSyncing() {
        env.readiness = SyncReadiness.SessionRequired
        assertEquals(Result.failure(), run())
        assertEquals(0, env.syncCalls)
        assertEquals(listOf(hash), env.sessionRequired)
    }

    @Test fun appNotReadyYetRetries() {
        env.readiness = SyncReadiness.NotYet
        assertEquals(Result.retry(), run())
        assertEquals(0, env.syncCalls)
    }

    @Test fun workOfAnotherAccountIsDroppedAndItsPeriodicWorkCancelled() {
        env.readiness = SyncReadiness.Unavailable
        assertEquals(Result.failure(), run())
        assertEquals(0, env.syncCalls)
        assertEquals(listOf(hash), env.unavailable)
    }

    @Test fun missingInputFails() {
        assertEquals(Result.failure(), run(input = null))
    }
}
