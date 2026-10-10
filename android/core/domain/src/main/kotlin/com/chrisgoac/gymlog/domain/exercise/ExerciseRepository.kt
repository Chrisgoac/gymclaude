package com.chrisgoac.gymlog.domain.exercise

import kotlinx.coroutines.flow.Flow

/** The exercise [id] does not exist (or was deleted). */
class ExerciseNotFoundException(val id: String) : RuntimeException("Exercise $id not found")

/** Catalog exercises are read-only APK assets; only the user's own exercises can change. */
class ExerciseNotEditableException(val id: String) : RuntimeException("Exercise $id belongs to the catalog and cannot be changed")

class ExerciseValidationException(val errors: Map<ExerciseField, ExerciseFieldError>) :
    IllegalArgumentException("Invalid exercise: $errors")

/**
 * Exercises = packaged catalog (read-only) + the user's custom exercises (synchronized).
 * Reads are Flows over the local database; writes commit locally and never wait for the network.
 */
interface ExerciseRepository {
    /** Matching exercises sorted by name; re-emits whenever catalog or custom exercises change. */
    fun observeExercises(filter: ExerciseFilter = ExerciseFilter()): Flow<List<Exercise>>

    /** The exercise, or null while missing/deleted. */
    fun observeExercise(id: String): Flow<Exercise?>

    /** @throws ExerciseValidationException on invalid [input]. */
    suspend fun createCustom(input: ExerciseInput): Exercise

    /** @throws ExerciseNotEditableException for catalog ids; [ExerciseNotFoundException]; [ExerciseValidationException]. */
    suspend fun updateCustom(id: String, input: ExerciseInput): Exercise

    /** Tombstones a custom exercise. Same errors as [updateCustom] (except validation). */
    suspend fun deleteCustom(id: String)
}
