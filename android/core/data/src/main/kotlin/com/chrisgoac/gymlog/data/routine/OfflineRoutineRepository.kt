package com.chrisgoac.gymlog.data.routine

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.RoutineEntity
import com.chrisgoac.gymlog.database.entity.RoutineExerciseEntity
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineExercise
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseConflictException
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineNotFoundException
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.routine.RoutineValidation
import com.chrisgoac.gymlog.domain.routine.RoutineValidationException
import com.chrisgoac.gymlog.domain.routine.RoutineValidator
import com.chrisgoac.gymlog.domain.routine.ValidRoutine
import com.chrisgoac.gymlog.domain.routine.ValidRoutineExercise
import com.chrisgoac.gymlog.domain.routine.nextRoutineId
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Routines over the open account's Room database. Every write is ONE [LocalMutation] (routine row
 * + its exercise rows + outbox): reorder rewrites `orden` on the existing stable rows, removing
 * an exercise or deleting a routine writes tombstones (`deletedAt`), never physical deletes.
 * Writes only touch rows whose content actually changes, so a no-op save writes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRoutineRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    private val exercises: ExerciseRepository,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : RoutineRepository {

    // ---- Reads ----

    private class Snapshot(val routines: List<RoutineEntity>, val children: Map<String?, List<RoutineExerciseEntity>>, val names: Map<String, String>)

    private fun snapshots(): Flow<Snapshot> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(Snapshot(emptyList(), emptyMap(), emptyMap()))
        } else {
            combine(
                open.database.routineDao().observeLive(),
                open.database.routineExerciseDao().observeLive(),
                exercises.observeExercises().map { all -> all.associate { it.id to it.name } },
            ) { routines, children, names ->
                Snapshot(
                    routines = routines.sortedWith(ROUTINE_ORDER),
                    children = children.groupBy { it.routineId }.mapValues { (_, rows) -> rows.sortedWith(CHILD_ORDER) },
                    names = names,
                )
            }
        }
    }

    override fun observeRoutines(): Flow<List<RoutineSummary>> = snapshots().map { snapshot ->
        snapshot.routines.map { routine ->
            val rows = snapshot.children[routine.id].orEmpty()
            RoutineSummary(
                id = routine.id,
                name = routine.nombre,
                description = routine.descripcion,
                archived = routine.archivada,
                exerciseCount = rows.size,
                exercisePreview = rows.take(PREVIEW_SIZE).mapNotNull { snapshot.names[it.exerciseId] },
            )
        }
    }

    override fun observeRoutine(id: String): Flow<Routine?> = snapshots().map { snapshot ->
        snapshot.routines.firstOrNull { it.id == id }?.let { routine ->
            Routine(
                id = routine.id,
                name = routine.nombre,
                description = routine.descripcion,
                archived = routine.archivada,
                exercises = snapshot.children[routine.id].orEmpty().mapIndexed { index, row ->
                    RoutineExercise(
                        id = row.id,
                        exerciseId = row.exerciseId,
                        exerciseName = snapshot.names[row.exerciseId],
                        position = index,
                        sets = row.seriesObjetivo,
                        repsMin = row.repsObjetivoMin,
                        repsMax = row.repsObjetivo,
                        restSeconds = row.descansoSegundos,
                        notes = row.notas,
                    )
                },
            )
        }
    }.distinctUntilChanged()

    override fun observeNextRoutineId(): Flow<String?> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(null)
        } else {
            combine(open.database.routineDao().observeLive(), open.database.workoutSessionDao().observeLive()) { routines, sessions ->
                // Sessions are newest first; the last routine is the one of the latest session started from a routine.
                val last = sessions.firstOrNull { it.routineId != null }?.routineId
                nextRoutineId(routines.filter { !it.archivada }.sortedWith(ROUTINE_ORDER).map { it.id }, last)
            }
        }
    }.distinctUntilChanged()

    // ---- Writes ----

    override suspend fun create(input: RoutineInput): Routine {
        val valid = validate(input)
        val database = openDatabase()
        val routineId = RecordId(newId())
        val position = database.routineDao().observeLive().first().size
        runnerFor(database).run(
            LocalMutation {
                put(
                    RoutineRecord(
                        id = routineId,
                        updatedAt = nextTimestamp(SyncTable.ROUTINES, routineId),
                        deletedAt = null,
                        nombre = valid.name,
                        descripcion = valid.description,
                        orden = position,
                        archivada = false,
                    ),
                )
                valid.exercises.forEachIndexed { index, exercise ->
                    val childId = RecordId(exercise.key)
                    // A new routine can only own new rows: reusing any existing id would overwrite someone else's row.
                    if (find(SyncTable.ROUTINE_EXERCISES, childId) != null) throw RoutineExerciseConflictException(exercise.key)
                    put(exercise.toRecord(childId, routineId.value, index, nextTimestamp(SyncTable.ROUTINE_EXERCISES, childId)))
                }
            },
        )
        return checkNotNull(observeRoutine(routineId.value).first()) { "Routine ${routineId.value} was just created" }
    }

    override suspend fun update(id: String, input: RoutineInput): Routine {
        val valid = validate(input)
        val database = openDatabase()
        val routineId = RecordId(id)
        val liveChildIds = liveChildren(database, id).map { it.id }
        runnerFor(database).run(
            LocalMutation {
                val routine = requireLive(find(SyncTable.ROUTINES, routineId), id)
                if (routine.nombre != valid.name || routine.descripcion != valid.description) {
                    put(
                        routine.copy(
                            updatedAt = nextTimestamp(SyncTable.ROUTINES, routineId),
                            nombre = valid.name,
                            descripcion = valid.description,
                        ),
                    )
                }
                val keep = valid.exercises.map { it.key }.toSet()
                valid.exercises.forEachIndexed { index, exercise ->
                    val childId = RecordId(exercise.key)
                    when (val current = find(SyncTable.ROUTINE_EXERCISES, childId)) {
                        null -> put(exercise.toRecord(childId, id, index, nextTimestamp(SyncTable.ROUTINE_EXERCISES, childId)))
                        is RoutineExerciseRecord -> {
                            if (current.deletedAt != null || current.routineId != id) throw RoutineExerciseConflictException(exercise.key)
                            val next = exercise.toRecord(childId, id, index, current.updatedAt)
                            if (next != current) put(next.copy(updatedAt = nextTimestamp(SyncTable.ROUTINE_EXERCISES, childId)))
                        }
                        else -> throw RoutineExerciseConflictException(exercise.key)
                    }
                }
                liveChildIds.filter { it !in keep }.forEach { removed -> tombstone(SyncTable.ROUTINE_EXERCISES, RecordId(removed)) }
            },
        )
        return checkNotNull(observeRoutine(id).first()) { "Routine $id disappeared after saving" }
    }

    override suspend fun reorder(idsInOrder: List<String>) {
        val database = openDatabase()
        val current = database.routineDao().observeLive().first().sortedWith(ROUTINE_ORDER).map { it.id }
        val live = current.toSet()
        val requested = idsInOrder.filter { it in live }.distinct()
        val sequence = requested + current.filter { it !in requested }
        runnerFor(database).run(
            LocalMutation {
                sequence.forEachIndexed { index, id ->
                    val routineId = RecordId(id)
                    val routine = find(SyncTable.ROUTINES, routineId) as? RoutineRecord ?: return@forEachIndexed
                    if (routine.deletedAt == null && routine.orden != index) {
                        put(routine.copy(updatedAt = nextTimestamp(SyncTable.ROUTINES, routineId), orden = index))
                    }
                }
            },
        )
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        val database = openDatabase()
        val routineId = RecordId(id)
        runnerFor(database).run(
            LocalMutation {
                val routine = requireLive(find(SyncTable.ROUTINES, routineId), id)
                if (routine.archivada != archived) {
                    put(routine.copy(updatedAt = nextTimestamp(SyncTable.ROUTINES, routineId), archivada = archived))
                }
            },
        )
    }

    override suspend fun delete(id: String) {
        val database = openDatabase()
        val routineId = RecordId(id)
        val childIds = liveChildren(database, id).map { it.id }
        runnerFor(database).run(
            LocalMutation {
                requireLive(find(SyncTable.ROUTINES, routineId), id)
                tombstone(SyncTable.ROUTINES, routineId)
                childIds.forEach { tombstone(SyncTable.ROUTINE_EXERCISES, RecordId(it)) }
            },
        )
    }

    // ---- Helpers ----

    private suspend fun com.chrisgoac.gymlog.database.LocalMutationScope.tombstone(table: SyncTable, id: RecordId) {
        val record = find(table, id) ?: return
        if (record.deletedAt != null) return
        val stamp = nextTimestamp(table, id)
        when (record) {
            is RoutineRecord -> put(record.copy(updatedAt = stamp, deletedAt = stamp))
            is RoutineExerciseRecord -> put(record.copy(updatedAt = stamp, deletedAt = stamp))
            else -> error("Not a routine table: $table")
        }
    }

    private suspend fun liveChildren(database: GymLogDatabase, routineId: String): List<RoutineExerciseEntity> =
        database.routineExerciseDao().observeLive().first().filter { it.routineId == routineId }

    private fun validate(input: RoutineInput): ValidRoutine = when (val result = RoutineValidator.validate(input)) {
        is RoutineValidation.Valid -> result.value
        is RoutineValidation.Invalid -> throw RoutineValidationException(result.issues)
    }

    private fun openDatabase(): GymLogDatabase = checkNotNull(databases.current.value) { "No account database is open" }.database

    private fun requireLive(record: SyncRecord?, id: String): RoutineRecord {
        val routine = record as? RoutineRecord
        if (routine == null || routine.deletedAt != null) throw RoutineNotFoundException(id)
        return routine
    }

    private companion object {
        const val PREVIEW_SIZE = 3

        // Same order as the PWA: manual position (missing = 0), then name, then id as a stable tiebreak.
        val ROUTINE_ORDER = compareBy<RoutineEntity> { it.orden ?: 0 }.thenBy { it.nombre.lowercase() }.thenBy { it.id }
        val CHILD_ORDER = compareBy<RoutineExerciseEntity> { it.orden }.thenBy { it.id }
    }
}

private fun ValidRoutineExercise.toRecord(id: RecordId, routineId: String, position: Int, updatedAt: Long) = RoutineExerciseRecord(
    id = id,
    updatedAt = updatedAt,
    deletedAt = null,
    routineId = routineId,
    exerciseId = exerciseId,
    orden = position,
    seriesObjetivo = sets,
    repsObjetivo = repsMax,
    repsObjetivoMin = repsMin,
    descansoSegundos = restSeconds,
    notas = notes,
)
