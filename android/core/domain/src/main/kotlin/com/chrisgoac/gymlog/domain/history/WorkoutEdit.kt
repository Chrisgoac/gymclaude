package com.chrisgoac.gymlog.domain.history

import com.chrisgoac.gymlog.domain.workout.FieldParse
import com.chrisgoac.gymlog.domain.workout.SetValidation
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate

/** New values of the session row; only present in a [WorkoutEdit] when one of them changed. */
data class SessionEdit(val startedAt: Long, val gymId: String?, val notes: String?)

/** A logged exercise to write: new (not in the original) or with a new position. */
data class ExerciseRowEdit(val id: String, val exerciseId: String, val position: Int)

/** A set to write: new, or with a changed position, parent, weight, reps or warm-up flag. */
data class SetRowEdit(
    val id: String,
    val loggedExerciseId: String,
    val position: Int,
    val weight: Double,
    val reps: Int,
    val warmup: Boolean,
)

/**
 * What saving a draft writes, as the difference between the original aggregate and the draft:
 * unchanged rows are absent. Removing an exercise also lists all its original sets in
 * [removedSetIds]. Positions are compact (0..n-1) in draft order.
 */
data class WorkoutEdit(
    val sessionId: String,
    val session: SessionEdit?,
    val upsertExercises: List<ExerciseRowEdit>,
    val removedExerciseIds: List<String>,
    val upsertSets: List<SetRowEdit>,
    val removedSetIds: List<String>,
) {
    val isEmpty: Boolean
        get() = session == null && upsertExercises.isEmpty() && removedExerciseIds.isEmpty() && upsertSets.isEmpty() && removedSetIds.isEmpty()

    companion object {
        /** @throws IllegalArgumentException if [draft] has an unparseable value (validate first). */
        fun between(original: WorkoutAggregate, draft: WorkoutDraft): WorkoutEdit {
            require(draft.sessionId == original.session.id) { "Draft ${draft.sessionId} is not for session ${original.session.id}" }
            val session = original.session
            val notes = WorkoutDraftValidation.cleanNotes(draft.notes)
            val startedAt = draft.startedAt.toEpochMilli()
            val sessionEdit = if (startedAt != session.startedAt || draft.gymId != session.gymId || notes != session.notes) {
                SessionEdit(startedAt, draft.gymId, notes)
            } else {
                null
            }

            val originalExercises = original.exercises.associateBy { it.id }
            val originalSets = original.exercises.flatMap { exercise -> exercise.sets.map { it to exercise.id } }.associateBy { it.first.id }
            val draftExerciseIds = draft.exercises.map { it.id }.toSet()
            val draftSetIds = draft.exercises.flatMap { exercise -> exercise.sets.map { it.id } }.toSet()

            val upsertExercises = draft.exercises.mapIndexedNotNull { index, exercise ->
                val before = originalExercises[exercise.id]
                if (before != null && before.position == index && before.exerciseId == exercise.exerciseId) null
                else ExerciseRowEdit(exercise.id, exercise.exerciseId, index)
            }
            val upsertSets = draft.exercises.flatMap { exercise ->
                exercise.sets.mapIndexedNotNull { index, set ->
                    val weight = (SetValidation.parseWeight(set.weight) as? FieldParse.Valid)?.value
                        ?: throw IllegalArgumentException("Invalid weight '${set.weight}' in set ${set.id}")
                    val reps = (SetValidation.parseReps(set.reps) as? FieldParse.Valid)?.value
                        ?: throw IllegalArgumentException("Invalid reps '${set.reps}' in set ${set.id}")
                    val row = SetRowEdit(set.id, exercise.id, index, weight, reps, set.warmup)
                    val before = originalSets[set.id]
                    val unchanged = before != null && before.second == exercise.id && before.first.position == index &&
                        before.first.weight == weight && before.first.reps == reps && before.first.warmup == set.warmup
                    if (unchanged) null else row
                }
            }
            val removedExerciseIds = original.exercises.map { it.id }.filterNot { it in draftExerciseIds }
            val removedSetIds = original.exercises.flatMap { it.sets }.map { it.id }.filterNot { it in draftSetIds }
            return WorkoutEdit(draft.sessionId, sessionEdit, upsertExercises, removedExerciseIds, upsertSets, removedSetIds)
        }
    }

    /**
     * Derived data that this edit makes stale (consumed by the progress/achievements/coach features):
     *
     * - date → [InvalidationScope.STREAKS], [InvalidationScope.WEEKLY_SUMMARY] (the session moves week),
     *   and everything plotted or ranked over time: charts, PRs (when achieved), stalls, achievements.
     * - set values (weight, reps, warm-up) or added/removed sets → PRs, 1RM, volume, charts, stalls, achievements.
     * - added/removed exercises → [InvalidationScope.MUSCLE_BALANCE] plus the set-value scopes.
     * - gym → [InvalidationScope.CHARTS] only (charts can be filtered by gym; PRs/volume are gym-agnostic).
     * - notes, reordering sets or exercises → no metric changes.
     * - any change at all → [InvalidationScope.COACH_SNAPSHOT] (the Coach reads the raw history).
     */
    fun invalidatedScopes(original: WorkoutAggregate): Set<InvalidationScope> {
        if (isEmpty) return emptySet()
        val scopes = mutableSetOf(InvalidationScope.COACH_SNAPSHOT)
        val originalSets = original.exercises.flatMap { it.sets }.associateBy { it.id }
        val setValuesChanged = removedSetIds.isNotEmpty() || upsertSets.any { row ->
            val before = originalSets[row.id]
            before == null || before.weight != row.weight || before.reps != row.reps || before.warmup != row.warmup
        }
        val originalExerciseIds = original.exercises.map { it.id }.toSet()
        val exercisesAddedOrRemoved = removedExerciseIds.isNotEmpty() || upsertExercises.any { it.id !in originalExerciseIds }
        if (setValuesChanged || exercisesAddedOrRemoved) scopes += SET_VALUE_SCOPES
        if (exercisesAddedOrRemoved) scopes += InvalidationScope.MUSCLE_BALANCE
        val sessionBefore = original.session
        if (session != null && session.startedAt != sessionBefore.startedAt) scopes += DATE_SCOPES
        if (session != null && session.gymId != sessionBefore.gymId) scopes += InvalidationScope.CHARTS
        return scopes
    }
}

