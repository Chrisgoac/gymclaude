package com.chrisgoac.gymlog.domain.time

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId

/**
 * Derived "today / this week" data goes stale when the local date changes with nothing written.
 * [ticks] emits once immediately and again just after every local midnight in the device zone
 * (Monday's midnight starts a new week, so one ticker covers both), re-reading [now] and [zone]
 * each time so DST and zone changes are honoured.
 */
object DayRollover {
    /** Slack after midnight so the re-read clearly lands on the new day. */
    const val SLACK_MILLIS = 1_000L

    /** Milliseconds from [now] to the next local midnight in [zone] (always > 0). */
    fun millisUntilNextDay(now: Long, zone: ZoneId): Long {
        val next = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return next - now
    }

    fun ticks(now: () -> Long, zone: () -> ZoneId): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(millisUntilNextDay(now(), zone()) + SLACK_MILLIS)
        }
    }
}
