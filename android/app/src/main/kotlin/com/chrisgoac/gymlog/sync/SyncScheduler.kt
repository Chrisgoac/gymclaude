package com.chrisgoac.gymlog.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager scheduling of the sync of each account (spec 10.5).
 *
 * Per account two unique works, both named from the same hash as the database file (never the raw id):
 * - `sync-<hash>`: immediate one-time runs. Requests use `APPEND_OR_REPLACE`: `KEEP` would drop a
 *   trigger arriving while a run is in flight (that run may already have read the outbox, so a fresh
 *   local mutation would wait for the next period), and `REPLACE` would cancel the in-flight push.
 *   Appending queues a follow-up behind the running one (WorkManager runs one chain element at a
 *   time, and `SyncLocks` serialises the repository anyway). Requests are coalesced: while a run is
 *   queued and has not started, or a follow-up is already waiting, no further work is added.
 * - `sync-<hash>-periodic`: a 15 minute (WorkManager minimum) periodic run. It is a separate name
 *   because one unique name cannot hold both a one-time chain and a periodic work.
 *
 * Both need a network connection, which also covers the "connectivity returned" trigger, and use
 * exponential backoff (WorkManager clamps it between 10 s and 5 h). Jitter is added to server
 * `Retry-After` waits by [RetryAfterGate] and WorkManager spreads periodic runs over the flex window.
 */
class WorkManagerSyncScheduler(
    private val workManager: () -> WorkManager,
    private val retryGate: RetryAfterGate,
) : BackgroundSyncScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override suspend fun ensurePeriodic(userId: String) {
        val hash = hashOf(userId)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIOD_MINUTES, TimeUnit.MINUTES,
            FLEX_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_USER_HASH to hash))
            .addTag(oneTimeName(hash))
            .build()
        workManager().enqueueUniquePeriodicWork(periodicName(hash), ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override suspend fun requestSync(userId: String) {
        val hash = hashOf(userId)
        val manager = workManager()
        val existing = manager.getWorkInfosForUniqueWorkFlow(oneTimeName(hash)).first()
        val alreadyQueued = existing.any {
            it.state == WorkInfo.State.BLOCKED || (it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0)
        }
        if (alreadyQueued) return

        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInitialDelay(retryGate.remainingMillis(hash), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_USER_HASH to hash))
            .addTag(oneTimeName(hash))
            .build()
        manager.enqueueUniqueWork(oneTimeName(hash), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override suspend fun cancel(userId: String) {
        val hash = hashOf(userId)
        val manager = workManager()
        manager.cancelUniqueWork(oneTimeName(hash))
        manager.cancelUniqueWork(periodicName(hash))
        retryGate.clear(hash)
    }

    /** Stops the periodic wake-ups of an account that has no session (the worker only knows the hash). */
    fun cancelPeriodic(userHash: String) {
        workManager().cancelUniqueWork(periodicName(userHash))
    }

    companion object {
        const val PERIOD_MINUTES = 15L
        const val FLEX_MINUTES = 5L
        const val BACKOFF_SECONDS = 30L

        fun oneTimeName(userHash: String) = "sync-$userHash"
        fun periodicName(userHash: String) = "sync-$userHash-periodic"
        fun hashOf(userId: String) = UserDatabaseManagerImpl.userHash(userId)
    }
}
