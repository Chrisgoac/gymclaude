package com.chrisgoac.gymlog.domain.time

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class DayRolloverTest {
    private val madrid = ZoneId.of("Europe/Madrid")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(madrid).toInstant().toEpochMilli()

    @Test
    fun `time until the next local midnight`() {
        assertEquals(60 * 60 * 1000L, DayRollover.millisUntilNextDay(at("2026-06-10T23:00:00"), madrid))
        assertEquals(24 * 60 * 60 * 1000L, DayRollover.millisUntilNextDay(at("2026-06-10T00:00:00"), madrid))
    }

    @Test
    fun `the spring-forward day is 23 hours long`() {
        // 2026-03-29 is Madrid's DST start: midnight + 23 h is the next midnight.
        assertEquals(23 * 60 * 60 * 1000L, DayRollover.millisUntilNextDay(at("2026-03-29T00:00:00"), madrid))
    }

    @Test
    fun `ticks now and again just after every midnight`() = runTest {
        val start = at("2026-06-10T22:00:00")
        val ticks = DayRollover.ticks(now = { start + currentTime }, zone = { madrid }).take(3).toList()
        assertEquals(3, ticks.size)
        // 2 h to the first midnight + slack; the second wait re-reads the clock (1 s into the new day) so it lands exactly 24 h later.
        assertEquals(26 * 60 * 60 * 1000L + 1_000, currentTime)
    }
}
