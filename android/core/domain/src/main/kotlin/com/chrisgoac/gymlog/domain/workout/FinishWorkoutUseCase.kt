package com.chrisgoac.gymlog.domain.workout

import com.chrisgoac.gymlog.domain.timer.RestTimerController
import kotlinx.coroutines.flow.first

/** What a finished workout amounted to. [volume] = Σ weight × reps over every live set (PWA `listSessionSummaries`, warm-ups included). */
data class WorkoutSummary(
    val sessionId: String,
    val durationSeconds: Int,
    val exerciseCount: Int,
    val setCount: Int,
    val volume: Double,
)

/** A set that counts as work done: at least one repetition. */
fun WorkoutSet.isMeaningful(): Boolean = reps >= 1

/** True when at least one set has reps >= 1. */
fun WorkoutAggregate.hasMeaningfulSet(): Boolean = exercises.any { e -> e.sets.any { it.isMeaningful() } }

/** The summary of this aggregate for the given duration. */
fun WorkoutAggregate.summary(durationSeconds: Int): WorkoutSummary = WorkoutSummary(
    sessionId = session.id,
    durationSeconds = durationSeconds,
    exerciseCount = exercises.size,
    setCount = exercises.sumOf { it.sets.size },
    volume = exercises.sumOf { e -> e.sets.sumOf { it.weight * it.reps } },
)

/** Completion write of a workout session. */
interface WorkoutFinishRepository {
    /**
     * Sets the session's duration (server-corrected clock, `round((now - start) / 1000)` as the PWA)
     * and, when [notes] is not null, its notes (blank clears) in ONE local mutation. Finishing an
     * already finished session writes nothing and returns the stored duration.
     * @throws WorkoutRowMissingException; [IllegalArgumentException] for notes over [WorkoutLimits.NOTES_MAX]
     */
    suspend fun finish(sessionId: String, notes: String?): Int
}

sealed interface FinishResult {
    /** [alreadyFinished] = nothing was written (a repeated tap, or finished from another device). */
    data class Finished(val summary: WorkoutSummary, val alreadyFinished: Boolean) : FinishResult

    /** No set has a repetition: ask "¿Terminar sin series?" and call again with `confirmEmpty = true`. */
    data object NeedsConfirmation : FinishResult

    data object NotFound : FinishResult
}

/**
 * Finishes the workout: idempotent, asks for confirmation when nothing meaningful was logged,
 * persists duration + notes atomically and then clears the rest timer.
 */
class FinishWorkoutUseCase(
    private val workouts: WorkoutRepository,
    private val finisher: WorkoutFinishRepository,
    private val restTimer: RestTimerController? = null,
    /** Called once after a finish that wrote (never for a repeated tap): derived data such as achievements is now stale. */
    private val onFinished: () -> Unit = {},
) {
    suspend operator fun invoke(sessionId: String, notes: String? = null, confirmEmpty: Boolean = false): FinishResult {
        val before = readLive(sessionId) ?: return FinishResult.NotFound
        before.session.durationSeconds?.let { stored ->
            restTimer?.reset()
            return FinishResult.Finished(before.summary(stored), alreadyFinished = true)
        }
        if (!confirmEmpty && !before.hasMeaningfulSet()) return FinishResult.NeedsConfirmation

        val duration = try {
            finisher.finish(sessionId, notes)
        } catch (_: WorkoutRowMissingException) {
            return FinishResult.NotFound
        }
        restTimer?.reset()
        onFinished()
        // Totals come from what is stored now (a sync may have changed rows since [before]).
        val after = readLive(sessionId) ?: before
        return FinishResult.Finished(after.summary(duration), alreadyFinished = false)
    }

    private suspend fun readLive(sessionId: String): WorkoutAggregate? =
        workouts.observeWorkout(sessionId).first()
}
