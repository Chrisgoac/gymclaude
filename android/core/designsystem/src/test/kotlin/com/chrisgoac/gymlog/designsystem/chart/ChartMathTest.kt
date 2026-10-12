package com.chrisgoac.gymlog.designsystem.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    @Test
    fun `equal values give a flat domain centred on the value without a zero span`() {
        val domain = ChartMath.lineDomain(listOf(80.0, 80.0, 80.0))
        assertTrue(domain.flat)
        assertTrue(domain.span > 0)
        assertEquals(0.5f, ChartMath.fraction(80.0, domain), 0.0001f)
    }

    @Test
    fun `a single value and zero values are also flat and centred`() {
        assertEquals(0.5f, ChartMath.fraction(0.0, ChartMath.lineDomain(listOf(0.0, 0.0))), 0.0001f)
        val single = ChartMath.lineDomain(listOf(42.5))
        assertTrue(single.flat)
        assertEquals(0.5f, ChartMath.fraction(42.5, single), 0.0001f)
    }

    @Test
    fun `an empty or non finite series has a safe domain`() {
        val domain = ChartMath.lineDomain(listOf(Double.NaN, Double.POSITIVE_INFINITY))
        assertTrue(domain.span > 0)
        assertEquals(0.5f, ChartMath.fraction(Double.NaN, domain), 0f)
    }

    @Test
    fun `the line domain spans the data and fractions stay inside the plot`() {
        val domain = ChartMath.lineDomain(listOf(60.0, 100.0, 80.0))
        assertFalse(domain.flat)
        assertEquals(0f, ChartMath.fraction(60.0, domain), 0f)
        assertEquals(1f, ChartMath.fraction(100.0, domain), 0f)
        assertEquals(1f, ChartMath.fraction(500.0, domain), 0f)
        assertEquals(0f, ChartMath.fraction(-500.0, domain), 0f)
    }

    @Test
    fun `bars are anchored at zero`() {
        val domain = ChartMath.barDomain(listOf(500.0, 1000.0))
        assertEquals(0.0, domain.min, 0.0)
        assertEquals(1000.0, domain.max, 0.0)
        assertEquals(0.5f, ChartMath.fraction(500.0, domain), 0f)
        val zeros = ChartMath.barDomain(listOf(0.0, 0.0))
        assertEquals(0f, ChartMath.fraction(0.0, zeros), 0f)
        assertEquals(0.0, ChartMath.barDomain(emptyList()).min, 0.0)
    }

    @Test
    fun `columns are equal width so one point is centred`() {
        assertEquals(0.5f, ChartMath.xFraction(0, 1), 0f)
        assertEquals(0.25f, ChartMath.xFraction(0, 2), 0f)
        assertEquals(0.75f, ChartMath.xFraction(1, 2), 0f)
        assertEquals(0.5f, ChartMath.xFraction(0, 0), 0f)
    }

    @Test
    fun `stats report min max last and trend`() {
        val stats = ChartMath.stats(listOf(ChartPoint("a", 10.0), ChartPoint("b", 30.0), ChartPoint("c", 20.0), ChartPoint("d", Double.NaN)))!!
        assertEquals(3, stats.count)
        assertEquals("a", stats.min.label)
        assertEquals("b", stats.max.label)
        assertEquals("c", stats.last.label)
        assertEquals(ChartTrend.Up, stats.trend)
    }

    @Test
    fun `trend of equal, single and empty series`() {
        assertEquals(ChartTrend.Flat, ChartMath.stats(listOf(ChartPoint("a", 5.0), ChartPoint("b", 5.0)))!!.trend)
        assertEquals(ChartTrend.Flat, ChartMath.stats(listOf(ChartPoint("a", 5.0)))!!.trend)
        assertEquals(ChartTrend.Down, ChartMath.stats(listOf(ChartPoint("a", 5.0), ChartPoint("b", 1.0)))!!.trend)
        assertNull(ChartMath.stats(emptyList()))
    }
}
