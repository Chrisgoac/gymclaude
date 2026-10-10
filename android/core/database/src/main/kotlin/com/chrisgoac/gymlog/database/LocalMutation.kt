package com.chrisgoac.gymlog.database

import androidx.room.withTransaction
import com.chrisgoac.gymlog.database.entity.OutboxMutationEntity
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import com.chrisgoac.gymlog.model.AchievementOperation
import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.BodyMetricOperation
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageOperation
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ExerciseOperation
import com.chrisgoac.gymlog.model.ExercisePhotoOperation
import com.chrisgoac.gymlog.model.ExercisePhotoRecord
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.GymOperation
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseOperation
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetOperation
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MesocycleOperation
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.MutationId
import com.chrisgoac.gymlog.model.ProgressPhotoOperation
import com.chrisgoac.gymlog.model.ProgressPhotoRecord
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.RoutineExerciseOperation
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineOperation
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncOperation
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingOperation
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionOperation
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import java.util.UUID

/** What a [LocalMutation] can do inside its transaction. */
interface LocalMutationScope {
    /** Current local state of a row (tombstones included), seeing this mutation's own puts. */
    suspend fun find(table: SyncTable, id: RecordId): SyncRecord?

    /**
     * Stages a COMPLETE record (full-record replace). Deleting is putting the full record with
     * `deletedAt` set: a tombstone, never a physical delete. Callers stamp `updatedAt`.
     */
    fun put(record: SyncRecord)

    /**
     * The `updatedAt` (and `deletedAt` for a tombstone) to stamp on a write to ([table], [id]):
     * the server-corrected clock, but always strictly after the version this device already holds
     * for that row, so a device clock running behind still produces a write that wins LWW.
     */
    suspend fun nextTimestamp(table: SyncTable, id: RecordId): Long
}

/**
 * One logical user action (e.g. saving an edited workout: session + exercises + sets). Whatever
 * [block] puts becomes ONE outbox row whose operations keep the put order; [block]'s value is
 * returned by [LocalMutationRunner.run].
 */
class LocalMutation<T>(val block: suspend LocalMutationScope.() -> T)

interface LocalMutationRunner {
    suspend fun <T> run(mutation: LocalMutation<T>): T
}

/**
 * Writes the domain rows and the outbox row in ONE Room transaction (spec 10.2, 16.2): if
 * validation, a row write or the outbox insert fails, everything rolls back.
 *
 * `baseServerVersion` of each operation is the row's locally known `serverVersion`, which the
 * local write preserves (only a pull changes it). The mutation is validated with the server's
 * rules ([MutationValidator]) before anything is written.
 */
class RoomLocalMutationRunner(
    private val database: GymLogDatabase,
    private val deviceId: DeviceId,
    /** Server-corrected wall clock (device now + server offset); stamps outbox rows and [LocalMutationScope.nextTimestamp]. */
    private val clock: () -> Long = System::currentTimeMillis,
    private val newMutationId: () -> MutationId = { MutationId(UUID.randomUUID().toString()) },
) : LocalMutationRunner {
    private val store = RecordStore(database)

    override suspend fun <T> run(mutation: LocalMutation<T>): T = database.withTransaction {
        val scope = StagingScope(store, clock)
        val result = mutation.block(scope)

        val stored = scope.staged.map { record -> record to store.find(record.table, record.id) }
        // LWW: a write that does not move past the version already held would lose (or be a
        // no-op) on the server. Stamp with LocalMutationScope.nextTimestamp.
        val notNewer = stored.filter { (record, existing) -> existing != null && record.updatedAt <= existing.record.updatedAt }
        if (notNewer.isNotEmpty()) {
            throw InvalidLocalMutationException(
                notNewer.map { (record, existing) ->
                    "${record.table.wire}/${record.id.value}: updatedAt ${record.updatedAt} must be greater than the stored " +
                        "${existing!!.record.updatedAt}; stamp it with nextTimestamp"
                },
            )
        }
        val staged = stored.map { (record, existing) -> record to existing?.serverVersion }
        val pushMutation = PushMutation(
            mutationId = newMutationId(),
            deviceId = deviceId,
            operations = staged.map { (record, known) -> record.toOperation(baseServerVersion = known) },
        )
        MutationValidator.requireValid(pushMutation)

        staged.forEach { (record, known) -> store.put(record, serverVersion = known) }
        val syncDao = database.syncDao()
        syncDao.insertOutbox(
            OutboxMutationEntity(
                mutationId = pushMutation.mutationId.value,
                deviceId = deviceId.value,
                payloadJson = ContractJson.encodeToString(PushMutation.serializer(), pushMutation),
                createdAt = clock(),
                attemptCount = 0,
                lastErrorCode = null,
                sequence = syncDao.nextOutboxSequence(),
                status = OutboxStatus.PENDING,
            ),
        )
        result
    }

    private class StagingScope(private val store: RecordStore, private val clock: () -> Long) : LocalMutationScope {
        val staged = mutableListOf<SyncRecord>()

        override suspend fun find(table: SyncTable, id: RecordId): SyncRecord? =
            staged.lastOrNull { it.table == table && it.id == id } ?: store.find(table, id)?.record

        override fun put(record: SyncRecord) {
            staged += record
        }

        override suspend fun nextTimestamp(table: SyncTable, id: RecordId): Long {
            val seen = find(table, id)?.updatedAt ?: return clock()
            return maxOf(clock(), seen + 1)
        }
    }
}

/** Wraps a record in its table's push operation. */
fun SyncRecord.toOperation(baseServerVersion: Long?): SyncOperation = when (this) {
    is ExerciseRecord -> ExerciseOperation(this, baseServerVersion)
    is RoutineRecord -> RoutineOperation(this, baseServerVersion)
    is RoutineExerciseRecord -> RoutineExerciseOperation(this, baseServerVersion)
    is WorkoutSessionRecord -> WorkoutSessionOperation(this, baseServerVersion)
    is LoggedExerciseRecord -> LoggedExerciseOperation(this, baseServerVersion)
    is LoggedSetRecord -> LoggedSetOperation(this, baseServerVersion)
    is GymRecord -> GymOperation(this, baseServerVersion)
    is ExercisePhotoRecord -> ExercisePhotoOperation(this, baseServerVersion)
    is UserSettingRecord -> UserSettingOperation(this, baseServerVersion)
    is CoachMessageRecord -> CoachMessageOperation(this, baseServerVersion)
    is BodyMetricRecord -> BodyMetricOperation(this, baseServerVersion)
    is ProgressPhotoRecord -> ProgressPhotoOperation(this, baseServerVersion)
    is MesocycleRecord -> MesocycleOperation(this, baseServerVersion)
    is AchievementRecord -> AchievementOperation(this, baseServerVersion)
}
