package com.chrisgoac.gymlog.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * File-backed [UserDatabaseManager]. The file name is `gymlog-<first 32 hex chars of
 * SHA-256(userId)>.db`, so raw Clerk ids never appear on disk. Switching user closes the previous
 * database before the next one opens; deletion removes the exact files of that user's database.
 */
class UserDatabaseManagerImpl(
    context: Context,
    private val configure: (RoomDatabase.Builder<GymLogDatabase>) -> RoomDatabase.Builder<GymLogDatabase> = { it },
) : UserDatabaseManager {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val state = MutableStateFlow<OpenUserDatabase?>(null)

    override val current: StateFlow<OpenUserDatabase?> = state.asStateFlow()

    override suspend fun open(userId: String): GymLogDatabase = mutex.withLock {
        require(userId.isNotEmpty()) { "userId must not be empty" }
        state.value?.let { open -> if (open.userId == userId) return@withLock open.database }
        closeLocked()
        val database = configure(
            Room.databaseBuilder(appContext, GymLogDatabase::class.java, databaseFileName(userId)),
        ).build()
        state.value = OpenUserDatabase(userId, database)
        database
    }

    override suspend fun close() = mutex.withLock { closeLocked() }

    override suspend fun closeAndDelete(userId: String) = closeAndDeleteHash(userHash(userId))

    /** [closeAndDelete] by [userHash], for deletions finished without knowing the raw user id (startup sweep). */
    suspend fun closeAndDeleteHash(userHash: String) = mutex.withLock {
        if (state.value?.userId?.let(::userHash) == userHash) closeLocked()
        withContext(Dispatchers.IO) {
            databaseFilesForHash(userHash).forEach { file ->
                if (file.exists() && !file.delete()) error("Could not delete ${file.name}")
            }
        }
    }

    /** The exact files SQLite may create for [userId]'s database (main file, WAL, shared memory, journal). */
    fun databaseFiles(userId: String): List<File> = databaseFilesForHash(userHash(userId))

    private fun databaseFilesForHash(userHash: String): List<File> {
        val main = appContext.getDatabasePath("gymlog-$userHash.db")
        return listOf(main) + FILE_SUFFIXES.map { File(main.path + it) }
    }

    private fun closeLocked() {
        state.value?.database?.close()
        state.value = null
    }

    companion object {
        private val FILE_SUFFIXES = listOf("-wal", "-shm", "-journal")

        /** `gymlog-<first 32 hex chars of SHA-256(userId)>.db` (128 bits: collisions are not a concern). */
        fun databaseFileName(userId: String): String = "gymlog-${userHash(userId)}.db"

        /** First 32 hex chars of SHA-256(userId): the only form of a user id allowed on disk or in work names. */
        fun userHash(userId: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }
}
