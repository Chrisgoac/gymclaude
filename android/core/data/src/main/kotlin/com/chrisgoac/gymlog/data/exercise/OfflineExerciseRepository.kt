package com.chrisgoac.gymlog.data.exercise

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotEditableException
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotFoundException
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.ExerciseValidation
import com.chrisgoac.gymlog.domain.exercise.ExerciseValidationException
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.exercise.ValidExercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseValidator
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Exercises over the open account's Room database: the local-only `catalog_exercises` table
 * (read-only, never synced) merged with the synchronized `exercises` table restricted to the
 * user's own exercises (`esPersonalizado`, not tombstoned).
 *
 * Reads are Room Flows, so the list refreshes by itself if the catalog import lands after the
 * database was published. Writes go through [LocalMutationRunner] (one transaction: row + outbox
 * row) stamped with `nextTimestamp`, so they never wait for the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineExerciseRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ExerciseRepository {

    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = allExercises().map { all ->
        all.filter(filter::matches)
    }

    override fun observeExercise(id: String): Flow<Exercise?> = allExercises().map { all -> all.firstOrNull { it.id == id } }
        .distinctUntilChanged()

    private fun allExercises(): Flow<List<Exercise>> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(emptyList())
        } else {
            combine(open.database.catalogDao().observeAll(), open.database.exerciseDao().observeLive()) { catalog, synced ->
                (catalog.map(CatalogExerciseEntity::toDomain) + synced.filter { it.esPersonalizado }.map(ExerciseEntity::toDomain))
                    .sortedWith(compareBy<Exercise> { it.name.lowercase() }.thenBy { it.id })
            }
        }
    }

    override suspend fun createCustom(input: ExerciseInput): Exercise {
        val valid = validate(input)
        val database = openDatabase()
        val id = RecordId(newId())
        return runnerFor(database).run(
            LocalMutation {
                val record = valid.toRecord(id, updatedAt = nextTimestamp(SyncTable.EXERCISES, id), deletedAt = null)
                put(record)
                record.toDomain()
            },
        )
    }

    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise {
        val valid = validate(input)
        val database = openDatabase()
        rejectCatalog(database, id)
        val recordId = RecordId(id)
        return runnerFor(database).run(
            LocalMutation {
                requireOwnLive(find(SyncTable.EXERCISES, recordId), id)
                val record = valid.toRecord(recordId, updatedAt = nextTimestamp(SyncTable.EXERCISES, recordId), deletedAt = null)
                put(record)
                record.toDomain()
            },
        )
    }

    override suspend fun deleteCustom(id: String) {
        val database = openDatabase()
        rejectCatalog(database, id)
        val recordId = RecordId(id)
        runnerFor(database).run(
            LocalMutation {
                val existing = requireOwnLive(find(SyncTable.EXERCISES, recordId), id)
                val stamp = nextTimestamp(SyncTable.EXERCISES, recordId)
                put(existing.copy(updatedAt = stamp, deletedAt = stamp))
            },
        )
    }

    private fun validate(input: ExerciseInput): ValidExercise = when (val result = ExerciseValidator.validate(input)) {
        is ExerciseValidation.Valid -> result.value
        is ExerciseValidation.Invalid -> throw ExerciseValidationException(result.errors)
    }

    private fun openDatabase(): GymLogDatabase =
        checkNotNull(databases.current.value) { "No account database is open" }.database

    private suspend fun rejectCatalog(database: GymLogDatabase, id: String) {
        if (id.startsWith(CATALOG_ID_PREFIX) || database.catalogDao().findById(id) != null) throw ExerciseNotEditableException(id)
    }

    private fun requireOwnLive(record: com.chrisgoac.gymlog.model.SyncRecord?, id: String): ExerciseRecord {
        val exercise = record as? ExerciseRecord
        if (exercise == null || exercise.deletedAt != null) throw ExerciseNotFoundException(id)
        if (!exercise.esPersonalizado) throw ExerciseNotEditableException(id)
        return exercise
    }

    private companion object {
        const val CATALOG_ID_PREFIX = "seed-"
    }
}

private fun ValidExercise.toRecord(id: RecordId, updatedAt: Long, deletedAt: Long?) = ExerciseRecord(
    id = id,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    nombre = name,
    grupoMuscular = muscleGroup.wire,
    equipamiento = equipment.wire,
    tipo = type.wire,
    videoUrl = videoUrl,
    notas = notes,
    descripcion = description,
    execution = execution,
    incrementoKg = incrementKg,
    esPersonalizado = true,
)

private fun ExerciseRecord.toDomain() = Exercise(
    id = id.value,
    name = nombre,
    muscleGroup = MuscleGroup.fromWire(grupoMuscular),
    equipment = Equipment.fromWire(equipamiento),
    type = ExerciseType.fromWire(tipo),
    videoUrl = videoUrl,
    notes = notas,
    description = descripcion,
    execution = execution,
    incrementKg = incrementoKg,
    isCustom = true,
)

private fun ExerciseEntity.toDomain() = Exercise(
    id = id,
    name = nombre,
    muscleGroup = MuscleGroup.fromWire(grupoMuscular),
    equipment = Equipment.fromWire(equipamiento),
    type = ExerciseType.fromWire(tipo),
    videoUrl = videoUrl,
    notes = notas,
    description = descripcion,
    execution = execution,
    incrementKg = incrementoKg,
    isCustom = true,
)

private fun CatalogExerciseEntity.toDomain() = Exercise(
    id = id,
    name = nombre,
    muscleGroup = MuscleGroup.fromWire(grupoMuscular),
    equipment = Equipment.fromWire(equipamiento),
    type = ExerciseType.fromWire(tipo),
    videoUrl = videoUrl,
    description = descripcion,
    execution = execution,
    isCustom = false,
)
