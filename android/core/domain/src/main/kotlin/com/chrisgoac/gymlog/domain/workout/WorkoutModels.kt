package com.chrisgoac.gymlog.domain.workout

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A workout session. Active = [durationSeconds] is null (not finished) and not deleted. */
data class WorkoutSession(
    val id: String,
    val routineId: String?,
    val gymId: String?,
    /** Start instant, epoch millis (server-corrected clock). */
    val startedAt: Long,
    val durationSeconds: Int?,
    val notes: String?,
)

data class WorkoutSet(
    val id: String,
    val position: Int,
    val weight: Double,
    val reps: Int,
    val warmup: Boolean,
)

/** An exercise inside a session with its ordered sets. [exerciseName] is null if the exercise no longer exists. */
data class WorkoutExercise(
    val id: String,
    val exerciseId: String,
    val exerciseName: String?,
    val position: Int,
    val sets: List<WorkoutSet>,
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
