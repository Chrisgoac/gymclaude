package com.chrisgoac.gymlog.data.sync

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.RecordStore
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes pulled changes into Room. Callers wrap [apply] and their cursor/checkpoint write in ONE
 * transaction, so a page and its cursor land together or not at all.
 *
 * Explicit DTO -> entity mapping goes through [RecordStore] (core:database mappers). Every change
 * is a full-record replace stamped with the server's `serverVersion`; tombstones are rows with
 * `deletedAt` set. A record that still has a PENDING outbox operation is skipped: the unsynced
 * local edit wins locally until it is pushed, and the server's LWW decision arrives on a later pull.
 */
class PullPageApplier internal constructor(
    /** Test seam: runs after each written change, to simulate a failure mid-page. */
    private val afterChangeWritten: suspend (index: Int) -> Unit,
) {
    constructor() : this({})

    /** Must run inside a Room transaction. Returns the number of changes written. */
    suspend fun apply(database: GymLogDatabase, page: PullResponse): Int {
        val store = RecordStore(database)
        val locallyPending = pendingRecordKeys(database)
        var written = 0
        page.changes.forEachIndexed { index, change ->
            if ((change.table to change.record.id.value) in locallyPending) return@forEachIndexed
            store.put(change.record, serverVersion = change.serverVersion)
            written++
            afterChangeWritten(index)
        }
        return written
    }

    private suspend fun pendingRecordKeys(database: GymLogDatabase): Set<Pair<SyncTable, String>> =
        database.syncDao().pendingOutbox(Int.MAX_VALUE).flatMapTo(HashSet()) { row ->
            ContractJson.decodeFromString(PushMutation.serializer(), row.payloadJson)
                .operations.map { it.table to it.record.id.value }
        }
}

/** One mutex per user: at most one push/pull/bootstrap touches a user's sync state at a time (spec 10.5). */
class SyncLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forUser(userId: String): Mutex = locks.getOrPut(userId) { Mutex() }
}
