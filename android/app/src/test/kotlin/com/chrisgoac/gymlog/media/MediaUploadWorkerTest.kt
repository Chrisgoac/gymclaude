package com.chrisgoac.gymlog.media

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.chrisgoac.gymlog.model.media.MediaWorkOutcome
import com.chrisgoac.gymlog.sync.SyncReadiness
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaUploadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val hash = "a".repeat(32)

    private class FakeEnvironment : MediaWorkEnvironment {
        var readiness: SyncReadiness = SyncReadiness.Ready("user_1")
        var outcome = MediaWorkOutcome.DONE
        val processed = mutableListOf<Pair<String, String>>()
        override suspend fun readiness(userHash: String) = readiness
        override suspend fun process(userId: String, mediaId: String): MediaWorkOutcome {
            processed += userId to mediaId
            return outcome
        }
    }

    private val env = FakeEnvironment()

    private fun run(): Result = runBlocking {
        TestListenableWorkerBuilder<MediaUploadWorker>(context)
            .setWorkerFactory(MediaUploadWorkerFactory(env))
            .setInputData(workDataOf(MediaUploadWorker.KEY_USER_HASH to hash, MediaUploadWorker.KEY_MEDIA_ID to "m1"))
            .build()
            .doWork()
    }

    @Test fun outcomesMapToWorkResults() {
        assertEquals(Result.success(), run())
        assertEquals(listOf("user_1" to "m1"), env.processed)
        env.outcome = MediaWorkOutcome.RETRY
        assertEquals(Result.retry(), run())
        env.outcome = MediaWorkOutcome.SESSION_REQUIRED
        assertEquals(Result.failure(), run())
        env.outcome = MediaWorkOutcome.UNAVAILABLE
        assertEquals(Result.failure(), run())
    }

    @Test fun notReadyOrSigningOutRetriesWithoutTouchingThePhoto() {
        env.readiness = SyncReadiness.NotYet
        assertEquals(Result.retry(), run())
        env.readiness = SyncReadiness.SessionRequired
        assertEquals(Result.failure(), run())
        env.readiness = SyncReadiness.Unavailable
        assertEquals(Result.failure(), run())
        assertTrue(env.processed.isEmpty())
    }

    @Test fun workIsUniquePerAccountAndPhotoNeedsNetworkAndIsSkippedWhileSigningOut() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(MediaUploadWorkerFactory(env)).build(),
        )
        val workManager = WorkManager.getInstance(context)
        val suspended = mutableSetOf<String>()
        val scheduler = WorkManagerMediaScheduler({ workManager }, { it in suspended })

        scheduler.schedule(hash, "m1")
        scheduler.schedule(hash, "m1", resume = true) // a resume keeps the queued work
        scheduler.schedule(hash, "m2")
        val m1 = workManager.getWorkInfosForUniqueWork(WorkManagerMediaScheduler.workName(hash, "m1")).get()
        assertEquals(1, m1.size)
        assertEquals(NetworkType.CONNECTED, m1.single().constraints.requiredNetworkType)
        assertEquals(WorkInfo.State.ENQUEUED, m1.single().state)
        assertEquals(2, workManager.getWorkInfosByTag(WorkManagerMediaScheduler.tag(hash)).get().size)

        val other = "b".repeat(32)
        suspended += other
        scheduler.schedule(other, "m3")
        assertTrue(workManager.getWorkInfosByTag(WorkManagerMediaScheduler.tag(other)).get().isEmpty())

        scheduler.cancelAll(hash)
        assertTrue(workManager.getWorkInfosByTag(WorkManagerMediaScheduler.tag(hash)).get().all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test fun theMergedManifestRequestsNoBroadMediaStorageOrCameraPermission() {
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toSet()
        val forbidden = setOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            "android.permission.ACCESS_MEDIA_LOCATION",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.CAMERA",
        )
        assertTrue("INTERNET is read from the merged manifest", "android.permission.INTERNET" in requested)
        assertEquals(emptySet<String>(), requested intersect forbidden)
    }

    @Test fun cameraCaptureTargetsLiveInTheAccountsPrivateDirectory() {
        val targets = CameraCaptureTargets(context)
        val target = targets.create(hash)
        assertTrue(target.file.path.startsWith(context.filesDir.path + "/capture/$hash/"))
        assertEquals("content", target.uri.scheme)
        assertEquals("${context.packageName}.media", target.uri.authority)
        target.file.writeBytes(byteArrayOf(1))
        targets.release(target)
        assertTrue(!target.file.exists())
        val another = targets.create(hash).also { it.file.writeBytes(byteArrayOf(1)) }
        targets.forget(hash)
        assertTrue(!another.file.parentFile!!.exists())
    }
}
