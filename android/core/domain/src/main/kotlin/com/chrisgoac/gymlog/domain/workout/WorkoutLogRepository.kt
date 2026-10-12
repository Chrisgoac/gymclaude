package com.chrisgoac.gymlog.domain.workout

import kotlinx.coroutines.flow.Flow

/** One set of a past session, as logged. */
data class HistorySet(val weight: Double, val reps: Int, val warmup: Boolean)

/**
 * The most recent earlier session of an exercise that has sets (PWA `findLastLoggedExercise`):
 * its date and its live sets in order.
 */
data class ExerciseHistory(val date: Long, val sets: List<HistorySet>) {
    /** Last set by position, warm-ups included (PWA `getLastPerformance`). */
    val lastSet: HistorySet? get() = sets.lastOrNull()

    /** Working sets (no warm-ups) in order, or null if there are none (PWA `getLastWorkingSets`). */
    val workingSets: List<WeightReps>? get() = sets.filterNot { it.warmup }.map { WeightReps(it.weight, it.reps) }.ifEmpty { null }
}

/** Partial update of a set; null fields keep their stored value. */
data class SetChange(val weight: Double? = null, val reps: Int? = null, val warmup: Boolean? = null)

/** The row a logging write targets does not exist (or the session/exercise was deleted meanwhile). */
class WorkoutRowMissingException(val id: String) : RuntimeException("Workout row $id does not exist or was deleted")

/**
 * Logging inside a workout. Every write is ONE local mutation (rows + outbox) that never waits for
 * the network. Positions stay compact (0..n-1) on stable ids; removals tombstone. Values are
 * checked with [SetValidation] before the transaction ([IllegalArgumentException] otherwise).
 * Removing or editing a row that is already gone is a no-op, so repeated events are harmless.
 */
interface WorkoutLogRepository {
    /**
     * Last earlier performance per exercise in [exerciseIds], excluding [excludeSessionId]; when
     * [gymId] is set only sessions at that gym count (PWA parity). Exercises without history are
     * absent from the map. Read-only; re-emits when history changes.
     */
    fun observeExerciseHistory(exerciseIds: Set<String>, excludeSessionId: String, gymId: String?): Flow<Map<String, ExerciseHistory>>

    fun observeProgressionSettings(): Flow<ProgressionSettings>

    /**
     * Appends [exerciseId] to the session; returns the new logged-exercise id.
     * @throws com.chrisgoac.gymlog.domain.exercise.ExerciseNotFoundException for an unknown/deleted exercise; [WorkoutRowMissingException]
     */
    suspend fun addExercise(sessionId: String, exerciseId: String): String

    /** Tombstones the logged exercise and its sets; the remaining exercises are re-compacted. */
    suspend fun removeExercise(loggedExerciseId: String)

    /** Moves the logged exercise by [delta] positions (clamped); compacts the session's order. */
    suspend fun moveExercise(loggedExerciseId: String, delta: Int)

    /** Appends a set; returns its id. @throws WorkoutRowMissingException; [IllegalArgumentException] */
    suspend fun addSet(loggedExerciseId: String, weight: Double, reps: Int, warmup: Boolean = false): String

    /** Applies [change]; writes nothing when the values are unchanged. @throws IllegalArgumentException */
    suspend fun updateSet(setId: String, change: SetChange)

    /** Tombstones the set; its siblings are re-compacted. */
    suspend fun removeSet(setId: String)

    /** Moves the set by [delta] positions (clamped); compacts the exercise's order. */
    suspend fun moveSet(setId: String, delta: Int)

    /** Blank clears the notes. @throws IllegalArgumentException over [WorkoutLimits.NOTES_MAX]; [WorkoutRowMissingException] */
    suspend fun updateNotes(sessionId: String, notes: String)
}
