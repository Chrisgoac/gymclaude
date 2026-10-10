package com.chrisgoac.gymlog.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.OutboxMutationEntity
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SyncRepository
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncStatus
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.PushRequest
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * Push-then-pull convergence of the open account's database (spec 10, contracts/v1/README.md).
 *
 * Push: PENDING outbox rows in FIFO order, at most [MAX_BATCH_MUTATIONS] per request and well under
 * the 2 MiB body limit, each resent byte-for-byte (the stored payload). Only ids listed in
 * `acceptedMutationIds` are deleted. A non-retryable error naming a mutation (`details.mutationId`,
 * or a `mutations.<i>…` field path) quarantines that one mutation and the rest are resent; an
 * unattributable one falls back to one-mutation requests so the poison row identifies itself.
 * Retryable and transport failures stop the run with [SyncResult.Retryable]; 401 stops it with
 * [SyncResult.SessionRequired] without touching the outbox.
 *
 * Pull: from the stored cursor until `hasMore == false`, each page and its cursor in ONE Room
 * transaction. `INVALID_CURSOR` triggers a replacement bootstrap; an unfinished bootstrap is
 * resumed instead of pulling incrementally.
 */
class SyncRepositoryImpl(
    private val databases: UserDatabaseManager,
    private val api: SyncApi,
    private val bootstrap: BootstrapRepository,
    private val locks: SyncLocks,
    private val applier: PullPageApplier = PullPageApplier(),
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncRepository {
    /** Users with a run in progress (one per user at most; different users may overlap). */
    private val runningUsers = MutableStateFlow<Set<String>>(emptySet())

    /** Last result per user, so switching account never shows the previous account's outcome. */
    private val lastResults = MutableStateFlow<Map<String, SyncResult>>(emptyMap())

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeStatus(): Flow<SyncStatus> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(SyncStatus(running = false, pendingMutations = 0, quarantinedMutations = 0, restoreComplete = false, lastResult = null))
        } else {
            val dao = open.database.syncDao()
            combine(
                runningUsers,
                lastResults,
                dao.observeOutboxCount(OutboxStatus.PENDING),
                dao.observeOutboxCount(OutboxStatus.QUARANTINED),
                dao.observeBootstrapCheckpoint(),
            ) { running, results, pending, quarantined, checkpoint ->
                SyncStatus(
                    running = open.userId in running,
                    pendingMutations = pending,
                    quarantinedMutations = quarantined,
                    restoreComplete = checkpoint?.completed == true,
                    lastResult = results[open.userId],
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeDiagnostics(): Flow<List<OutboxDiagnostic>> = databases.current.flatMapLatest { open ->
        if (open == null) flowOf(emptyList())
        else open.database.syncDao().observeOutbox(DIAGNOSTIC_LIMIT).map { rows -> rows.map(::diagnosticOf) }
    }

    /** Ids, wire table names, counters and error codes only: never payload values. */
    private fun diagnosticOf(row: OutboxMutationEntity): OutboxDiagnostic = OutboxDiagnostic(
        mutationIdPrefix = row.mutationId.take(ID_PREFIX_LENGTH),
        tables = decodeOrNull(row)?.operations?.map { it.table.wire }?.distinct().orEmpty(),
        attemptCount = row.attemptCount,
        lastErrorCode = row.lastErrorCode,
        rejected = row.status == OutboxStatus.QUARANTINED,
    )

    override suspend fun syncOnce(): SyncResult {
        val open = databases.current.value ?: return SyncResult.SessionRequired(quarantined = 0)
        return locks.forUser(open.userId).withLock {
            runningUsers.update { it + open.userId }
            val run = RunCounters()
            try {
                val result = try {
                    push(open.database, run)
                    pull(open.database, run)
                    SyncResult.Success(pushed = run.pushed, pulled = run.pulled, quarantined = run.quarantined)
                } catch (e: SyncApiException) {
                    e.toResult(run.quarantined)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A local failure (Room/SQLite, a closed database) must not escape the worker
                    // as an unhandled crash; the transaction already rolled back.
                    Log.w(TAG, "Sync failed locally", e)
                    SyncResult.Failed(LOCAL_ERROR, run.quarantined)
                }
                lastResults.update { it + (open.userId to result) }
                result
            } finally {
                runningUsers.update { it - open.userId }
            }
        }
    }

    private class RunCounters {
        var pushed = 0
        var pulled = 0
        var quarantined = 0
        val pending = PendingEdits()
    }

    // ---- Push ----

    private suspend fun push(database: GymLogDatabase, run: RunCounters) {
        val dao = database.syncDao()
        var batchLimit = MAX_BATCH_MUTATIONS
        while (true) {
            val batch = fitBodyBudget(dao.pendingOutbox(batchLimit))
            if (batch.isEmpty()) return
            val mutations = batch.map { row -> decodeOrNull(row) }
            val corrupt = batch.filterIndexed { index, _ -> mutations[index] == null }
            if (corrupt.isNotEmpty()) {
                // Cannot be resent byte-for-byte: keep it, flag it, never let it block the queue.
                corrupt.forEach { dao.quarantineOutbox(it.mutationId, CORRUPT_PAYLOAD) }
                run.quarantined += corrupt.size
                continue
            }
            val response = try {
                api.push(PushRequest(mutations.filterNotNull()))
            } catch (e: SyncApiException.Api) {
                if (e.retryable) {
                    batch.forEach { dao.recordOutboxFailure(it.mutationId, e.errorCode()) }
                    throw e
                }
                val culprit = culpritOf(e, batch)
                when {
                    culprit != null -> {
                        dao.quarantineOutbox(culprit, e.errorCode())
                        run.quarantined++
                    }
                    // Not a per-mutation problem (e.g. INVALID_JSON, 415): quarantining would empty the outbox.
                    e.code !in PER_MUTATION_CODES -> throw e
                    // Not attributable: isolate the poison row by sending one mutation per request.
                    batch.size > 1 -> batchLimit = 1
                    else -> {
                        dao.quarantineOutbox(batch.single().mutationId, e.errorCode())
                        run.quarantined++
                    }
                }
                continue
            } catch (e: SyncApiException.Transport) {
                batch.forEach { dao.recordOutboxFailure(it.mutationId, TRANSPORT_ERROR) }
                throw e
            } catch (e: SyncApiException.Malformed) {
                batch.forEach { dao.recordOutboxFailure(it.mutationId, MALFORMED_ERROR) }
                throw e
            }
            val sent = batch.mapTo(HashSet()) { it.mutationId }
            val accepted = response.acceptedMutationIds.map { it.value }.filter { it in sent }.distinct()
            // Nothing accepted would resend the same batch forever: try again on the next run.
            if (accepted.isEmpty()) {
                batch.forEach { dao.recordOutboxFailure(it.mutationId, NONE_ACCEPTED_ERROR) }
                throw SyncApiException.Malformed("Push accepted none of ${batch.size} mutations")
            }
            dao.deleteOutbox(accepted)
            run.pushed += accepted.size
        }
    }

    private fun decodeOrNull(row: OutboxMutationEntity): PushMutation? = try {
        ContractJson.decodeFromString(PushMutation.serializer(), row.payloadJson)
    } catch (_: IllegalArgumentException) {
        null // SerializationException is an IllegalArgumentException.
    }

    /** Keeps the FIFO prefix whose encoded request stays within [PUSH_BODY_BUDGET_BYTES] (at least one row). */
    private fun fitBodyBudget(rows: List<OutboxMutationEntity>): List<OutboxMutationEntity> {
        var size = REQUEST_ENVELOPE_BYTES
        val fitted = mutableListOf<OutboxMutationEntity>()
        for (row in rows) {
            size += row.payloadJson.toByteArray(Charsets.UTF_8).size + 1
            if (fitted.isNotEmpty() && size > PUSH_BODY_BUDGET_BYTES) break
            fitted += row
        }
        return fitted
    }

    /** The batch row a non-retryable error points at: `details.mutationId`, or a `mutations.<i>…` field path. */
    private fun culpritOf(e: SyncApiException.Api, batch: List<OutboxMutationEntity>): String? {
        e.mutationId?.let { id -> if (batch.any { it.mutationId == id }) return id }
        val index = e.details?.keys?.firstNotNullOfOrNull { key -> MUTATION_PATH.find(key)?.groupValues?.get(1)?.toIntOrNull() }
        return index?.let { batch.getOrNull(it)?.mutationId }
    }

    // ---- Pull ----

    private suspend fun pull(database: GymLogDatabase, run: RunCounters) {
        val dao = database.syncDao()
        if (dao.bootstrapCheckpoint()?.completed != true) {
            run.pulled += bootstrap.resumeLocked(database)
            return
        }
        var cursor = dao.cursor()?.cursor
        do {
            val page = try {
                api.pull(cursor)
            } catch (e: SyncApiException.Api) {
                if (e.code != ApiErrorCode.INVALID_CURSOR) throw e
                run.pulled += bootstrap.restartLocked(database)
                return
            }
            database.withTransaction {
                run.pulled += applier.apply(database, page, run.pending)
                dao.saveCursor(SyncCursorEntity(cursor = page.cursor, updatedAt = clock()))
            }
            cursor = page.cursor
        } while (page.hasMore)
    }

    private fun SyncApiException.toResult(quarantined: Int): SyncResult = when (this) {
        is SyncApiException.SessionRequired -> SyncResult.SessionRequired(quarantined)
        is SyncApiException.Transport, is SyncApiException.Malformed -> SyncResult.Retryable(retryAfterMillis = null, quarantined = quarantined)
        is SyncApiException.Api ->
            if (retryable) SyncResult.Retryable(retryAfterSeconds?.let { it * 1000 }, quarantined)
            else SyncResult.Failed(errorCode(), quarantined)
    }

    private fun SyncApiException.Api.errorCode(): String = rawCode.ifEmpty { "HTTP_$httpStatus" }

    companion object {
        /** contracts/v1/README.md recommends at most 20 mutations per request. */
        const val MAX_BATCH_MUTATIONS = 20

        private const val DIAGNOSTIC_LIMIT = 200
        private const val ID_PREFIX_LENGTH = 8

        /** Half of MAX_PUSH_BODY_BYTES (2 MiB): keeps every request well under the server limit. */
        const val PUSH_BODY_BUDGET_BYTES = 1024 * 1024

        private const val REQUEST_ENVELOPE_BYTES = 16 // {"mutations":[]}
        private const val TRANSPORT_ERROR = "TRANSPORT"
        private const val MALFORMED_ERROR = "MALFORMED_RESPONSE"
        private const val NONE_ACCEPTED_ERROR = "NONE_ACCEPTED"
        const val CORRUPT_PAYLOAD = "CORRUPT_PAYLOAD"

        /** [SyncResult.Failed] code for an unexpected local (database) failure. */
        const val LOCAL_ERROR = "LOCAL_ERROR"
        private const val TAG = "GymLogSync"
        private val MUTATION_PATH = Regex("^mutations[.\\[](\\d+)")

        /** Non-retryable codes caused by one mutation's content (README "Quarantine of poison mutations", plus 413). */
        private val PER_MUTATION_CODES = setOf(
            ApiErrorCode.INVALID_REQUEST,
            ApiErrorCode.RECORD_ID_CONFLICT,
            ApiErrorCode.INVALID_REFERENCE,
            ApiErrorCode.FUTURE_TIMESTAMP,
            ApiErrorCode.UNKNOWN_TABLE,
            ApiErrorCode.PAYLOAD_TOO_LARGE,
        )
    }
}
