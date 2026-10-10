package com.chrisgoac.gymlog.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L
    private val env = FakeSyncEnvironment(RetryAfterGate(clock = { now }, jitterMillis = { 0 }))
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler

    private fun hash(userId: String) = UserDatabaseManagerImpl.userHash(userId)

    @Before fun setUp() {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(SyncWorkerFactory(env))
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerSyncScheduler({ workManager }, env.retryGate)
    }

    private fun infos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    @Test fun workNamesUseTheSameHashAsTheDatabaseFile() {
        val h = hash("user_1")
        assertEquals("sync-$h", WorkManagerSyncScheduler.oneTimeName(h))
        assertEquals("sync-$h-periodic", WorkManagerSyncScheduler.periodicName(h))
        assertTrue(UserDatabaseManagerImpl.databaseFileName("user_1").contains(h))
        assertTrue("raw ids never reach WorkManager", !WorkManagerSyncScheduler.oneTimeName(h).contains("user_1"))
    }

    @Test fun immediateWorkNeedsNetworkAndUsesExponentialBackoff() = runBlocking {
        scheduler.requestSync("user_1")

        val info = infos("sync-${hash("user_1")}").single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.tags.contains("sync-${hash("user_1")}"))
        val spec = workManagerSpec(info)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.first)
    }

    @Test fun periodicWorkRunsEveryFifteenMinutesWithNetworkAndIsUnique() = runBlocking {
        scheduler.ensurePeriodic("user_1")
        scheduler.ensurePeriodic("user_1")

        val info = infos("sync-${hash("user_1")}-periodic").single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(15 * 60_000L, info.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun repeatedRequestsCoalesceIntoOneQueuedRun() = runBlocking {
        repeat(5) { scheduler.requestSync("user_1") }
        assertEquals(1, infos("sync-${hash("user_1")}").size)
    }

    @Test fun aRequestDuringARunQueuesExactlyOneFollowUpBehindIt() = runBlocking {
        scheduler.requestSync("user_1")
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(infos("sync-${hash("user_1")}").single().id)
        // The first run finished; a later trigger starts a new one.
        scheduler.requestSync("user_1")
        scheduler.requestSync("user_1")
        val states = infos("sync-${hash("user_1")}").map { it.state }
        assertEquals(1, states.count { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED || it == WorkInfo.State.RUNNING })
    }

    @Test fun accountsHaveIndependentWork() = runBlocking {
        scheduler.requestSync("user_1")
        scheduler.requestSync("user_2")
        assertEquals(1, infos("sync-${hash("user_1")}").size)
        assertEquals(1, infos("sync-${hash("user_2")}").size)
    }

    @Test fun retryAfterBecomesTheInitialDelayOfTheNextEnqueue() = runBlocking {
        env.retryGate.defer(hash("user_1"), 90_000)
        scheduler.requestSync("user_1")

        val delay = infos("sync-${hash("user_1")}").single().initialDelayMillis
        assertTrue("delay $delay", delay in 89_000..90_000)
    }

    @Test fun signOutCancelsBothWorksAndForgetsTheRetryWindow() = runBlocking {
        env.retryGate.defer(hash("user_1"), 90_000)
        scheduler.ensurePeriodic("user_1")
        scheduler.requestSync("user_1")

        scheduler.cancel("user_1")

        assertTrue(infos("sync-${hash("user_1")}").all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(infos("sync-${hash("user_1")}-periodic").all { it.state == WorkInfo.State.CANCELLED })
        assertEquals(0, env.retryGate.remainingMillis(hash("user_1")))
    }

    @Test fun cancellingOneAccountLeavesTheOtherAlone() = runBlocking {
        scheduler.requestSync("user_1")
        scheduler.requestSync("user_2")
        scheduler.cancel("user_1")
        assertEquals(WorkInfo.State.ENQUEUED, infos("sync-${hash("user_2")}").single().state)
    }

    @Test fun periodicWorkCanBeCancelledByHashWhenTheSessionIsGone() = runBlocking {
        scheduler.ensurePeriodic("user_1")
        scheduler.cancelPeriodic(hash("user_1"))
        assertTrue(infos("sync-${hash("user_1")}-periodic").all { it.state == WorkInfo.State.CANCELLED })
    }

    /** Backoff policy of the stored spec: WorkInfo does not expose it, so read it through the test driver's DB. */
    private fun workManagerSpec(info: WorkInfo): Pair<BackoffPolicy, Long> {
        val spec = androidx.work.impl.WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
        return spec.backoffPolicy to spec.backoffDelayDuration
    }
}
