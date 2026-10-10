package com.chrisgoac.gymlog.train

import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutStartException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface StartWorkoutResult {
    /** [resumed] = an active workout already existed; no new session was created. */
    data class Started(val sessionId: String, val resumed: Boolean) : StartWorkoutResult
    data object GymUnavailable : StartWorkoutResult
    data object RoutineUnavailable : StartWorkoutResult
    data object RoutineArchived : StartWorkoutResult
    /** Any other local failure (storage). Nothing was started. */
    data object Failed : StartWorkoutResult
}

/**
 * Starts workouts. Calls are serialized, so rapid duplicate taps queue behind each other: the
 * first creates the session and the rest resume it. The repository enforces the same
 * one-active-session rule inside its transaction, which is what actually guarantees it.
 */
class StartWorkoutUseCase(private val workouts: WorkoutRepository) {
    private val lock = Mutex()

    suspend fun startFree(gymId: String?): StartWorkoutResult = attempt { workouts.startFreeWorkout(gymId) }

    suspend fun startRoutine(routineId: String, gymId: String?): StartWorkoutResult =
        attempt { workouts.startRoutineWorkout(routineId, gymId) }

    private suspend fun attempt(start: suspend () -> com.chrisgoac.gymlog.domain.workout.StartedWorkout): StartWorkoutResult =
        lock.withLock {
            try {
                val started = start()
                StartWorkoutResult.Started(started.sessionId, started.resumed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WorkoutStartException.GymUnavailable) {
                StartWorkoutResult.GymUnavailable
            } catch (e: WorkoutStartException.RoutineNotFound) {
                StartWorkoutResult.RoutineUnavailable
            } catch (e: WorkoutStartException.RoutineArchived) {
                StartWorkoutResult.RoutineArchived
            } catch (e: Exception) {
                StartWorkoutResult.Failed
            }
        }
}
