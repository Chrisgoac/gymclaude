package com.chrisgoac.gymlog.data.sync

import com.chrisgoac.gymlog.model.AchievementChange
import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.model.BodyMetricChange
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageChange
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ExerciseChange
import com.chrisgoac.gymlog.model.ExercisePhotoChange
import com.chrisgoac.gymlog.model.ExercisePhotoRecord
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.GymChange
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseChange
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetChange
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MAX_MUTATIONS_PER_PUSH
import com.chrisgoac.gymlog.model.MAX_OPERATIONS_PER_MUTATION
import com.chrisgoac.gymlog.model.MesocycleChange
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.ProgressPhotoChange
import com.chrisgoac.gymlog.model.ProgressPhotoRecord
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.PushRequest
import com.chrisgoac.gymlog.model.PushResponse
import com.chrisgoac.gymlog.model.ResponseJson
import com.chrisgoac.gymlog.model.RoutineChange
import com.chrisgoac.gymlog.model.RoutineExerciseChange
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncChange
import com.chrisgoac.gymlog.model.SyncConflict
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingChange
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionChange
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import com.chrisgoac.gymlog.network.SyncApi
import com.chrisgoac.gymlog.network.SyncApiException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.Base64

/**
 * In-process stand-in for `/api/v1/sync/{push,pull}` of ONE user, faithful to contracts/v1/README.md:
 *
 * - push: one transaction per request; each mutation is atomic and deduplicated by `mutationId`
 *   (a known id returns its stored conflicts and writes nothing, even if the body changed); every
 *   operation is decided by [ConflictResolver] (the Kotlin twin of the server's LWW tuple rule);
 *   an applied write gets the next global journal sequence as its `serverVersion`; any record
 *   beyond now + 5 min rejects the whole request with `FUTURE_TIMESTAMP`.
 * - pull: walks the journal after the cursor and emits a record only at its latest journal entry
 *   (older entries are skipped but advance the cursor), at most [pageSize] changes per page,
 *   cursor = base64url(decimal sequence), `INVALID_CURSOR` for anything else.
 *
 * Requests and responses go through the wire codecs (strict for requests, lenient for responses),
 * so what a device sends is exactly what a real server would parse.
 */
