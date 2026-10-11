package com.chrisgoac.gymlog.data.workout

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.dao.FinishedSessionRow
import com.chrisgoac.gymlog.database.entity.toRecord
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.searchKey
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.workout.HistoryDetail
import com.chrisgoac.gymlog.domain.workout.HistoryFilter
import com.chrisgoac.gymlog.domain.workout.HistoryItem
import com.chrisgoac.gymlog.domain.workout.HistoryPage
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutRowMissingException
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import com.chrisgoac.gymlog.domain.workout.WorkoutStartException
import com.chrisgoac.gymlog.domain.workout.summary
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Finished-workout history over the open account's Room database. The list is a LIMITed walk of
 * `index_workout_sessions_fecha` plus one grouped totals query for the visible page only; the
 * text query resolves names to ids first (routines, exercises) and asks SQL for just those
 * sessions, so no read scans every workout. Edits are single local mutations (outbox).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineHistoryRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    private val exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : HistoryRepository {

    override fun observeHistory(filter: HistoryFilter, limit: Int): Flow<HistoryPage> {
        val key = filter.query.searchKey()
        // Exercise names are only needed to match a typed query.
        val exerciseNames: Flow<List<Exercise>> = if (key.isEmpty()) flowOf(emptyList()) else exercises.observeExercises()
        return databases.current.flatMapLatest { open ->
            if (open == null) {
                flowOf(HistoryPage(emptyList(), false))
            } else {
                val database = open.database
                combine(
                    database.invalidationTracker.createFlow(SESSIONS, LOGGED_EXERCISES, LOGGED_SETS, GYMS),
                    routines.observeRoutines(),
                    exerciseNames,
                ) { _, routineList, exerciseList -> routineList to exerciseList }.mapLatest { (routineList, exerciseList) ->
                    database.withTransaction {
                        val dao = database.workoutHistoryDao()
                        val since = filter.period.sinceMillis(now())
                        val routineNames = routineList.associate { it.id to it.name }
                        val rows: List<FinishedSessionRow> = if (key.isEmpty()) {
                            dao.finishedPage(since, filter.gymId, limit + 1)
                        } else {
                            val matchingRoutines = routineList.filter { it.name.searchKey().contains(key) }.map { it.id }
                            val matchingExercises = exerciseList.filter { it.name.searchKey().contains(key) }.map { it.id }
                            val found = LinkedHashMap<String, FinishedSessionRow>()
                            dao.finishedWithNotes(since, filter.gymId).filter { it.notas.orEmpty().searchKey().contains(key) }.forEach { found[it.id] = it }
                            matchingRoutines.chunked(ID_CHUNK).forEach { ids -> dao.finishedByRoutines(since, filter.gymId, ids).forEach { found[it.id] = it } }
                            matchingExercises.chunked(ID_CHUNK).forEach { ids -> dao.finishedByExercises(since, filter.gymId, ids).forEach { found[it.id] = it } }
                            found.values.sortedWith(compareByDescending<FinishedSessionRow> { it.fecha }.thenBy { it.id }).take(limit + 1)
                        }
                        val page = rows.take(limit)
                        val totals = page.map { it.id }.chunked(ID_CHUNK).flatMap { dao.totals(it) }.associateBy { it.sessionId }
                        val gymNames = database.gymDao().live().associate { it.id to it.nombre }
                        HistoryPage(
                            items = page.map { row ->
                                val total = totals[row.id]
                                HistoryItem(
                                    sessionId = row.id,
                                    startedAt = row.fecha,
                                    durationSeconds = row.duracionSegundos,
                                    gymId = row.gymId,
                                    gymName = row.gymId?.let(gymNames::get),
                                    routineName = row.routineId?.let(routineNames::get),
                                    notes = row.notas,
                                    exerciseCount = total?.exercises ?: 0,
                                    setCount = total?.sets ?: 0,
                                    volume = total?.volume ?: 0.0,
                                )
                            },
                            hasMore = rows.size > limit,
                        )
                    }
                }
            }
        }.distinctUntilChanged()
    }

    override fun observeDetail(sessionId: String): Flow<HistoryDetail?> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(null)
        } else {
            val database = open.database
            combine(
                database.invalidationTracker.createFlow(SESSIONS, LOGGED_EXERCISES, LOGGED_SETS, GYMS),
                exercises.observeExercises().map { all -> all.associate { it.id to it.name } },
                routines.observeRoutines().map { all -> all.associate { it.id to it.name } },
            ) { _, names, routineNames -> names to routineNames }.mapLatest { (names, routineNames) ->
                database.withTransaction {
                    val session = database.workoutSessionDao().findById(sessionId)?.takeIf { it.deletedAt == null } ?: return@withTransaction null
                    val setsByExercise = database.workoutHistoryDao().liveSetsForSession(sessionId).groupBy { it.loggedExerciseId }
                    val aggregate = WorkoutAggregate(
                        session = WorkoutSession(session.id, session.routineId, session.gymId, session.fecha, session.duracionSegundos, session.notas, session.updatedAt),
                        exercises = database.loggedExerciseDao().liveForSession(sessionId).map { row ->
                            WorkoutExercise(
                                id = row.id,
                                exerciseId = row.exerciseId,
                                exerciseName = names[row.exerciseId],
                                position = row.orden,
                                sets = setsByExercise[row.id].orEmpty().map { WorkoutSet(it.id, it.orden, it.peso, it.reps, it.esCalentamiento == true, it.updatedAt) },
                                updatedAt = row.updatedAt,
                            )
                        },
                    )
                    val gym = session.gymId?.let { database.gymDao().findById(it) }?.takeIf { it.deletedAt == null }
                    HistoryDetail(
                        workout = aggregate,
                        gymName = gym?.nombre,
                        routineName = session.routineId?.let(routineNames::get),
                        summary = aggregate.summary(session.duracionSegundos ?: 0),
                    )
                }
            }
        }
    }.distinctUntilChanged()

    override suspend fun changeGym(sessionId: String, gymId: String?) {
        mutate { _ ->
            val session = (find(SyncTable.WORKOUT_SESSIONS, RecordId(sessionId)) as? WorkoutSessionRecord)?.takeIf { it.deletedAt == null }
                ?: throw WorkoutRowMissingException(sessionId)
            if (session.gymId == gymId) return@mutate
            if (gymId != null) {
                val gym = find(SyncTable.GYMS, RecordId(gymId)) as? GymRecord
                if (gym == null || gym.deletedAt != null) throw WorkoutStartException.GymUnavailable(gymId)
            }
            put(session.copy(gymId = gymId, updatedAt = nextTimestamp(SyncTable.WORKOUT_SESSIONS, session.id)))
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        mutate { database ->
            val session = (find(SyncTable.WORKOUT_SESSIONS, RecordId(sessionId)) as? WorkoutSessionRecord)?.takeIf { it.deletedAt == null }
                ?: return@mutate
            // Children first, session last: the runner keeps the session row in the final chunk when it splits.
            database.loggedExerciseDao().liveForSession(sessionId).forEach { exercise ->
                database.loggedSetDao().liveForExercise(exercise.id).forEach { set ->
                    val stamp = nextTimestamp(SyncTable.LOGGED_SETS, RecordId(set.id))
                    put(set.toRecord().copy(updatedAt = stamp, deletedAt = stamp))
                }
                val stamp = nextTimestamp(SyncTable.LOGGED_EXERCISES, RecordId(exercise.id))
                put(exercise.toRecord().copy(updatedAt = stamp, deletedAt = stamp))
            }
            val stamp = nextTimestamp(SyncTable.WORKOUT_SESSIONS, session.id)
            put(session.copy(updatedAt = stamp, deletedAt = stamp))
        }
    }

    private suspend fun <T> mutate(block: suspend com.chrisgoac.gymlog.database.LocalMutationScope.(GymLogDatabase) -> T): T {
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(LocalMutation { block(database) })
    }

    private companion object {
        const val SESSIONS = "workout_sessions"
        const val LOGGED_EXERCISES = "logged_exercises"
        const val LOGGED_SETS = "logged_sets"
        const val GYMS = "gyms"

        /** Below SQLite's 999 bound-variable limit (API 26) with room for the other parameters. */
        const val ID_CHUNK = 400
    }
}
