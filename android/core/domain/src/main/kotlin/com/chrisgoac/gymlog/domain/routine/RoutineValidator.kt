package com.chrisgoac.gymlog.domain.routine

enum class RoutineField { NAME, DESCRIPTION, SETS, REPS_MIN, REPS_MAX, REST, NOTES }

enum class RoutineError { REQUIRED, TOO_LONG, NOT_A_NUMBER, NOT_POSITIVE, TOO_LARGE, RANGE_INVERTED, DUPLICATE_KEY }

/** One problem. [exerciseKey] is null for routine-level fields, else the [RoutineExerciseInput.key] it belongs to. */
data class RoutineIssue(val exerciseKey: String?, val field: RoutineField, val error: RoutineError)

class RoutineValidationException(val issues: List<RoutineIssue>) : IllegalArgumentException("Invalid routine: $issues")

class RoutineNotFoundException(val id: String) : RuntimeException("Routine $id not found")

/** A draft exercise row refers to a row that is not a live exercise of this routine (deleted, or another routine's). */
class RoutineExerciseConflictException(val key: String) : RuntimeException("Routine exercise $key cannot be reused")

/** Validated, normalized values; order is the list position. */
data class ValidRoutine(val name: String, val description: String?, val exercises: List<ValidRoutineExercise>)

data class ValidRoutineExercise(
    val key: String,
    val exerciseId: String,
    val sets: Int?,
    val repsMin: Int?,
    val repsMax: Int?,
    val restSeconds: Int?,
    val notes: String?,
)

sealed interface RoutineValidation {
    data class Valid(val value: ValidRoutine) : RoutineValidation
    data class Invalid(val issues: List<RoutineIssue>) : RoutineValidation
}

object RoutineValidator {
    const val MAX_NAME = 120
    const val MAX_TEXT = 1000
    const val MAX_SETS = 99
    const val MAX_REPS = 999
    const val MAX_REST_SECONDS = 3600

    fun validate(input: RoutineInput): RoutineValidation {
        val issues = mutableListOf<RoutineIssue>()
        val name = input.name.trim()
        when {
            name.isEmpty() -> issues += RoutineIssue(null, RoutineField.NAME, RoutineError.REQUIRED)
            name.length > MAX_NAME -> issues += RoutineIssue(null, RoutineField.NAME, RoutineError.TOO_LONG)
        }
        val description = input.description.trim().ifEmpty { null }
        if (description != null && description.length > MAX_TEXT) {
            issues += RoutineIssue(null, RoutineField.DESCRIPTION, RoutineError.TOO_LONG)
        }

        val seen = mutableSetOf<String>()
        val exercises = input.exercises.map { draft ->
            val key = draft.key
            if (!seen.add(key)) issues += RoutineIssue(key, RoutineField.NOTES, RoutineError.DUPLICATE_KEY)
            val sets = number(key, RoutineField.SETS, draft.sets, minimum = 1, maximum = MAX_SETS, issues)
            val repsMin = number(key, RoutineField.REPS_MIN, draft.repsMin, minimum = 1, maximum = MAX_REPS, issues)
            val repsMax = number(key, RoutineField.REPS_MAX, draft.repsMax, minimum = 1, maximum = MAX_REPS, issues)
            val rest = number(key, RoutineField.REST, draft.restSeconds, minimum = 0, maximum = MAX_REST_SECONDS, issues)
            if (repsMin != null && repsMax != null && repsMin > repsMax) {
                issues += RoutineIssue(key, RoutineField.REPS_MIN, RoutineError.RANGE_INVERTED)
            }
            val notes = draft.notes.trim().ifEmpty { null }
            if (notes != null && notes.length > MAX_TEXT) issues += RoutineIssue(key, RoutineField.NOTES, RoutineError.TOO_LONG)
            ValidRoutineExercise(key, draft.exerciseId, sets, repsMin, repsMax, rest, notes)
        }
        return if (issues.isEmpty()) RoutineValidation.Valid(ValidRoutine(name, description, exercises)) else RoutineValidation.Invalid(issues)
    }

    /** Blank is "not set" (null); otherwise a plain non-negative integer within [minimum]..[maximum]. */
    private fun number(
        key: String,
        field: RoutineField,
        text: String,
        minimum: Int,
        maximum: Int,
        issues: MutableList<RoutineIssue>,
    ): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.all { it in '0'..'9' }) {
            issues += RoutineIssue(key, field, RoutineError.NOT_A_NUMBER)
            return null
        }
        val value = trimmed.toBigInteger()
        return when {
            value > maximum.toBigInteger() -> {
                issues += RoutineIssue(key, field, RoutineError.TOO_LARGE)
                null
            }
            value < minimum.toBigInteger() -> {
                issues += RoutineIssue(key, field, RoutineError.NOT_POSITIVE)
                null
            }
            else -> value.toInt()
        }
    }
}
