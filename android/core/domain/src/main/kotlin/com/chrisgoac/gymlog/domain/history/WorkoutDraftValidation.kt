package com.chrisgoac.gymlog.domain.history

import com.chrisgoac.gymlog.domain.workout.FieldParse
import com.chrisgoac.gymlog.domain.workout.SetFieldError
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutLimits

/** One reason a draft cannot be saved, tied to the field (and row) it belongs to. */
sealed interface DraftError {
    /** The start is later than now (server clock) plus [WorkoutDraftValidation.FUTURE_TOLERANCE_MILLIS]. */
    data object DateInFuture : DraftError

    /** The start is before the epoch (the wire format is unsigned epoch millis). */
    data object DateOutOfRange : DraftError

    /** The newly chosen gym does not exist or was deleted. */
    data class GymUnavailable(val gymId: String) : DraftError

    data object NotesTooLong : DraftError

    /** An added exercise points at an exercise that is not in the catalog nor a live custom one. */
    data class UnknownExercise(val loggedExerciseId: String, val exerciseId: String) : DraftError

    data class InvalidWeight(val setId: String, val error: SetFieldError) : DraftError

    data class InvalidReps(val setId: String, val error: SetFieldError) : DraftError
}

/** Every error of a draft at once (the editor lists all of them, per field). */
data class DraftValidationSummary(val errors: List<DraftError>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Reference checks that need storage (implemented over the local database). */
interface WorkoutReferenceChecker {
    /** The ids in [exerciseIds] that are neither catalog exercises nor live custom ones. */
    suspend fun missingExercises(exerciseIds: Set<String>): Set<String>

    /** True when [gymId] exists and is not deleted (archived gyms are still valid). */
    suspend fun isGymAvailable(gymId: String): Boolean
}

/**
 * Validation of a draft before any transaction starts. Values are checked with the same rules as
 * live logging ([SetValidation]); references are checked only where the draft introduces them (an
 * added exercise, a changed gym) so a workout whose custom exercise or gym was deleted later can
 * still be corrected.
 */
object WorkoutDraftValidation {
    /** A start up to 5 minutes ahead of the server clock is accepted (clock skew between devices). */
    const val FUTURE_TOLERANCE_MILLIS = 5L * 60 * 1000

    /** Checks that need no storage: date, notes, weights and reps. */
    fun validateValues(draft: WorkoutDraft, nowMillis: Long): List<DraftError> = buildList {
        val start = draft.startedAt.toEpochMilli()
        if (start < 0) add(DraftError.DateOutOfRange)
        else if (start > nowMillis + FUTURE_TOLERANCE_MILLIS) add(DraftError.DateInFuture)
        if (cleanNotes(draft.notes).orEmpty().length > WorkoutLimits.NOTES_MAX) add(DraftError.NotesTooLong)
        draft.exercises.forEach { exercise ->
            exercise.sets.forEach { set ->
                (SetValidation.parseWeight(set.weight) as? FieldParse.Invalid)?.let { add(DraftError.InvalidWeight(set.id, it.error)) }
                (SetValidation.parseReps(set.reps) as? FieldParse.Invalid)?.let { add(DraftError.InvalidReps(set.id, it.error)) }
            }
        }
    }

    /** The full summary: [validateValues] plus the references the draft introduces. */
    suspend fun validate(
        original: WorkoutAggregate,
        draft: WorkoutDraft,
        nowMillis: Long,
        references: WorkoutReferenceChecker,
    ): DraftValidationSummary {
        val errors = validateValues(draft, nowMillis).toMutableList()
        val gymId = draft.gymId
        if (gymId != null && gymId != original.session.gymId && !references.isGymAvailable(gymId)) {
            errors += DraftError.GymUnavailable(gymId)
        }
        val existing = original.exercises.map { it.id }.toSet()
        val added = draft.exercises.filterNot { it.id in existing }
        if (added.isNotEmpty()) {
            val missing = references.missingExercises(added.map { it.exerciseId }.toSet())
            added.filter { it.exerciseId in missing }.forEach { errors += DraftError.UnknownExercise(it.id, it.exerciseId) }
        }
        return DraftValidationSummary(errors)
    }

    /** Notes as stored: NUL removed, trimmed, blank = none (same as live logging). */
    fun cleanNotes(notes: String): String? = notes.replace("\u0000", "").trim().ifEmpty { null }
}
