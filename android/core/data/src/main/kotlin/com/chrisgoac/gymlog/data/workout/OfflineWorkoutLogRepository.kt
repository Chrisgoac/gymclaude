package com.chrisgoac.gymlog.data.workout

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.LocalMutationScope
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.toRecord
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotFoundException
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.workout.ExerciseHistory
import com.chrisgoac.gymlog.domain.workout.HistorySet
import com.chrisgoac.gymlog.domain.workout.ProgressionSettings
import com.chrisgoac.gymlog.domain.workout.SetChange
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.WorkoutLimits
import com.chrisgoac.gymlog.domain.workout.WorkoutLogRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutRowMissingException
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Set logging over the open account's Room database. Each call is ONE [LocalMutation]; positions
 * are rewritten as compact 0..n-1 on the existing stable rows (only changed rows are written) and
 * removals are tombstones. Values are validated before the transaction starts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineWorkoutLogRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    /** Catalog + custom exercises: an added exercise must exist (catalog rows are not in the sync tables). */
    private val exercises: ExerciseRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : WorkoutLogRepository {

    // ---- Reads ----

    override fun observeExerciseHistory(
        exerciseIds: Set<String>,
        excludeSessionId: String,
        gymId: String?,
    ): Flow<Map<String, ExerciseHistory>> = if (exerciseIds.isEmpty()) {
        flowOf(emptyMap())
    } else {
        databases.current.flatMapLatest { open ->
            if (open == null) {
                flowOf(emptyMap())
            } else {
                // Scoped query: only the latest earlier logged exercise per id and its sets are read,
                // so re-running it after an edit of the current session stays cheap.
                open.database.workoutHistoryDao()
                    .observeLatestHistory(exerciseIds.sorted(), excludeSessionId, gymId)
                    .map { rows ->
                        rows.groupBy { it.exerciseId }.mapValues { (_, sets) ->
                            ExerciseHistory(sets.first().fecha, sets.map { HistorySet(it.peso, it.reps, it.esCalentamiento == true) })
                        }
                    }
            }
        }.distinctUntilChanged()
    }

    override fun observeProgressionSettings(): Flow<ProgressionSettings> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(ProgressionSettings())
        } else {
            open.database.userSettingDao().observeLive().map { rows ->
                ProgressionSettings.fromStored(
                    mode = rows.firstOrNull { it.id == ProgressionSettings.MODE_KEY }?.valor,
                    increments = rows.firstOrNull { it.id == ProgressionSettings.INCREMENTS_KEY }?.valor,
                )
            }
        }
    }.distinctUntilChanged()

    // ---- Writes ----

    override suspend fun addExercise(sessionId: String, exerciseId: String): String {
        // Checked before the transaction: the catalog is a separate local table read through the repository.
        exercises.observeExercise(exerciseId).first() ?: throw ExerciseNotFoundException(exerciseId)
        return addLoggedExercise(sessionId, exerciseId)
    }

    private suspend fun addLoggedExercise(sessionId: String, exerciseId: String): String = mutate { database ->
        liveSession(sessionId)
        val siblings = database.loggedExerciseDao().liveForSession(sessionId)
        val id = RecordId(newId())
        put(
            LoggedExerciseRecord(
                id = id,
                updatedAt = nextTimestamp(SyncTable.LOGGED_EXERCISES, id),
                deletedAt = null,
                sessionId = sessionId,
                exerciseId = exerciseId,
                orden = (siblings.maxOfOrNull { it.orden } ?: -1) + 1,
            ),
        )
        id.value
    }

    override suspend fun removeExercise(loggedExerciseId: String) = mutate { database ->
        val row = liveLoggedExercise(loggedExerciseId) ?: return@mutate
        tombstone(row)
        database.loggedSetDao().liveForExercise(loggedExerciseId).forEach { tombstone(it.toRecord()) }
        compactExercises(database.loggedExerciseDao().liveForSession(row.sessionId).filterNot { it.id == loggedExerciseId })
    }

    override suspend fun moveExercise(loggedExerciseId: String, delta: Int) = mutate { database ->
        val row = liveLoggedExercise(loggedExerciseId) ?: return@mutate
        compactExercises(moved(database.loggedExerciseDao().liveForSession(row.sessionId), { it.id == loggedExerciseId }, delta))
    }

    override suspend fun addSet(loggedExerciseId: String, weight: Double, reps: Int, warmup: Boolean): String {
        require(SetValidation.isStorable(weight, reps)) { "Invalid set $weight x $reps" }
        return mutate { database ->
            val parent = liveLoggedExercise(loggedExerciseId) ?: throw WorkoutRowMissingException(loggedExerciseId)
            liveSession(parent.sessionId)
            val siblings = database.loggedSetDao().liveForExercise(loggedExerciseId)
            val id = RecordId(newId())
            put(
                LoggedSetRecord(
                    id = id,
                    updatedAt = nextTimestamp(SyncTable.LOGGED_SETS, id),
                    deletedAt = null,
                    loggedExerciseId = loggedExerciseId,
                    orden = (siblings.maxOfOrNull { it.orden } ?: -1) + 1,
                    peso = weight,
                    reps = reps,
                    esCalentamiento = warmup,
                ),
            )
            id.value
        }
    }

    override suspend fun updateSet(setId: String, change: SetChange) {
        change.weight?.let { require(SetValidation.isStorable(it, 0)) { "Invalid weight $it" } }
        change.reps?.let { require(SetValidation.isStorable(0.0, it)) { "Invalid reps $it" } }
        if (change == SetChange()) return
        mutate {
            val set = liveSet(setId) ?: return@mutate
            val next = set.copy(
                peso = change.weight ?: set.peso,
                reps = change.reps ?: set.reps,
                esCalentamiento = change.warmup ?: set.esCalentamiento,
            )
            // A missing warm-up flag (older clients) means "not a warm-up": false == null here.
            val unchanged = next.peso == set.peso && next.reps == set.reps && (next.esCalentamiento == true) == (set.esCalentamiento == true)
            if (unchanged) return@mutate
            put(next.copy(updatedAt = nextTimestamp(SyncTable.LOGGED_SETS, next.id)))
        }
    }

    override suspend fun removeSet(setId: String) = mutate { database ->
        val set = liveSet(setId) ?: return@mutate
        tombstone(set)
        compactSets(database.loggedSetDao().liveForExercise(set.loggedExerciseId).filterNot { it.id == setId })
    }

    override suspend fun moveSet(setId: String, delta: Int) = mutate { database ->
        val set = liveSet(setId) ?: return@mutate
        compactSets(moved(database.loggedSetDao().liveForExercise(set.loggedExerciseId), { it.id == setId }, delta))
    }

    override suspend fun updateNotes(sessionId: String, notes: String) {
        val cleaned = notes.replace("\u0000", "").trim()
        require(cleaned.length <= WorkoutLimits.NOTES_MAX) { "Notes over ${WorkoutLimits.NOTES_MAX} characters" }
        mutate {
            val session = liveSession(sessionId)
            val value = cleaned.ifEmpty { null }
            if (session.notas == value) return@mutate
            put(session.copy(notas = value, updatedAt = nextTimestamp(SyncTable.WORKOUT_SESSIONS, session.id)))
        }
    }

    // ---- Helpers (inside the mutation) ----

    private suspend fun <T> mutate(block: suspend LocalMutationScope.(GymLogDatabase) -> T): T {
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(LocalMutation { block(database) })
    }

    private suspend fun LocalMutationScope.liveSession(id: String): WorkoutSessionRecord =
        (find(SyncTable.WORKOUT_SESSIONS, RecordId(id)) as? WorkoutSessionRecord)?.takeIf { it.deletedAt == null }
            ?: throw WorkoutRowMissingException(id)

    private suspend fun LocalMutationScope.liveLoggedExercise(id: String): LoggedExerciseRecord? =
        (find(SyncTable.LOGGED_EXERCISES, RecordId(id)) as? LoggedExerciseRecord)?.takeIf { it.deletedAt == null }

    private suspend fun LocalMutationScope.liveSet(id: String): LoggedSetRecord? =
        (find(SyncTable.LOGGED_SETS, RecordId(id)) as? LoggedSetRecord)?.takeIf { it.deletedAt == null }

    private suspend fun LocalMutationScope.tombstone(row: LoggedExerciseRecord) {
        val stamp = nextTimestamp(SyncTable.LOGGED_EXERCISES, row.id)
        put(row.copy(updatedAt = stamp, deletedAt = stamp))
    }

    private suspend fun LocalMutationScope.tombstone(row: LoggedSetRecord) {
        val stamp = nextTimestamp(SyncTable.LOGGED_SETS, row.id)
        put(row.copy(updatedAt = stamp, deletedAt = stamp))
    }

    /** Rewrites [ordered] (already in the wanted order) as positions 0..n-1, writing only changed rows. */
    private suspend fun LocalMutationScope.compactExercises(ordered: List<LoggedExerciseEntity>) {
        ordered.forEachIndexed { index, row ->
            if (row.orden != index) put(row.toRecord().copy(orden = index, updatedAt = nextTimestamp(SyncTable.LOGGED_EXERCISES, RecordId(row.id))))
        }
    }

    private suspend fun LocalMutationScope.compactSets(ordered: List<LoggedSetEntity>) {
        ordered.forEachIndexed { index, row ->
            if (row.orden != index) put(row.toRecord().copy(orden = index, updatedAt = nextTimestamp(SyncTable.LOGGED_SETS, RecordId(row.id))))
        }
    }

    /** [rows] with the one matching [target] moved by [delta] places, clamped to the list. */
    private fun <R> moved(rows: List<R>, target: (R) -> Boolean, delta: Int): List<R> {
        val index = rows.indexOfFirst(target)
        if (index < 0) return rows
        val destination = (index + delta).coerceIn(0, rows.lastIndex)
        return rows.toMutableList().apply { add(destination, removeAt(index)) }
    }
}
