package com.chrisgoac.gymlog.data.backup

import androidx.room.withTransaction
import com.chrisgoac.gymlog.data.workout.findActiveSessionId
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.MutationValidator
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.toRecord
import com.chrisgoac.gymlog.database.table
import com.chrisgoac.gymlog.database.toOperation
import com.chrisgoac.gymlog.domain.backup.BackupRepository
import com.chrisgoac.gymlog.domain.backup.ExportResult
import com.chrisgoac.gymlog.domain.backup.ImportMode
import com.chrisgoac.gymlog.domain.backup.ImportResult
import com.chrisgoac.gymlog.domain.backup.PreparedImport
import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ExercisePhotoRecord
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MAX_OPERATIONS_PER_MUTATION
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.MutationId
import com.chrisgoac.gymlog.model.ProgressPhotoRecord
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import com.chrisgoac.gymlog.model.backup.BackupCodec
import com.chrisgoac.gymlog.model.backup.BackupEntities
import com.chrisgoac.gymlog.model.backup.BackupException
import com.chrisgoac.gymlog.model.backup.BackupV1
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Backups of the open account's Room database in the PWA's file format.
 *
 * Import rules (PWA parity where it has one, see lib/repositories/backup.ts):
 * - the whole file is decoded and validated with the push rules ([MutationValidator]) before Room
 *   is touched; a file the server would reject is refused whole;
 * - whatever `userId` the file carries is ignored: rows land in the account the file was prepared
 *   for, and importing into another open account is refused; so is importing while a workout is
 *   being recorded (the active workout per [com.chrisgoac.gymlog.domain.workout.ActiveWorkoutPolicy]:
 *   a stale unfinished session does not block);
 * - a file with records dated more than [MAX_FUTURE_SKEW_MILLIS] after the server-corrected now is
 *   refused at prepare: under last-writer-wins those rows would beat every later edit;
 * - [ImportMode.Merge] never deletes: file tombstones are ignored; a live file row is written only if
 *   the row is new or the file's copy is newer (`updatedAt` strictly greater) and differs, and it
 *   keeps the file's `updatedAt` (it already beats the stored version, so the runner's LWW rule holds);
 * - [ImportMode.ReplaceAfterConfirmation], only within the tables the file has: every file row that
 *   differs is written stamped with [com.chrisgoac.gymlog.database.LocalMutationScope.nextTimestamp]
 *   (the PWA's "refresh updatedAt", so it beats the server's copy), file tombstones delete live rows,
 *   and every live row the file lacks is tombstoned; tables absent from the file are untouched;
 * - rows identical to the stored ones (timestamps aside) are never rewritten, so re-importing the
 *   same file adds no outbox mutation;
 * - everything runs in ONE Room transaction; inside it the rows go through the runner in batches of
 *   [batchSize] (each split by the runner into outbox mutations of at most 50 operations), so a
 *   failure anywhere rolls back every row and every outbox mutation.
 * After a commit [onImported] runs (see [com.chrisgoac.gymlog.domain.backup.BackupImportEffects]).
 */
class RoomBackupRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    /** Server-corrected clock: the file's `exportedAt`. */
    private val now: () -> Long,
    private val onImported: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) : BackupRepository {

    override suspend fun export(sink: OutputStream): ExportResult {
        val database = openDatabase()
        // One read transaction: a consistent snapshot even if a sync applies a page meanwhile.
        val rows = database.withTransaction { BackupCodec.TABLE_ORDER.associateWith { readAll(database, it) } }
        val entities = BackupEntities.of(rows.mapValues { (table, list) -> if (table == SyncTable.EXERCISES) list.filterNot(::isCatalog) else list })
        withContext(io) { BackupCodec.write(BackupV1(BackupCodec.CURRENT_VERSION, now(), entities), sink) }
        return ExportResult(entities.size)
    }

    override suspend fun prepare(source: InputStream): PreparedImport {
        val accountId = databases.current.value?.userId ?: throw BackupException.NoAccount()
        val decoded = withContext(io) { BackupCodec.read(source) }
        val problems = validate(decoded.backup.entities)
        if (problems.isNotEmpty()) throw BackupException.InvalidRecords(problems)
        val latestAllowed = now() + MAX_FUTURE_SKEW_MILLIS
        val future = decoded.backup.entities.byTable().sumOf { (_, rows) ->
            rows.count { it.updatedAt > latestAllowed || (it.deletedAt ?: Long.MIN_VALUE) > latestAllowed }
        }
        if (future > 0) throw BackupException.FutureTimestamps(future)
        return PreparedImport(decoded.backup, decoded.skippedCatalogExercises, accountId)
    }

    override suspend fun import(prepared: PreparedImport, mode: ImportMode): ImportResult {
        val open = databases.current.value ?: throw BackupException.NoAccount()
        if (open.userId != prepared.accountId) throw BackupException.AccountChanged()
        val database = open.database
        val runner = runnerFor(database)
        val result = database.withTransaction {
            if (database.workoutSessionDao().findActiveSessionId(now()) != null) throw BackupException.ActiveWorkout()
            val plan = plan(database, prepared.backup, mode)
            plan.writes.chunked(batchSize).forEach { batch ->
                runner.run(
                    LocalMutation {
                        batch.forEach { row ->
                            if (mode == ImportMode.Merge) {
                                put(row) // keeps the file's updatedAt, already newer than the stored one
                            } else {
                                val stamp = nextTimestamp(row.table, row.id)
                                put(row.withStamps(updatedAt = stamp, deletedAt = row.deletedAt?.let { stamp }))
                            }
                        }
                    },
                )
            }
            ImportResult(plan.written, plan.deleted, plan.unchanged, prepared.skippedCatalogExercises)
        }
        onImported()
        return result
    }

    private class Plan(val writes: List<SyncRecord>, val written: Int, val deleted: Int, val unchanged: Int)

    private suspend fun plan(database: GymLogDatabase, backup: BackupV1, mode: ImportMode): Plan {
        val writes = mutableListOf<SyncRecord>()
        var written = 0
        var deleted = 0
        var unchanged = 0
        BackupCodec.TABLE_ORDER.filter { it in backup.presentTables }.forEach { table ->
            val stored = readAll(database, table).associateBy { it.id }
            val fileRows = backup.entities.rows(table)
            fileRows.forEach { row ->
                val current = stored[row.id]
                val tombstone = row.deletedAt != null
                val write = when {
                    mode == ImportMode.Merge && tombstone -> false // merge never deletes
                    current == null -> !tombstone // nothing to delete
                    sameContent(row, current) -> false
                    tombstone && current.deletedAt != null -> false // already deleted
                    mode == ImportMode.Merge -> row.updatedAt > current.updatedAt
                    else -> true
                }
                when {
                    !write -> unchanged++
                    tombstone -> { writes += row; deleted++ }
                    else -> { writes += row; written++ }
                }
            }
            if (mode == ImportMode.ReplaceAfterConfirmation) {
                val inFile = fileRows.mapTo(HashSet()) { it.id }
                stored.values
                    .filter { it.deletedAt == null && it.id !in inFile && !isCatalog(it) }
                    .forEach { live ->
                        writes += live.withStamps(live.updatedAt, deletedAt = live.updatedAt)
                        deleted++
                    }
            }
        }
        return Plan(writes, written, deleted, unchanged)
    }

    private fun openDatabase(): GymLogDatabase = databases.current.value?.database ?: throw BackupException.NoAccount()

    private companion object {
        const val DEFAULT_BATCH_SIZE = 250

        /** Records dated later than the server-corrected now plus this are refused (clock skew allowance). */
        const val MAX_FUTURE_SKEW_MILLIS = 5 * 60 * 1000L

        /** Placeholder identity for validation only; the real mutations get theirs from the runner. */
        val VALIDATION_MUTATION_ID = MutationId("00000000-0000-0000-0000-000000000000")
        val VALIDATION_DEVICE_ID = DeviceId("backup-validation")

        private const val CATALOG_ID_PREFIX = "seed-"

        fun isCatalog(row: SyncRecord): Boolean =
            row is ExerciseRecord && (row.id.value.startsWith(CATALOG_ID_PREFIX) || !row.esPersonalizado)

        /** The server's push rules over every row, plus one row per (table, id) in the whole file. */
        fun validate(entities: BackupEntities): List<String> = buildList {
            entities.byTable().forEach { (table, rows) ->
                rows.groupingBy { it.id.value }.eachCount().filterValues { it > 1 }.keys.forEach { id ->
                    add("${table.wire}/$id appears more than once")
                }
                rows.chunked(MAX_OPERATIONS_PER_MUTATION).forEach { chunk ->
                    val mutation = PushMutation(VALIDATION_MUTATION_ID, VALIDATION_DEVICE_ID, chunk.map { it.toOperation(baseServerVersion = null) })
                    // Duplicates are reported above with the file-wide view.
                    addAll(MutationValidator.problems(mutation).filterNot { it.endsWith("duplicate (table, id)") })
                }
            }
        }

        /** Equal apart from updatedAt and the deletedAt instant (deleted-ness still counts). */
        fun sameContent(a: SyncRecord, b: SyncRecord): Boolean =
            a.withStamps(0, a.deletedAt?.let { 0 }) == b.withStamps(0, b.deletedAt?.let { 0 })

        suspend fun readAll(database: GymLogDatabase, table: SyncTable): List<SyncRecord> = when (table) {
            SyncTable.EXERCISES -> database.exerciseDao().getAll().map { it.toRecord() }
            SyncTable.ROUTINES -> database.routineDao().getAll().map { it.toRecord() }
            SyncTable.ROUTINE_EXERCISES -> database.routineExerciseDao().getAll().map { it.toRecord() }
            SyncTable.WORKOUT_SESSIONS -> database.workoutSessionDao().getAll().map { it.toRecord() }
            SyncTable.LOGGED_EXERCISES -> database.loggedExerciseDao().getAll().map { it.toRecord() }
            SyncTable.LOGGED_SETS -> database.loggedSetDao().getAll().map { it.toRecord() }
            SyncTable.GYMS -> database.gymDao().getAll().map { it.toRecord() }
            SyncTable.EXERCISE_PHOTOS -> database.exercisePhotoDao().getAll().map { it.toRecord() }
            SyncTable.USER_SETTINGS -> database.userSettingDao().getAll().map { it.toRecord() }
            SyncTable.COACH_MESSAGES -> database.coachMessageDao().getAll().map { it.toRecord() }
            SyncTable.BODY_METRICS -> database.bodyMetricDao().getAll().map { it.toRecord() }
            SyncTable.PROGRESS_PHOTOS -> database.progressPhotoDao().getAll().map { it.toRecord() }
            SyncTable.MESOCYCLES -> database.mesocycleDao().getAll().map { it.toRecord() }
            SyncTable.ACHIEVEMENTS -> database.achievementDao().getAll().map { it.toRecord() }
        }

        fun SyncRecord.withStamps(updatedAt: Long, deletedAt: Long?): SyncRecord = when (this) {
            is ExerciseRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is RoutineRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is RoutineExerciseRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is WorkoutSessionRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is LoggedExerciseRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is LoggedSetRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is GymRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is ExercisePhotoRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is UserSettingRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is CoachMessageRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is BodyMetricRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is ProgressPhotoRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is MesocycleRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
            is AchievementRecord -> copy(updatedAt = updatedAt, deletedAt = deletedAt)
        }
    }
}
