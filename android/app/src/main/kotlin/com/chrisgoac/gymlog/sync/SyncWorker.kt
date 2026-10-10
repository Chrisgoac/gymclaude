package com.chrisgoac.gymlog.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.chrisgoac.gymlog.domain.SyncResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Whether the process can sync the account a piece of work was scheduled for. */
sealed interface SyncReadiness {
    /** The account ([userId], resolved from the matching ready state) is signed in, bootstrapped and its database is open. */
    data class Ready(val userId: String) : SyncReadiness

    /** The app is still starting or bootstrapping (or bootstrap failed transiently): try again later. */
    data object NotYet : SyncReadiness

    /** There is no usable session for that account: stop until it signs in again. */
    data object SessionRequired : SyncReadiness

    /** Another account is active, or the app cannot run at all: this work is obsolete. */
    data object Unavailable : SyncReadiness
}

/** What [SyncWorker] needs from the app; implemented over [com.chrisgoac.gymlog.AppContainer]. */
interface SyncEnvironment {
    val retryGate: RetryAfterGate
    suspend fun readiness(userHash: String): SyncReadiness

    /** One run for [userId] only (see [com.chrisgoac.gymlog.domain.SyncRepository.syncOnce]). */
    suspend fun syncOnce(userId: String): SyncResult

    /** Queues one run that starts when the server-requested wait of [userHash] is over. */
    suspend fun requeueAfterRetryWindow(userHash: String)

    /**
     * No usable session: the periodic work must not keep waking the device until sign-in. Kept
     * while the identity provider still holds an active session for that account (a failed token
     * fetch is not a sign-out).
     */
    suspend fun onSessionRequired(userHash: String)

    /** The work belongs to an account that is not the active one (or the app cannot run): stop its periodic wake-ups. */
    suspend fun onUnavailable(userHash: String)
}

/**
 * Remembers server-requested waits (429 `Retry-After`) per account, so neither WorkManager's own
 * retry nor a new trigger reaches the server before the window ends. In memory on purpose: after a
 * process death the server simply answers 429 again.
 */
class RetryAfterGate(
    private val clock: () -> Long = System::currentTimeMillis,
    private val jitterMillis: () -> Long = { Random.nextLong(0, JITTER_MAX_MILLIS) },
) {
    private val until = ConcurrentHashMap<String, Long>()

    fun defer(userHash: String, millis: Long) {
        val end = clock() + millis.coerceIn(0, MAX_DEFER_MILLIS) + jitterMillis()
        until.merge(userHash, end, ::maxOf)
    }

    fun remainingMillis(userHash: String): Long = ((until[userHash] ?: return 0) - clock()).coerceAtLeast(0)

    fun clear(userHash: String) {
        until.remove(userHash)
    }

    private companion object {
        const val JITTER_MAX_MILLIS = 5_000L
        const val MAX_DEFER_MILLIS = 60 * 60_000L
    }
}

/**
 * One sync run for one account. WorkManager semantics: `retry` only for transient failures (offline,
 * server busy, app not ready); inside a server-requested wait the run ends with `success` after queueing one for later; a missing session or a permanent error is `failure`; rows the server
 * rejected for good (quarantined) never fail the run.
 *
 * The input is the account's hash, never the raw user id (WorkManager persists inputs in plain text).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val environment: SyncEnvironment,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val userHash = inputData.getString(KEY_USER_HASH) ?: return Result.failure()
        if (environment.retryGate.remainingMillis(userHash) > 0) {
            // Finish cleanly and queue one run for when the window ends, instead of burning
            // WorkManager backoff attempts against a wait the server already told us about.
            environment.requeueAfterRetryWindow(userHash)
            return Result.success()
        }

        val userId = when (val readiness = environment.readiness(userHash)) {
            is SyncReadiness.Ready -> readiness.userId
            SyncReadiness.NotYet -> return Result.retry()
            SyncReadiness.Unavailable -> {
                environment.onUnavailable(userHash)
                return Result.failure()
            }
            SyncReadiness.SessionRequired -> {
                environment.onSessionRequired(userHash)
                return Result.failure()
            }
        }

        return when (val result = environment.syncOnce(userId)) {
            is SyncResult.Success -> Result.success()
            is SyncResult.Retryable -> {
                result.retryAfterMillis?.let { environment.retryGate.defer(userHash, it) }
                Result.retry()
            }
            is SyncResult.SessionRequired -> {
                environment.onSessionRequired(userHash)
                Result.failure()
            }
            // The session is still active: keep the periodic work, a later run may be accepted.
            is SyncResult.SessionRejected -> {
                Log.w(TAG, "Background sync stopped: the server rejected the session")
                Result.failure()
            }
            is SyncResult.Failed -> {
                Log.w(TAG, "Background sync stopped: ${result.code}")
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_USER_HASH = "userHash"
        private const val TAG = "GymLogSyncWorker"
    }
}

/** Manual-DI worker factory (the app has no Hilt/Koin). */
class SyncWorkerFactory(private val environment: SyncEnvironment) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == SyncWorker::class.java.name) SyncWorker(appContext, workerParameters, environment) else null
}