class FakeSyncServer(
    private val now: () -> Long,
    private val pageSize: Int = 500,
) {
    private data class Row(val record: SyncRecord, val deviceId: String, val mutationId: String, val serverVersion: Long)

    private val mutex = Mutex()
    private val rows = HashMap<Pair<SyncTable, String>, Row>()
    private val journal = mutableListOf<Pair<Long, Pair<SyncTable, String>>>()
    private var sequence = 0L
    private val receipts = HashMap<String, List<SyncConflict>>()

    /** How many times each mutation's operations were applied (dedupe => never above 1). */
    val applications = HashMap<String, Int>()

    /** How many pushed mutations were answered from a stored receipt. */
    var replays = 0
        private set

    val journalSize: Int get() = journal.size

    /** Current server copy of every record (tombstones included), keyed by (table, id). */
    suspend fun snapshot(): Map<Pair<SyncTable, String>, SyncRecord> = mutex.withLock { rows.mapValues { it.value.record } }

    suspend fun push(body: String): String = mutex.withLock {
        val request = ContractJson.decodeFromString(PushRequest.serializer(), body)
        require(request.mutations.size in 1..MAX_MUTATIONS_PER_PUSH) { "INVALID_REQUEST: mutation count" }
        require(request.mutations.map { it.mutationId }.toSet().size == request.mutations.size) { "INVALID_REQUEST: duplicate mutationId" }
        request.mutations.forEach { mutation ->
            require(mutation.operations.size in 1..MAX_OPERATIONS_PER_MUTATION) { "INVALID_REQUEST: operation count" }
            require(mutation.operations.map { it.table to it.record.id }.toSet().size == mutation.operations.size) { "INVALID_REQUEST: duplicate record" }
            val limit = now() + FUTURE_CLOCK_TOLERANCE_MS
            if (mutation.operations.any { it.record.updatedAt > limit || (it.record.deletedAt ?: Long.MIN_VALUE) > limit }) {
                throw SyncApiException.Api(400, ApiErrorCode.FUTURE_TIMESTAMP, "FUTURE_TIMESTAMP", false, null, mutation.mutationId.value, null, "future")
            }
        }
        val conflicts = mutableListOf<SyncConflict>()
        for (mutation in request.mutations) {
            val id = mutation.mutationId.value
            receipts[id]?.let { stored ->
                replays++
                conflicts += stored
                continue
            }
            val own = mutableListOf<SyncConflict>()
            for (operation in mutation.operations) {
                val key = operation.table to operation.record.id.value
                val existing = rows[key]
                val decision = ConflictResolver.decide(
                    existing?.let { StoredVersion(it.record.updatedAt, it.deviceId, it.mutationId, it.serverVersion) },
                    VersionTuple(operation.record.updatedAt, mutation.deviceId.value, id),
                    operation.baseServerVersion,
                )
                val version = if (decision.apply) {
                    val next = ++sequence
                    rows[key] = Row(operation.record, mutation.deviceId.value, id, next)
                    journal += next to key
                    next
                } else {
                    existing?.serverVersion ?: 0L
                }
                decision.conflict?.let { reason ->
                    own += SyncConflict(mutation.mutationId, operation.table, operation.record.id, version, reason.wire)
                }
            }
            receipts[id] = own
            applications.merge(id, 1, Int::plus)
            conflicts += own
        }
        ContractJson.encodeToString(PushResponse.serializer(), PushResponse(request.mutations.map { it.mutationId }, conflicts))
    }

    suspend fun pull(cursor: String?, limit: Int): String = mutex.withLock {
        val after = if (cursor == null) 0L else decodeCursor(cursor)
        val changes = mutableListOf<SyncChange>()
        var examined = after
        for ((seq, key) in journal) {
            if (seq <= after) continue
            if (changes.size >= minOf(limit, pageSize)) break
            examined = seq
            val row = rows.getValue(key)
            if (row.serverVersion == seq) changes += changeOf(row.record, seq)
        }
        val hasMore = journal.any { it.first > examined }
        ContractJson.encodeToString(PullResponse.serializer(), PullResponse(changes, encodeCursor(examined), hasMore))
    }

    private fun decodeCursor(cursor: String): Long {
        val text = runCatching { String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8) }.getOrNull()
        return text?.takeIf { CANONICAL.matches(it) }?.toLongOrNull()
            ?: throw SyncApiException.Api(400, ApiErrorCode.INVALID_CURSOR, "INVALID_CURSOR", false, null, null, null, "bad cursor")
    }

    private fun encodeCursor(sequence: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(sequence.toString().toByteArray(Charsets.UTF_8))

    /** A device's connection to this server: the same [SyncApi] seam the app's Ktor client implements. */
    inner class Link(private val userId: String) : SyncApi {
        var online = false

        /** The next push reaches the server and is applied, but its response is lost (timeout). */
        var loseNextPushResponse = false

        /** Exact request bodies this device sent, in order. */
        val pushBodies = mutableListOf<String>()

        override suspend fun push(expectedUserId: String, request: PushRequest): PushResponse {
            check(expectedUserId == userId) { "push for $expectedUserId on $userId's link" }
            if (!online) throw SyncApiException.Transport(IOException("offline"))
            val body = ContractJson.encodeToString(PushRequest.serializer(), request)
            pushBodies += body
            val response = this@FakeSyncServer.push(body)
            if (loseNextPushResponse) {
                loseNextPushResponse = false
                throw SyncApiException.Transport(IOException("response lost"))
            }
            return ResponseJson.decodeFromString(PushResponse.serializer(), response)
        }

        override suspend fun pull(expectedUserId: String, cursor: String?, limit: Int): PullResponse {
            check(expectedUserId == userId) { "pull for $expectedUserId on $userId's link" }
            if (!online) throw SyncApiException.Transport(IOException("offline"))
            return ResponseJson.decodeFromString(PullResponse.serializer(), this@FakeSyncServer.pull(cursor, limit))
        }
    }

    companion object {
        const val FUTURE_CLOCK_TOLERANCE_MS = 300_000L
        private val CANONICAL = Regex("^(0|[1-9][0-9]{0,18})$")

        fun changeOf(record: SyncRecord, serverVersion: Long): SyncChange = when (record) {
            is ExerciseRecord -> ExerciseChange(record, serverVersion)
            is RoutineRecord -> RoutineChange(record, serverVersion)
            is RoutineExerciseRecord -> RoutineExerciseChange(record, serverVersion)
            is WorkoutSessionRecord -> WorkoutSessionChange(record, serverVersion)
            is LoggedExerciseRecord -> LoggedExerciseChange(record, serverVersion)
            is LoggedSetRecord -> LoggedSetChange(record, serverVersion)
            is GymRecord -> GymChange(record, serverVersion)
            is ExercisePhotoRecord -> ExercisePhotoChange(record, serverVersion)
            is UserSettingRecord -> UserSettingChange(record, serverVersion)
            is CoachMessageRecord -> CoachMessageChange(record, serverVersion)
            is BodyMetricRecord -> BodyMetricChange(record, serverVersion)
            is ProgressPhotoRecord -> ProgressPhotoChange(record, serverVersion)
            is MesocycleRecord -> MesocycleChange(record, serverVersion)
            is AchievementRecord -> AchievementChange(record, serverVersion)
        }
    }
}
