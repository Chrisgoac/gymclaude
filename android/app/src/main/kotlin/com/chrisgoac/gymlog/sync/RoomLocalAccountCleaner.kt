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
 * `files/users/<hash>` and `cache/users/<hash>` (the places future per-user private files and
 * thumbnails must use). There is no per-user DataStore yet; when one exists its file goes here too.
 *
 * Deletion is recorded (by hash, never the raw id) in a small preferences file before the session
 * ends and cleared once the files are gone; [sweepPendingDeletions] finishes any that a crash or
 * cancellation left behind.
 */
class RoomLocalAccountCleaner(
    context: Context,
    private val databases: UserDatabaseManagerImpl,
    private val locks: SyncLocks,
) : LocalAccountCleaner {
    private val appContext = context.applicationContext
    private val marks by lazy { appContext.getSharedPreferences(MARKS_FILE, Context.MODE_PRIVATE) }

    override suspend fun unsyncedChangeCount(userId: String): Int {
        val open = databases.current.value?.takeIf { it.userId == userId }
            ?: error("The database of the signing-out account is not the open one")
        val dao = open.database.syncDao()
        return dao.outboxCount(OutboxStatus.PENDING) + dao.outboxCount(OutboxStatus.QUARANTINED)
    }

    override suspend fun markPendingDeletion(userId: String) = edit { it + UserDatabaseManagerImpl.userHash(userId) }

    override suspend fun clearPendingDeletion(userId: String) = edit { it - UserDatabaseManagerImpl.userHash(userId) }

    override suspend fun deleteLocalData(userId: String) {
        // An in-flight sync run holds this lock: wait for it before the files disappear under it.
        locks.forUser(userId).withLock {
            val hash = UserDatabaseManagerImpl.userHash(userId)
            deleteByHash(hash)
            edit { it - hash }
        }
    }

    /** Finishes deletions interrupted by a crash or cancellation. Call once at app start, before any sign-in. */
    suspend fun sweepPendingDeletions() {
        val pending = withContext(Dispatchers.IO) { marks.getStringSet(KEY, emptySet()).orEmpty().toSet() }
        pending.forEach { hash ->
            deleteByHash(hash)
            edit { it - hash }
        }
    }

    private suspend fun deleteByHash(hash: String) {
        databases.closeAndDeleteHash(hash)
        withContext(Dispatchers.IO) {
            listOf(File(appContext.filesDir, "users/$hash"), File(appContext.cacheDir, "users/$hash")).forEach { dir ->
                if (dir.exists() && !dir.deleteRecursively()) error("Could not delete ${dir.name}")
            }
        }
    }

    /** Synchronous (`commit`) so the mark is on disk before the caller proceeds. */
    private suspend fun edit(change: (Set<String>) -> Set<String>) = withContext(Dispatchers.IO) {
        synchronized(marks) {
            val updated = change(marks.getStringSet(KEY, emptySet()).orEmpty().toSet())
            check(marks.edit().putStringSet(KEY, updated).commit()) { "Could not persist the pending deletion" }
        }
    }

    private companion object {
        const val MARKS_FILE = "gymlog.pending-deletion"
        const val KEY = "hashes"
    }
}
