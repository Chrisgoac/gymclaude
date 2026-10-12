package com.chrisgoac.gymlog.domain.timer

/**
 * Persisted rest-timer run. Time is wall-clock epoch millis, never a decrementing counter, so a
 * killed process, a time-zone change or a long background stay cannot drift it.
 *
 * Running: [pausedRemainingMillis] is null and the run ends at [endsAtEpochMillis].
 * Paused: [pausedRemainingMillis] holds what is left; [endsAtEpochMillis] is not meaningful.
 * [completionDelivered] is the exactly-once marker: it is stored before the completion event goes out.
 */
data class RestTimerState(
    val runId: String,
    val sessionId: String,
    val totalMillis: Long,
    val endsAtEpochMillis: Long,
    val pausedRemainingMillis: Long? = null,
    val completionDelivered: Boolean = false,
)

/** Pure rest-timer rules; every function takes [now] explicitly. */
object RestTimer {
    fun startOrNull(runId: String, sessionId: String, now: Long, seconds: Int?): RestTimerState? =
        if (seconds == null || seconds <= 0) null else start(runId, sessionId, now, seconds)

    fun start(runId: String, sessionId: String, now: Long, seconds: Int): RestTimerState {
        val total = seconds * 1000L
        return RestTimerState(runId, sessionId, total, endsAtEpochMillis = now + total)
    }

    fun isPaused(state: RestTimerState) = state.pausedRemainingMillis != null

    /** `max(0, endsAt - now)`, also capped at the total so a clock set backwards cannot inflate it. */
    fun remaining(state: RestTimerState, now: Long): Long =
        state.pausedRemainingMillis ?: (state.endsAtEpochMillis - now).coerceIn(0L, state.totalMillis)

    fun isFinished(state: RestTimerState, now: Long) = !isPaused(state) && (state.completionDelivered || now >= state.endsAtEpochMillis)

    fun pause(state: RestTimerState, now: Long): RestTimerState =
        if (isPaused(state) || isFinished(state, now)) state else state.copy(pausedRemainingMillis = remaining(state, now))

    fun resume(state: RestTimerState, now: Long): RestTimerState {
        val left = state.pausedRemainingMillis ?: return state
        return state.copy(endsAtEpochMillis = now + left, pausedRemainingMillis = null)
    }

    /**
     * Adds [seconds]. A finished run starts a new one ([nextRunId]) from now, so its completion
     * can be delivered again.
     */
    fun extend(state: RestTimerState, now: Long, seconds: Int, nextRunId: String): RestTimerState {
        if (seconds <= 0) return state
        val extra = seconds * 1000L
        return when {
            isPaused(state) -> state.copy(totalMillis = state.totalMillis + extra, pausedRemainingMillis = state.pausedRemainingMillis!! + extra)
            isFinished(state, now) -> RestTimerState(nextRunId, state.sessionId, extra, endsAtEpochMillis = now + extra)
            else -> state.copy(totalMillis = state.totalMillis + extra, endsAtEpochMillis = state.endsAtEpochMillis + extra)
        }
    }

    /** True once a running, undelivered run has reached its end. */
    fun completionDue(state: RestTimerState, now: Long) =
        !isPaused(state) && !state.completionDelivered && now >= state.endsAtEpochMillis

    fun markDelivered(state: RestTimerState) = state.copy(completionDelivered = true)
}
