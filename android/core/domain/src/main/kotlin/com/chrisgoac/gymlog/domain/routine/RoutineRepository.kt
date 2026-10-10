package com.chrisgoac.gymlog.domain.routine

import kotlinx.coroutines.flow.Flow

/**
 * Routines and their ordered exercises over the local database. Reads are Flows; writes commit
 * locally in one transaction (domain rows + outbox) and never wait for the network.
 */
interface RoutineRepository {
    /** Live routines in manual order (archived ones included, flagged); re-emits on any change. */
    fun observeRoutines(): Flow<List<RoutineSummary>>

    /** The routine with its exercises in order, or null while missing/deleted. */
    fun observeRoutine(id: String): Flow<Routine?>

    /**
     * The id of the routine to train next: rotation over non-archived routines in manual order,
     * after the routine of the most recent session started from a routine. Null if there is none.
     */
    fun observeNextRoutineId(): Flow<String?>

    /** @throws RoutineValidationException on invalid [input]. */
    suspend fun create(input: RoutineInput): Routine

    /**
     * Applies [input] to routine [id]: updates changed fields, rewrites child positions as
     * compact 0..n-1 on the existing stable rows, creates new rows, and tombstones rows that are
     * no longer in [input] - all in one mutation.
     * @throws RoutineNotFoundException; [RoutineValidationException]; [RoutineExerciseConflictException]
     */
    suspend fun update(id: String, input: RoutineInput): Routine

    /**
     * Gives the live routines compact positions 0..n-1: [idsInOrder] first (unknown ids ignored),
     * the remaining live routines after them in their current order. Only changed rows are written.
     */
    suspend fun reorder(idsInOrder: List<String>)

    /** Archives or restores a routine; its exercises are untouched. @throws RoutineNotFoundException */
    suspend fun setArchived(id: String, archived: Boolean)

    /** Tombstones the routine and all its live exercises in one mutation. @throws RoutineNotFoundException */
    suspend fun delete(id: String)
}
