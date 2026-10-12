package com.chrisgoac.gymlog.data.workout

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.LocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.workout.ActiveWorkoutPolicy
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.WorkoutFinishRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutLimits
import com.chrisgoac.gymlog.domain.workout.WorkoutRowMissingException
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutStartException
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.util.UUID

/**
 * Workouts over the open account's Room database. Starting a workout is ONE [LocalMutation]
 * (session row + logged-exercise rows + outbox; the runner chunks it past 50 operations). The
 * one-active-session invariant ([ActiveWorkoutPolicy]: newest unfinished session within the last
 * 12 h) is checked INSIDE that transaction, so concurrent or repeated starts converge on a single
 * session; a second start returns the existing one. Reads are scoped to one workout (see
 * [readWorkout]), never whole tables.
 *
 * The weekly training summary counts every live session of the week, finished or not (PWA
 * `getResumenSemana` parity), so an abandoned session still counts as a day trained.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineWorkoutRepository(
    private val databases: UserDatabaseManager,
    private val runnerFor: (GymLogDatabase) -> LocalMutationRunner,
    /** Server-corrected wall clock; stamps the session's `fecha`. */
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : WorkoutRepository, WorkoutFinishRepository {

    // ---- Reads ----

    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(null)
        } else {
            open.database.workoutSessionDao().observeActiveSessionId(now)
                .flatMapLatest { id -> if (id == null) flowOf(null) else open.database.observeWorkoutRows(id) }
        }
    }.distinctUntilChanged()

    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = databases.current.flatMapLatest { open ->
        if (open == null) flowOf(null) else open.database.observeWorkoutRows(sessionId)
    }.distinctUntilChanged()

    override fun observeGyms(): Flow<List<Gym>> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(emptyList())
        } else {
            open.database.gymDao().observeLive().map { rows -> rows.filterNot { it.archivada }.map { Gym(it.id, it.nombre) } }
        }
    }.distinctUntilChanged()

    override fun observeTrainingSummary(): Flow<TrainingSummary> = databases.current.flatMapLatest { open ->
        if (open == null) {
            flowOf(TrainingSummary.compute(emptyList(), TrainingSummary.DEFAULT_WEEKLY_GOAL, now(), zone()))
        } else {
            combine(open.database.workoutSessionDao().observeLive(), open.database.userSettingDao().observeLive()) { sessions, settings ->
                val goal = settings.firstOrNull { it.id == GOAL_SETTING }?.valor?.trim()?.toIntOrNull()
                    ?: TrainingSummary.DEFAULT_WEEKLY_GOAL
                TrainingSummary.compute(sessions.map { it.fecha }, goal, now(), zone())
            }
        }
    }.distinctUntilChanged()

    // ---- Writes ----

    override suspend fun startFreeWorkout(gymId: String?): StartedWorkout = start(routineId = null, gymId = gymId)

    override suspend fun startRoutineWorkout(routineId: String, gymId: String?): StartedWorkout =
        start(routineId = routineId, gymId = gymId)

    private suspend fun start(routineId: String?, gymId: String?): StartedWorkout {
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(
            LocalMutation {
                // Inside the transaction: two racing starts cannot both see "no active workout". Same
                // definition as observeActiveWorkout: an unfinished session older than the window is abandoned.
                database.workoutSessionDao().findActiveSessionId(now())?.let { return@LocalMutation StartedWorkout(it, resumed = true) }

                if (gymId != null) {
                    val gym = find(SyncTable.GYMS, RecordId(gymId)) as? GymRecord
                    if (gym == null || gym.deletedAt != null) throw WorkoutStartException.GymUnavailable(gymId)
                }
                val children = if (routineId == null) {
                    emptyList()
                } else {
                    val routine = find(SyncTable.ROUTINES, RecordId(routineId)) as? RoutineRecord
                    if (routine == null || routine.deletedAt != null) throw WorkoutStartException.RoutineNotFound(routineId)
                    if (routine.archivada) throw WorkoutStartException.RoutineArchived(routineId)
                    // Tombstoned routine exercises are not live and never reach the workout.
                    database.routineExerciseDao().liveForRoutine(routineId)
                }

                val sessionId = RecordId(newId())
                put(
                    WorkoutSessionRecord(
                        id = sessionId,
                        updatedAt = nextTimestamp(SyncTable.WORKOUT_SESSIONS, sessionId),
                        deletedAt = null,
                        routineId = routineId,
                        gymId = gymId,
                        fecha = now(),
                    ),
                )
                children.forEachIndexed { index, child ->
                    val id = RecordId(newId())
                    put(
                        LoggedExerciseRecord(
                            id = id,
                            updatedAt = nextTimestamp(SyncTable.LOGGED_EXERCISES, id),
                            deletedAt = null,
                            sessionId = sessionId.value,
                            exerciseId = child.exerciseId,
                            orden = index,
                        ),
                    )
                }
                StartedWorkout(sessionId.value, resumed = false)
            },
        )
    }

    override suspend fun finish(sessionId: String, notes: String?): Int {
        val cleaned = notes?.replace("\u0000", "")?.trim()
        require(cleaned == null || cleaned.length <= WorkoutLimits.NOTES_MAX) { "Notes over ${WorkoutLimits.NOTES_MAX} characters" }
        val database = checkNotNull(databases.current.value) { "No account database is open" }.database
        return runnerFor(database).run(
            LocalMutation {
                val session = (find(SyncTable.WORKOUT_SESSIONS, RecordId(sessionId)) as? WorkoutSessionRecord)?.takeIf { it.deletedAt == null }
                    ?: throw WorkoutRowMissingException(sessionId)
                // Finishing twice keeps the first completion time.
                session.duracionSegundos?.let { return@LocalMutation it }
                // PWA: Math.round((now - fecha) / 1000); a start in the future (clock skew) is 0.
                val duration = Math.floorDiv((now() - session.fecha).coerceAtLeast(0L) + 500L, 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                put(
                    session.copy(
                        duracionSegundos = duration,
                        notas = if (cleaned == null) session.notas else cleaned.ifEmpty { null },
                        updatedAt = nextTimestamp(SyncTable.WORKOUT_SESSIONS, session.id),
                    ),
                )
                duration
            },
        )
    }

    private companion object {
        const val GOAL_SETTING = "objetivoSemanal"
    }
}
