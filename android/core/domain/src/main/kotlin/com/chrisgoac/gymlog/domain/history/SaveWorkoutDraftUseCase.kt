package com.chrisgoac.gymlog.domain.history

import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate

/** Outcome of saving a historical edit. */
sealed interface SaveResult {
    /**
     * Stored as one logical mutation, or nothing to store ([invalidatedScopes] empty). The scopes say
     * which derived data (PRs, 1RM, volume, balance, charts, streaks, weekly summary, stalls,
     * achievements, Coach snapshot) must be recomputed.
     */
    data class Saved(val invalidatedScopes: Set<InvalidationScope>) : SaveResult

    /** Nothing was written; [summary] lists every problem, per field. */
    data class Invalid(val summary: DraftValidationSummary) : SaveResult

    /** The workout changed elsewhere (e.g. sync) since the draft was opened; nothing was written. */
    data object Conflict : SaveResult

    /** The workout was deleted meanwhile; nothing was written. */
    data object NotFound : SaveResult

    /** The workout is still in progress (not finished): it is edited from the active-workout screen. */
    data object NotEditable : SaveResult
}

/** Saves an edited past workout atomically; [original] is the version the draft was opened from. */
interface SaveWorkoutDraftUseCase {
    suspend operator fun invoke(original: WorkoutAggregate, draft: WorkoutDraft): SaveResult
}

/**
 * Validates everything first (values, date against the server clock [now], references), then
 * writes the difference between [original] and the draft in ONE mutation. An unchanged draft
 * writes nothing.
 */
class DefaultSaveWorkoutDraftUseCase(
    private val repository: WorkoutEditRepository,
    private val now: () -> Long,
) : SaveWorkoutDraftUseCase {
    override suspend fun invoke(original: WorkoutAggregate, draft: WorkoutDraft): SaveResult {
        require(draft.sessionId == original.session.id) { "Draft ${draft.sessionId} is not for session ${original.session.id}" }
        if (original.session.durationSeconds == null) return SaveResult.NotEditable
        val summary = WorkoutDraftValidation.validate(original, draft, now(), repository)
        if (!summary.isValid) return SaveResult.Invalid(summary)
        val edit = WorkoutEdit.between(original, draft)
        if (edit.isEmpty) return SaveResult.Saved(emptySet())
        return when (repository.apply(original, edit)) {
            EditOutcome.Applied -> SaveResult.Saved(edit.invalidatedScopes(original))
            EditOutcome.Conflict -> SaveResult.Conflict
            EditOutcome.NotFound -> SaveResult.NotFound
        }
    }
}
