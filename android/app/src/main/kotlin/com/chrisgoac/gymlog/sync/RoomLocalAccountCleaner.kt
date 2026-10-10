package com.chrisgoac.gymlog.sync

import android.content.Context
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import com.chrisgoac.gymlog.domain.LocalAccountCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Counts and deletes one account's local data. Besides the Room files it removes
 * `files/users/<hash>` and `cache/users/<hash>` (the places future per-user private files and
 * thumbnails must use). There is no per-user DataStore yet; when one exists its file goes here too.
 */
class RoomLocalAccountCleaner(
    context: Context,
    private val databases: UserDatabaseManager,
) : LocalAccountCleaner {
    private val appContext = context.applicationContext

    override suspend fun unsyncedChangeCount(userId: String): Int {
        val database = databases.current.value?.takeIf { it.userId == userId }?.database ?: databases.open(userId)
        val dao = database.syncDao()
        return dao.outboxCount(OutboxStatus.PENDING) + dao.outboxCount(OutboxStatus.QUARANTINED)
    }

    override suspend fun deleteLocalData(userId: String) {
        databases.closeAndDelete(userId)
        val hash = UserDatabaseManagerImpl.userHash(userId)
        withContext(Dispatchers.IO) {
            listOf(File(appContext.filesDir, "users/$hash"), File(appContext.cacheDir, "users/$hash")).forEach { dir ->
                if (dir.exists() && !dir.deleteRecursively()) error("Could not delete ${dir.name}")
            }
        }
    }
}
