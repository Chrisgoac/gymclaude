package com.chrisgoac.gymlog.train

import org.junit.Assert.assertEquals
import org.junit.Test

class RestTimerBarTest {
    @Test fun `rest time is shown as m ss rounding up so it never reads zero early`() {
        assertEquals("1:30", formatRest(90_000))
        assertEquals("1:30", formatRest(89_001))
        assertEquals("0:01", formatRest(1))
        assertEquals("0:00", formatRest(0))
        assertEquals("0:00", formatRest(-5))
        assertEquals("10:05", formatRest(605_000))
    }
}
