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
 * `deletedAt` set.
 *
 * A record with a PENDING outbox operation keeps its local copy only while that operation's
 * `updatedAt` is strictly greater than the pulled one (it will win LWW once pushed). Otherwise the
 * server copy is applied: the operation stays queued and the server resolves it on push, so the
 * device converges instead of holding an edit that has already lost.
 */
class PullPageApplier internal constructor(
    /** Test seam: runs after each written change, to simulate a failure mid-page. */
    private val afterChangeWritten: suspend (index: Int) -> Unit,
) {
    constructor() : this({})

    /** Must run inside a Room transaction. Returns the number of changes written. */
    suspend fun apply(database: GymLogDatabase, page: PullResponse, pending: PendingEdits = PendingEdits()): Int {
        val store = RecordStore(database)
        val localUpdatedAt = pending.refresh(database)
        var written = 0
        page.changes.forEachIndexed { index, change ->
            val local = localUpdatedAt[change.table to change.record.id.value]
            if (local != null && local > change.record.updatedAt) return@forEachIndexed
            store.put(change.record, serverVersion = change.serverVersion)
            written++
            afterChangeWritten(index)
        }
        return written
    }
}

/**
 * Highest pending `updatedAt` per (table, id), kept across the pages of one run. Outbox payloads
 * are immutable, so each one is decoded once; each page only re-reads the pending id list.
 */
class PendingEdits {
    private val decoded = HashMap<String, Map<Pair<SyncTable, String>, Long>>()

    internal suspend fun refresh(database: GymLogDatabase): Map<Pair<SyncTable, String>, Long> {
        val dao = database.syncDao()
        val ids = dao.pendingOutboxIds().toSet()
        decoded.keys.retainAll(ids)
        val missing = ids - decoded.keys
        if (missing.isNotEmpty()) {
            dao.outboxByIds(missing.toList()).forEach { row -> decoded[row.mutationId] = keysOf(row.payloadJson) }
        }
        val result = HashMap<Pair<SyncTable, String>, Long>()
        decoded.values.forEach { keys -> keys.forEach { (key, updatedAt) -> result.merge(key, updatedAt, ::maxOf) } }
        return result
    }

    /** An undecodable payload protects nothing; push quarantines it as CORRUPT_PAYLOAD. */
    private fun keysOf(payloadJson: String): Map<Pair<SyncTable, String>, Long> = try {
        ContractJson.decodeFromString(PushMutation.serializer(), payloadJson)
            .operations.associate { (it.table to it.record.id.value) to it.record.updatedAt }
    } catch (_: IllegalArgumentException) {
        emptyMap() // SerializationException is an IllegalArgumentException.
    }
}

/** One mutex per user: at most one push/pull/bootstrap touches a user's sync state at a time (spec 10.5). */
class SyncLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forUser(userId: String): Mutex = locks.getOrPut(userId) { Mutex() }
}
