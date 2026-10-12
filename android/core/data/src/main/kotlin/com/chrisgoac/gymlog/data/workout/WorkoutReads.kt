package com.chrisgoac.gymlog.data.workout

import androidx.room.withTransaction
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.dao.WorkoutSessionDao
import com.chrisgoac.gymlog.domain.workout.ActiveWorkoutPolicy
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest

/** Tables whose commits can change what [readWorkout] returns. */
internal val WORKOUT_TABLES = arrayOf("workout_sessions", "logged_exercises", "logged_sets", "exercises", "catalog_exercises")

/**
 * One workout read with scoped queries only: the session by primary key, its logged exercises
 * through `index_logged_exercises_sessionId`, their sets through
 * `index_logged_sets_loggedExerciseId`, and names for just the exercises present. The cost depends
 * on the size of this workout, never on the size of the history. Null while missing or deleted.
 */
internal suspend fun GymLogDatabase.readWorkout(sessionId: String): WorkoutAggregate? = withTransaction {
    val session = workoutSessionDao().findById(sessionId)?.takeIf { it.deletedAt == null } ?: return@withTransaction null
    val rows = loggedExerciseDao().liveForSession(sessionId)
    val setsByExercise = workoutHistoryDao().liveSetsForSession(sessionId).groupBy { it.loggedExerciseId }
    val exerciseIds = rows.map { it.exerciseId }.distinct()
    val names = HashMap<String, String>()
    exerciseIds.chunked(NAME_CHUNK).forEach { ids ->
        catalogDao().names(ids).forEach { names[it.id] = it.nombre }
        exerciseDao().customNames(ids).forEach { names[it.id] = it.nombre }
    }
    WorkoutAggregate(
        session = WorkoutSession(session.id, session.routineId, session.gymId, session.fecha, session.duracionSegundos, session.notas),
        exercises = rows.map { row ->
            WorkoutExercise(
                id = row.id,
                exerciseId = row.exerciseId,
                exerciseName = names[row.exerciseId],
                position = row.orden,
                sets = setsByExercise[row.id].orEmpty().map { WorkoutSet(it.id, it.orden, it.peso, it.reps, it.esCalentamiento == true) },
            )
        },
    )
}

/** [readWorkout] re-run on every commit to a table it reads; identical results are not re-emitted. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun GymLogDatabase.observeWorkoutRows(sessionId: String): Flow<WorkoutAggregate?> =
    invalidationTracker.createFlow(*WORKOUT_TABLES).mapLatest { readWorkout(sessionId) }.distinctUntilChanged()

/**
 * The id of the active workout per [ActiveWorkoutPolicy], or null. Re-evaluated on every change to
 * the unfinished sessions AND when the active one ages out of the window (a timer to its expiry),
 * so an open Train tab does not keep offering a workout abandoned twelve hours ago.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun WorkoutSessionDao.observeActiveSessionId(now: () -> Long): Flow<String?> =
    observeActive().transformLatest { unfinishedNewestFirst ->
        while (true) {
            val at = now()
            val active = unfinishedNewestFirst.firstOrNull { ActiveWorkoutPolicy.isFresh(it.fecha, at) }
            emit(active?.id)
            if (active == null) break
            delay(ActiveWorkoutPolicy.staleAt(active.fecha) - at)
            // A clock that did not move (tests, or a wall clock set back) would loop forever: wait for the next change instead.
            if (now() <= at) break
        }
    }.distinctUntilChanged()

/** The active session id at this instant; usable inside a mutation transaction. */
internal suspend fun WorkoutSessionDao.findActiveSessionId(now: Long): String? =
    findActive().firstOrNull { ActiveWorkoutPolicy.isFresh(it.fecha, now) }?.id

/** Below SQLite's 999 bound-variable limit (API 26). */
private const val NAME_CHUNK = 400
