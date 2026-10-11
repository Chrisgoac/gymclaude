package com.chrisgoac.gymlog.domain.workout

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Which unfinished session is THE active workout (controller ruling): the newest unfinished, live
 * session started at most [WINDOW_MILLIS] ago on the server-corrected clock. Older unfinished
 * sessions are abandoned: History lists them as "Sin terminar", they can be deleted or finished
 * with a typed duration, and they never block starting a new workout.
 */
object ActiveWorkoutPolicy {
    const val WINDOW_MILLIS: Long = 12L * 60 * 60 * 1000

    /** True while a session started at [startedAt] can still be the active one. */
    fun isFresh(startedAt: Long, now: Long): Boolean = startedAt >= now - WINDOW_MILLIS

    /** The instant a session started at [startedAt] stops being fresh. */
    fun staleAt(startedAt: Long): Long = startedAt + WINDOW_MILLIS + 1

    /** Bounds of the "finish with a duration" prompt for an abandoned session, in minutes. */
    const val DEFAULT_FINISH_MINUTES = 60
    const val MAX_FINISH_MINUTES = 24 * 60
}

/** A workout session. Unfinished = [durationSeconds] is null; see [ActiveWorkoutPolicy] for which one is active. */
data class WorkoutSession(
    val id: String,
    val routineId: String?,
    val gymId: String?,
    /** Start instant, epoch millis (server-corrected clock). */
    val startedAt: Long,
    val durationSeconds: Int?,
    val notes: String?,
    /** Local row version (`updatedAt`); 0 = unknown. Lets an editor detect that the row changed since it was read. */
    val updatedAt: Long = 0L,
)

data class WorkoutSet(
    val id: String,
    val position: Int,
    val weight: Double,
    val reps: Int,
    val warmup: Boolean,
    /** Local row version (`updatedAt`); 0 = unknown. */
    val updatedAt: Long = 0L,
)

/** An exercise inside a session with its ordered sets. [exerciseName] is null if the exercise no longer exists. */
data class WorkoutExercise(
    val id: String,
    val exerciseId: String,
    val exerciseName: String?,
    val position: Int,
    val sets: List<WorkoutSet>,
    /** Local row version (`updatedAt`); 0 = unknown. */
    val updatedAt: Long = 0L,
)

/** The session with its ordered exercises and their ordered sets, built from live rows only. */
data class WorkoutAggregate(
    val session: WorkoutSession,
    val exercises: List<WorkoutExercise>,
)

data class Gym(val id: String, val name: String)

/**
 * Result of a start request. [resumed] is true when an active workout already existed and no new
 * session was created: callers route back to [sessionId].
 */
data class StartedWorkout(val sessionId: String, val resumed: Boolean)

/** Typed start failures thrown by [WorkoutRepository]. */
sealed class WorkoutStartException(message: String) : RuntimeException(message) {
    class GymUnavailable(val gymId: String) : WorkoutStartException("Gym $gymId does not exist or was deleted")
    class RoutineNotFound(val routineId: String) : WorkoutStartException("Routine $routineId does not exist or was deleted")
    class RoutineArchived(val routineId: String) : WorkoutStartException("Routine $routineId is archived")
}

/** Sessions this week (Monday start, local zone) against the weekly goal, and the weekly streak. */
data class TrainingSummary(
    val weekSessions: Int,
    val weeklyGoal: Int,
    /** Consecutive weeks meeting the goal, counted back from this week (or last, if this one has not met it yet). */
    val streakWeeks: Int,
) {
    companion object {
        const val DEFAULT_WEEKLY_GOAL = 3

        /**
         * Port of `getResumenSemana` (sessions since Monday) and `calcularRacha` (lib/logros.ts):
         * a week counts when it has at least [goal] sessions; the current week below the goal
         * does not break the streak.
         */
        fun compute(sessionStarts: List<Long>, goal: Int, now: Long, zone: ZoneId): TrainingSummary {
            val effectiveGoal = goal.coerceAtLeast(1)
            fun weekIndex(millis: Long): Long {
                val monday = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                    .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                return Math.floorDiv(monday.toEpochDay(), 7L)
            }
            val perWeek = sessionStarts.groupingBy(::weekIndex).eachCount()
            val current = weekIndex(now)
            val meeting = perWeek.filterValues { it >= effectiveGoal }.keys
            var cursor = if (current in meeting) current else current - 1
            var streak = 0
            while (cursor in meeting) {
                streak++
                cursor--
            }
            return TrainingSummary(perWeek[current] ?: 0, effectiveGoal, streak)
        }
    }
}
