package com.chrisgoac.gymlog.data.sync

import androidx.room.withTransaction
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.dao.SyncDao
import com.chrisgoac.gymlog.database.entity.BootstrapCheckpointEntity
import com.chrisgoac.gymlog.database.entity.BootstrapState
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.sync.withLock

/**
 * Downloads the whole cloud copy of an account into its database (spec 10.6): a pull from an empty
 * cursor, page by page, until `hasMore == false`.
 *
 * Resumable: each page is applied in the same transaction as the checkpoint that records its
 * cursor, so after process death the download continues after the last fully applied page. The
 * checkpoint stays [BootstrapState.IN_PROGRESS] until the last page, whose transaction also seeds
 * the incremental pull cursor and marks it [BootstrapState.COMPLETED]: partial data is never
 * presented as a finished restore.
 */
class BootstrapRepository(
    private val api: SyncApi,
    private val locks: SyncLocks,
    private val applier: PullPageApplier = PullPageApplier(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Finishes [userId]'s bootstrap (no-op if already completed). [onProgress] gets 0..1; the total
     * page count is unknown, so progress approaches 1 and reaches it on the last page.
     * Throws [com.chrisgoac.gymlog.network.SyncApiException] on network or server failure; the
     * checkpoint keeps every page applied so far.
     */
    suspend fun run(userId: String, database: GymLogDatabase, onProgress: (Float) -> Unit = {}) {
        locks.forUser(userId).withLock { resumeLocked(database, onProgress) }
    }

    /** Caller holds the user's sync lock. Returns the number of changes written. */
    internal suspend fun resumeLocked(database: GymLogDatabase, onProgress: (Float) -> Unit = {}): Int {
        val dao = database.syncDao()
        var checkpoint = dao.bootstrapCheckpoint()
        if (checkpoint?.completed == true) {
            onProgress(1f)
            return 0
        }
        if (checkpoint == null) {
            checkpoint = BootstrapCheckpointEntity(state = BootstrapState.IN_PROGRESS, lastCursor = null, pagesApplied = 0, updatedAt = clock())
            dao.saveBootstrapCheckpoint(checkpoint)
        }
        var cursor = checkpoint.lastCursor
        var pages = checkpoint.pagesApplied
        var written = 0
        val pending = PendingEdits()
        onProgress(progress(pages))
        while (true) {
            val page = try {
                api.pull(cursor)
            } catch (e: SyncApiException.Api) {
                // The checkpoint cursor is no longer valid: start the download over.
                if (e.code != ApiErrorCode.INVALID_CURSOR || cursor == null) throw e
                resetCheckpoint(dao)
                cursor = null
                pages = 0
                onProgress(0f)
                continue
            }
            val done = !page.hasMore
            database.withTransaction {
                written += applier.apply(database, page, pending)
                val now = clock()
                dao.saveBootstrapCheckpoint(
                    BootstrapCheckpointEntity(
                        state = if (done) BootstrapState.COMPLETED else BootstrapState.IN_PROGRESS,
                        lastCursor = page.cursor,
                        pagesApplied = pages + 1,
                        updatedAt = now,
                    ),
                )
                if (done) dao.saveCursor(SyncCursorEntity(cursor = page.cursor, updatedAt = now))
            }
            pages++
            cursor = page.cursor
            onProgress(if (done) 1f else progress(pages))
            if (done) return written
        }
    }

    /**
     * Restarts the download from an empty cursor (pull answered `INVALID_CURSOR`). Existing rows
     * are kept and overwritten page by page; the account counts as restored only after the new
     * download's last page. Caller holds the user's sync lock.
     */
    internal suspend fun restartLocked(database: GymLogDatabase): Int {
        resetCheckpoint(database.syncDao())
        return resumeLocked(database)
    }

    private suspend fun resetCheckpoint(dao: SyncDao) {
        dao.saveBootstrapCheckpoint(
            BootstrapCheckpointEntity(state = BootstrapState.IN_PROGRESS, lastCursor = null, pagesApplied = 0, updatedAt = clock()),
        )
    }

    private fun progress(pagesApplied: Int): Float = pagesApplied / (pagesApplied + 1f)
}
