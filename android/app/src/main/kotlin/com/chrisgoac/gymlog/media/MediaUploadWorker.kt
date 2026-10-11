package com.chrisgoac.gymlog.media

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chrisgoac.gymlog.model.media.MediaWorkOutcome
import com.chrisgoac.gymlog.sync.SyncReadiness
import java.util.concurrent.TimeUnit

/** What [MediaUploadWorker] needs from the app. */
interface MediaWorkEnvironment {
    /** Same readiness as sync: NotYet while the app starts or the account is signing out. */
    suspend fun readiness(userHash: String): SyncReadiness

    /** One upload/delete pass of photo [mediaId] for [userId] (replay-safe, serialised per account). */
    suspend fun process(userId: String, mediaId: String): MediaWorkOutcome
}

/**
 * Uploads (or deletes) one queued photo. Inputs are the account hash and the photo id, never the raw
 * user id. `retry` only for transient failures; a permanent server refusal ends in `success` with the
 * photo marked rejected (its file kept for the user); no session or another account → `failure`
 * (queued photos are re-scheduled when the account is ready again).
 */
class MediaUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val environment: MediaWorkEnvironment,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userHash = inputData.getString(KEY_USER_HASH) ?: return Result.failure()
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val userId = when (val readiness = environment.readiness(userHash)) {
            is SyncReadiness.Ready -> readiness.userId
            SyncReadiness.NotYet -> return Result.retry()
            SyncReadiness.SessionRequired, SyncReadiness.Unavailable -> return Result.failure()
        }
        return when (environment.process(userId, mediaId)) {
            MediaWorkOutcome.DONE -> Result.success()
            MediaWorkOutcome.RETRY -> Result.retry()
            MediaWorkOutcome.SESSION_REQUIRED, MediaWorkOutcome.UNAVAILABLE -> Result.failure()
        }
    }

    companion object {
        const val KEY_USER_HASH = "userHash"
        const val KEY_MEDIA_ID = "mediaId"
    }
}

/**
 * Unique work per account + photo (`media-<hash>-<id>`, tagged `media-<hash>`), network-constrained,
 * exponential backoff. User actions append behind a running pass (a delete queued during an upload must
 * not be dropped); resumes after a restart keep what is already queued. Nothing is scheduled for an
 * account that is signing out.
 */
class WorkManagerMediaScheduler(
    private val workManager: () -> WorkManager,
    private val isSuspended: (userHash: String) -> Boolean,
) {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(userHash: String, mediaId: String, resume: Boolean = false, immediately: Boolean = false) {
        if (isSuspended(userHash)) return
        val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(MediaUploadWorker.KEY_USER_HASH to userHash, MediaUploadWorker.KEY_MEDIA_ID to mediaId))
            .addTag(tag(userHash))
            .build()
        // An explicit user retry replaces the queued/backing-off pass so it starts now (the upload is replay-safe by id).
        val policy = when {
            resume -> ExistingWorkPolicy.KEEP
            immediately -> ExistingWorkPolicy.REPLACE
            else -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        workManager().enqueueUniqueWork(workName(userHash, mediaId), policy, request)
    }

    /** Stops every queued photo of an account whose local data is being deleted. */
    fun cancelAll(userHash: String) {
        workManager().cancelAllWorkByTag(tag(userHash))
    }

    companion object {
        const val BACKOFF_SECONDS = 30L

        fun workName(userHash: String, mediaId: String) = "media-$userHash-$mediaId"
        fun tag(userHash: String) = "media-$userHash"
    }
}

/** Manual-DI worker factory for [MediaUploadWorker]. */
class MediaUploadWorkerFactory(private val environment: MediaWorkEnvironment) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == MediaUploadWorker::class.java.name) MediaUploadWorker(appContext, workerParameters, environment) else null
}