private val SET_VALUE_SCOPES = setOf(
    InvalidationScope.PERSONAL_RECORDS,
    InvalidationScope.ONE_REP_MAX,
    InvalidationScope.VOLUME,
    InvalidationScope.CHARTS,
    InvalidationScope.STALLS,
    InvalidationScope.ACHIEVEMENTS,
)

private val DATE_SCOPES = setOf(
    InvalidationScope.STREAKS,
    InvalidationScope.WEEKLY_SUMMARY,
    InvalidationScope.CHARTS,
    InvalidationScope.PERSONAL_RECORDS,
    InvalidationScope.STALLS,
    InvalidationScope.ACHIEVEMENTS,
)

/** Derived data an edit can make stale. */
enum class InvalidationScope {
    PERSONAL_RECORDS,
    ONE_REP_MAX,
    VOLUME,
    MUSCLE_BALANCE,
    CHARTS,
    STREAKS,
    WEEKLY_SUMMARY,
    STALLS,
    ACHIEVEMENTS,
    COACH_SNAPSHOT,
}

/** Outcome of writing a [WorkoutEdit]. */
enum class EditOutcome {
    Applied,

    /** The stored workout is no longer the version the edit was based on; nothing was written. */
    Conflict,

    /** The session is gone (deleted); nothing was written. */
    NotFound,
}

/** Applies a [WorkoutEdit] as ONE local mutation (rows + outbox, chunked by the runner past 50 operations). */
interface WorkoutEditRepository : WorkoutReferenceChecker {
    /**
     * Re-reads the stored workout inside the mutation and returns [EditOutcome.Conflict] (writing
     * nothing) if any of its rows changed, appeared or disappeared since [original] was read.
     * Otherwise writes exactly the rows in [edit], each stamped with a fresh `updatedAt`.
     */
    suspend fun apply(original: WorkoutAggregate, edit: WorkoutEdit): EditOutcome
}
