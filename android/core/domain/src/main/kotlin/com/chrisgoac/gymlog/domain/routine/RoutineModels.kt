package com.chrisgoac.gymlog.domain.routine

/** One exercise inside a routine, with its targets. [id] is the stable routine-exercise row id. */
data class RoutineExercise(
    val id: String,
    val exerciseId: String,
    /** Null when the exercise no longer exists (deleted custom exercise). */
    val exerciseName: String?,
    val position: Int,
    val sets: Int?,
    val repsMin: Int?,
    /** The top of the repetition range (wire `repsObjetivo`). */
    val repsMax: Int?,
    val restSeconds: Int?,
    val notes: String?,
)

data class Routine(
    val id: String,
    val name: String,
    val description: String?,
    val archived: Boolean,
    val exercises: List<RoutineExercise>,
)

/** A routine as listed: enough to render a card without loading its exercises' targets. */
data class RoutineSummary(
    val id: String,
    val name: String,
    val description: String?,
    val archived: Boolean,
    val exerciseCount: Int,
    /** Names of the first exercises, for a one-line preview. */
    val exercisePreview: List<String>,
)

/**
 * Raw editor form values. Numbers are text so the form can show bad input. [RoutineExerciseInput.key]
 * is the stable id of the routine-exercise row: the existing row's id when editing, a fresh UUID
 * for an exercise added in this draft. List order is the routine order.
 */
data class RoutineInput(
    val name: String = "",
    val description: String = "",
    val exercises: List<RoutineExerciseInput> = emptyList(),
) {
    companion object {
        fun from(routine: Routine) = RoutineInput(
            name = routine.name,
            description = routine.description.orEmpty(),
            exercises = routine.exercises.map(RoutineExerciseInput::from),
        )
    }
}

data class RoutineExerciseInput(
    val key: String,
    val exerciseId: String,
    val exerciseName: String?,
    val sets: String = "",
    val repsMin: String = "",
    val repsMax: String = "",
    val restSeconds: String = "",
    val notes: String = "",
) {
    companion object {
        fun from(exercise: RoutineExercise) = RoutineExerciseInput(
            key = exercise.id,
            exerciseId = exercise.exerciseId,
            exerciseName = exercise.exerciseName,
            sets = exercise.sets?.toString().orEmpty(),
            repsMin = exercise.repsMin?.toString().orEmpty(),
            repsMax = exercise.repsMax?.toString().orEmpty(),
            restSeconds = exercise.restSeconds?.toString().orEmpty(),
            notes = exercise.notes.orEmpty(),
        )
    }
}

