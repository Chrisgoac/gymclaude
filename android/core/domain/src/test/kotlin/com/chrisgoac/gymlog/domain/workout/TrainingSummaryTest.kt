package com.chrisgoac.gymlog.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TrainingSummaryTest {
    private val zone = ZoneId.of("Europe/Madrid")
    private fun at(day: Int, hour: Int = 10) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    // 2026-10-12 is a Monday.
    private val wednesday = at(14)

    @Test fun `counts sessions since Monday in the local zone`() {
        val sundayBefore = at(11, 23)
        val monday = at(12, 0)
        val summary = TrainingSummary.compute(listOf(sundayBefore, monday, at(13)), goal = 3, now = wednesday, zone = zone)
        assertEquals(2, summary.weekSessions)
    }

    @Test fun `streak counts consecutive weeks meeting the goal back from last week when this one has not met it`() {
        val sessions = listOf(at(5), at(6), at(7), at(12)) // last week: 3, this week: 1
        val older = listOf(at(1, 9), at(2), at(3)) // two weeks ago: 3
        val summary = TrainingSummary.compute(sessions + older, goal = 3, now = wednesday, zone = zone)
        assertEquals(1, summary.weekSessions)
        assertEquals(2, summary.streakWeeks)
    }

    @Test fun `this week meeting the goal extends the streak and a gap breaks it`() {
        val thisWeek = listOf(at(12), at(13), at(14))
        val gapWeeks = listOf(at(5), at(6), at(7)).map { it - 7L * 24 * 3600 * 1000 } // skips last week
        val summary = TrainingSummary.compute(thisWeek + gapWeeks, goal = 3, now = wednesday, zone = zone)
        assertEquals(1, summary.streakWeeks)
    }

    @Test fun `no sessions means zero and the goal is at least one`() {
        val summary = TrainingSummary.compute(emptyList(), goal = 0, now = wednesday, zone = zone)
        assertEquals(TrainingSummary(0, 1, 0), summary)
    }
}
