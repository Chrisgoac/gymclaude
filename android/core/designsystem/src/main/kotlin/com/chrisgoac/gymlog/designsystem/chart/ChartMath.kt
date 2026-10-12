package com.chrisgoac.gymlog.designsystem.chart

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.max

/**
 * One datum of a chart: [label] is its x position in words ("03/08"), [value] its height, [detail]
 * optional extra text for the readout, table and TalkBack ("+12 % vs la anterior").
 */
@Immutable
data class ChartPoint(val label: String, val value: Double, val detail: String? = null) {
    /** "03/08: 4,2 t, +12 % vs la anterior": what every readout, table row and semantic node says. */
    fun spoken(formatValue: (Double) -> String): String =
        "$label: ${formatValue(value)}" + (detail?.let { ", $it" } ?: "")
}

enum class ChartTrend { Up, Down, Flat }

/** Vertical range of a chart. [flat] = every value equal: the line sits in the middle and only one axis label is shown. */
data class ChartDomain(val min: Double, val max: Double, val flat: Boolean) {
    val span: Double get() = max - min
}

/** Min / max / last / trend of a series, for the spoken summary and the table; null for an empty series. */
data class ChartStats(
    val count: Int,
    val min: ChartPoint,
    val max: ChartPoint,
    val last: ChartPoint,
    val first: ChartPoint,
    val trend: ChartTrend,
)

/**
 * Pure chart geometry: no Compose, no drawing, so empty, single-point and all-equal series are
 * unit-tested. Everything the canvas draws and everything the table lists comes from [clean] points.
 */
object ChartMath {
    /** Points with a finite value only (NaN and infinities never reach a domain or the canvas). */
    fun clean(points: List<ChartPoint>): List<ChartPoint> = points.filter { it.value.isFinite() }

    /**
     * Line domain: the data range itself. All values equal (including a single point) gives a flat
     * domain centred on the value, never a zero span.
     */
    fun lineDomain(values: List<Double>): ChartDomain {
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty()) return ChartDomain(0.0, 1.0, flat = true)
        val lo = finite.min()
        val hi = finite.max()
        if (lo == hi) {
            val pad = max(abs(lo) * 0.1, 1.0)
            return ChartDomain(lo - pad, hi + pad, flat = true)
        }
        return ChartDomain(lo, hi, flat = false)
    }

    /** Bar domain: always anchored at zero (negative values extend below it); an all-zero series spans 0..1. */
    fun barDomain(values: List<Double>): ChartDomain {
        val finite = values.filter { it.isFinite() }
        val lo = minOf(0.0, finite.minOrNull() ?: 0.0)
        val hi = maxOf(0.0, finite.maxOrNull() ?: 0.0)
        return if (hi == lo) ChartDomain(lo, lo + 1.0, flat = false) else ChartDomain(lo, hi, flat = false)
    }

    /** Position of [value] in [domain] as 0 (bottom) .. 1 (top); out-of-range values clamp, never overflow the plot. */
    fun fraction(value: Double, domain: ChartDomain): Float {
        if (!value.isFinite() || domain.span <= 0.0) return 0.5f
        return ((value - domain.min) / domain.span).toFloat().coerceIn(0f, 1f)
    }

    /** Horizontal centre of point [index] of [count] as 0..1: equal columns, so one point sits in the middle. */
    fun xFraction(index: Int, count: Int): Float = if (count <= 0) 0.5f else (index + 0.5f) / count

    fun stats(points: List<ChartPoint>): ChartStats? {
        val data = clean(points)
        if (data.isEmpty()) return null
        val first = data.first()
        val last = data.last()
        val trend = when {
            data.size < 2 -> ChartTrend.Flat
            last.value > first.value -> ChartTrend.Up
            last.value < first.value -> ChartTrend.Down
            else -> ChartTrend.Flat
        }
        return ChartStats(data.size, data.minBy { it.value }, data.maxBy { it.value }, last, first, trend)
    }
}
