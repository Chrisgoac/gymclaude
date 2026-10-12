package com.chrisgoac.gymlog.data.workout

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.LocalMutationScope
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.history.EditOutcome
import com.chrisgoac.gymlog.domain.history.WorkoutEdit
import com.chrisgoac.gymlog.domain.history.WorkoutEditRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionRecord

/**
 * Writes a historical edit as ONE [LocalMutation] over the open account's database. Inside the
 * mutation it first re-reads the stored workout and compares every row version (`updatedAt`) and
 * the set of live rows with the original the draft was opened from; any difference (a sync pull, an
 * edit on another screen) is a [EditOutcome.Conflict] and nothing is written. Puts go children
 * first and the session row last, so a split mutation (> 50 operations) keeps the session in the
 * final chunk.
 */
class OfflineWorkoutEditRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
) : WorkoutEditRepository {

    override suspend fun missingExercises(exerciseIds: Set<String>): Set<String> {
        val database = openDatabase()
        return exerciseIds.filterNot { id ->
            database.catalogDao().findById(id) != null ||
                database.exerciseDao().findById(id)?.let { it.deletedAt == null && it.esPersonalizado } == true
        }.toSet()
    }

    override suspend fun isGymAvailable(gymId: String): Boolean =
        openDatabase().gymDao().findById(gymId)?.let { it.deletedAt == null } == true

    override suspend fun apply(original: WorkoutAggregate, edit: WorkoutEdit): EditOutcome {
        require(edit.sessionId == original.session.id) { "Edit ${edit.sessionId} is not for session ${original.session.id}" }
        val database = openDatabase()
        return runnerFor(database).run(
            LocalMutation {
                val session = (find(SyncTable.WORKOUT_SESSIONS, RecordId(edit.sessionId)) as? WorkoutSessionRecord)
                    ?.takeIf { it.deletedAt == null }
                    ?: return@LocalMutation EditOutcome.NotFound
                if (storedVersions(database, session) != originalVersions(original)) return@LocalMutation EditOutcome.Conflict
                write(edit, session)
                EditOutcome.Applied
            },
        )
    }

    /** Row key → updatedAt of the live session, its live logged exercises and their live sets. */
    private suspend fun storedVersions(database: GymLogDatabase, session: WorkoutSessionRecord): Map<String, Long> = buildMap {
        put("session:${session.id.value}", session.updatedAt)
        database.loggedExerciseDao().liveForSession(session.id.value).forEach { exercise ->
            put("exercise:${exercise.id}", exercise.updatedAt)
            database.loggedSetDao().liveForExercise(exercise.id).forEach { set -> put("set:${set.id}", set.updatedAt) }
        }
    }

    private fun originalVersions(original: WorkoutAggregate): Map<String, Long> = buildMap {
        put("session:${original.session.id}", original.session.updatedAt)
        original.exercises.forEach { exercise ->
            put("exercise:${exercise.id}", exercise.updatedAt)
            exercise.sets.forEach { set -> put("set:${set.id}", set.updatedAt) }
        }
    }

    private suspend fun LocalMutationScope.write(edit: WorkoutEdit, session: WorkoutSessionRecord) {
        edit.removedSetIds.forEach { id ->
            val set = (find(SyncTable.LOGGED_SETS, RecordId(id)) as? LoggedSetRecord)?.takeIf { it.deletedAt == null } ?: return@forEach
            val stamp = nextTimestamp(SyncTable.LOGGED_SETS, set.id)
            put(set.copy(updatedAt = stamp, deletedAt = stamp))
        }
        edit.removedExerciseIds.forEach { id ->
            val exercise = (find(SyncTable.LOGGED_EXERCISES, RecordId(id)) as? LoggedExerciseRecord)?.takeIf { it.deletedAt == null }
                ?: return@forEach
            val stamp = nextTimestamp(SyncTable.LOGGED_EXERCISES, exercise.id)
            put(exercise.copy(updatedAt = stamp, deletedAt = stamp))
        }
        edit.upsertExercises.forEach { row ->
            val id = RecordId(row.id)
            val stamp = nextTimestamp(SyncTable.LOGGED_EXERCISES, id)
            val existing = find(SyncTable.LOGGED_EXERCISES, id) as? LoggedExerciseRecord
            put(
                existing?.copy(orden = row.position, exerciseId = row.exerciseId, updatedAt = stamp)
                    ?: LoggedExerciseRecord(id, stamp, null, edit.sessionId, row.exerciseId, row.position),
            )
        }
        edit.upsertSets.forEach { row ->
            val id = RecordId(row.id)
            val stamp = nextTimestamp(SyncTable.LOGGED_SETS, id)
            val existing = find(SyncTable.LOGGED_SETS, id) as? LoggedSetRecord
            put(
                existing?.copy(
                    loggedExerciseId = row.loggedExerciseId,
                    orden = row.position,
                    peso = row.weight,
                    reps = row.reps,
                    esCalentamiento = row.warmup,
                    updatedAt = stamp,
                ) ?: LoggedSetRecord(id, stamp, null, row.loggedExerciseId, row.position, row.weight, row.reps, row.warmup),
            )
        }
        edit.session?.let { change ->
            put(
                session.copy(
                    fecha = change.startedAt,
                    gymId = change.gymId,
                    notas = change.notes,
                    updatedAt = nextTimestamp(SyncTable.WORKOUT_SESSIONS, session.id),
                ),
            )
        }
    }

    private fun openDatabase(): GymLogDatabase = checkNotNull(databases.current.value) { "No account database is open" }.database
}
