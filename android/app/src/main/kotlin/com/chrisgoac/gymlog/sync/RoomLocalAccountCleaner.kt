package com.chrisgoac.gymlog.sync

import android.content.Context
import com.chrisgoac.gymlog.data.sync.SyncLocks
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import com.chrisgoac.gymlog.domain.LocalAccountCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Counts and deletes one account's local data. Besides the Room files it removes
 * `files/users/<hash>` and `cache/users/<hash>` (the places per-user private files and thumbnails
 * must use; the rest-timer DataStore lives there, released through [onLocalDataDeleted]).
 *
 * Deletion is recorded (by hash, never the raw id) in [PendingDeletionMarks] before the session
 * ends and cleared once the files are gone; [sweepPendingDeletions] and [reconcile] finish any that
 * a crash or a failed delete left behind.
 */
class RoomLocalAccountCleaner(
    context: Context,
    private val databases: UserDatabaseManagerImpl,
    private val locks: SyncLocks,
    /** Runs just BEFORE an account's files are deleted (by hash): releases per-user caches (open files) and, for the current account only, wake-ups. */
    private val onLocalDataDeleted: (userHash: String) -> Unit = {},
) : LocalAccountCleaner {
    private val appContext = context.applicationContext
    private val marks = PendingDeletionMarks(appContext)

    override suspend fun unsyncedChangeCount(userId: String): Int {
        val open = databases.current.value?.takeIf { it.userId == userId }
            ?: error("The database of the signing-out account is not the open one")
        val dao = open.database.syncDao()
        // Queued (or rejected) photo uploads/deletes never reached the server either.
        return dao.outboxCount(OutboxStatus.PENDING) + dao.outboxCount(OutboxStatus.QUARANTINED) +
            open.database.pendingMediaDao().count()
    }

    override suspend fun markPendingDeletion(userId: String) = marks.markIntent(UserDatabaseManagerImpl.userHash(userId))

    override suspend fun confirmPendingDeletion(userId: String) = marks.confirm(UserDatabaseManagerImpl.userHash(userId))

    override suspend fun clearPendingDeletion(userId: String) = marks.clear(UserDatabaseManagerImpl.userHash(userId))

    override suspend fun deleteLocalData(userId: String) {
        // An in-flight sync run holds this lock: wait for it before the files disappear under it.
        locks.forUser(userId).withLock {
            val hash = UserDatabaseManagerImpl.userHash(userId)
            deleteByHash(hash)
            marks.clear(hash)
        }
    }

    /**
     * Account deletion: removes everything local of the account [hash] (database, private files, caches and the
     * per-account stores released by [onLocalDataDeleted]) and any sign-out mark it had. Only the hash is known
     * after process death; the caller has already stopped the account's work and ended its session.
     */
    suspend fun deleteAccountData(hash: String) {
        deleteByHash(hash)
        marks.clear(hash)
    }

    /**
     * Called before [userId]'s database is opened (every open, not only at start). A confirmed
     * deletion is finished first, so the account restarts from a clean database and never resyncs
     * what the user discarded. An intent mark means the sign-out never completed (the session is
     * alive, since the user is opening the account), so the data stays and the mark is dropped.
     */
    suspend fun reconcile(userId: String) {
        val hash = UserDatabaseManagerImpl.userHash(userId)
        if (hash in marks.confirmed()) {
            deleteByHash(hash)
            marks.clear(hash)
        } else if (hash in marks.intents()) {
            marks.clearIntent(hash)
        }
    }

    /** Finishes confirmed deletions and drops stale intents. Runs once at app start, before the root state machine. */
    suspend fun sweepPendingDeletions() {
        marks.confirmed().forEach { hash ->
            deleteByHash(hash)
            marks.clear(hash)
        }
        marks.intents().forEach { marks.clearIntent(it) }
    }

    private suspend fun deleteByHash(hash: String) {
        onLocalDataDeleted(hash)
        databases.closeAndDeleteHash(hash)
        withContext(Dispatchers.IO) {
            listOf(File(appContext.filesDir, "users/$hash"), File(appContext.cacheDir, "users/$hash")).forEach { dir ->
                if (dir.exists() && !dir.deleteRecursively()) error("Could not delete ${dir.name}")
            }
        }
    }
}
