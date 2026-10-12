package com.chrisgoac.gymlog.domain.workout

import kotlinx.coroutines.flow.Flow

/**
 * Workout sessions over the local database. Reads are Flows; writes commit locally in one
 * transaction (session + exercises + outbox) and never wait for the network.
 */
interface WorkoutRepository {
    /** The active workout (newest, if sync ever delivered several), or null; re-emits on any change. */
    fun observeActiveWorkout(): Flow<WorkoutAggregate?>

    /** One workout by id, or null while missing/deleted. */
    fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?>

    /** Live, non-archived gyms in manual order. */
    fun observeGyms(): Flow<List<Gym>>

    fun observeTrainingSummary(): Flow<TrainingSummary>

    /**
     * Starts an empty workout, or returns the existing active one ([StartedWorkout.resumed]).
     * @throws WorkoutStartException.GymUnavailable
     */
    suspend fun startFreeWorkout(gymId: String?): StartedWorkout

    /**
     * Starts a workout copying the routine's live exercises (no sets pre-created), or returns the
     * existing active one without validating anything else.
     * @throws WorkoutStartException.RoutineNotFound; [WorkoutStartException.RoutineArchived]; [WorkoutStartException.GymUnavailable]
     */
    suspend fun startRoutineWorkout(routineId: String, gymId: String?): StartedWorkout
}
