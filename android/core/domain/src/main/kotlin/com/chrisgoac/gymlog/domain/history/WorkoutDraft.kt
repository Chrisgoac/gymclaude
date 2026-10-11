package com.chrisgoac.gymlog.domain.history

import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import java.time.Instant

/**
 * An in-progress edit of a past workout. Nothing here touches storage: the draft is edited in
 * memory (and in saved state) and only [SaveWorkoutDraftUseCase] turns it into ONE local mutation.
 * Order is the list order; ids are stable (existing rows keep theirs, new rows get one when added).
 */
data class WorkoutDraft(
    val sessionId: String,
    val startedAt: Instant,
    val gymId: String?,
    val notes: String,
    val exercises: List<LoggedExerciseDraft>,
) {
    fun withExercise(id: String, transform: (LoggedExerciseDraft) -> LoggedExerciseDraft): WorkoutDraft =
        copy(exercises = exercises.map { if (it.id == id) transform(it) else it })

    fun withSet(setId: String, transform: (LoggedSetDraft) -> LoggedSetDraft): WorkoutDraft =
        copy(exercises = exercises.map { exercise -> exercise.copy(sets = exercise.sets.map { if (it.id == setId) transform(it) else it }) })

    fun removeExercise(id: String): WorkoutDraft = copy(exercises = exercises.filterNot { it.id == id })

    fun moveExercise(id: String, delta: Int): WorkoutDraft = copy(exercises = exercises.moved({ it.id == id }, delta))

    fun removeSet(setId: String): WorkoutDraft =
        copy(exercises = exercises.map { exercise -> exercise.copy(sets = exercise.sets.filterNot { it.id == setId }) })

    fun moveSet(setId: String, delta: Int): WorkoutDraft =
        copy(exercises = exercises.map { exercise -> exercise.copy(sets = exercise.sets.moved({ it.id == setId }, delta)) })

    companion object {
        /** The draft that reproduces [aggregate] exactly (saving it unchanged writes nothing). */
        fun from(aggregate: WorkoutAggregate): WorkoutDraft = WorkoutDraft(
            sessionId = aggregate.session.id,
            startedAt = Instant.ofEpochMilli(aggregate.session.startedAt),
            gymId = aggregate.session.gymId,
            notes = aggregate.session.notes.orEmpty(),
            exercises = aggregate.exercises.sortedWith(compareBy({ it.position }, { it.id })).map { exercise ->
                LoggedExerciseDraft(
                    id = exercise.id,
                    exerciseId = exercise.exerciseId,
                    exerciseName = exercise.exerciseName,
                    sets = exercise.sets.sortedWith(compareBy({ it.position }, { it.id })).map { set ->
                        LoggedSetDraft(set.id, SetValidation.formatWeight(set.weight), set.reps.toString(), set.warmup)
                    },
                )
            },
        )
    }
}

/** A logged exercise of the draft; [exerciseName] is for display only (null = exercise no longer exists). */
data class LoggedExerciseDraft(
    val id: String,
    val exerciseId: String,
    val exerciseName: String?,
    val sets: List<LoggedSetDraft>,
)

/** A set of the draft; weight/reps are the typed text and are validated with [SetValidation] on save. */
data class LoggedSetDraft(
    val id: String,
    val weight: String,
    val reps: String,
    val warmup: Boolean,
)

/** [this] with the first element matching [target] moved by [delta] places, clamped to the list. */
private fun <T> List<T>.moved(target: (T) -> Boolean, delta: Int): List<T> {
    val index = indexOfFirst(target)
    if (index < 0) return this
    val destination = (index + delta).coerceIn(0, lastIndex)
    return toMutableList().apply { add(destination, removeAt(index)) }
}
