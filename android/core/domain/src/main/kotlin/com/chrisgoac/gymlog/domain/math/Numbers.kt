package com.chrisgoac.gymlog.domain.math

import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor

/**
 * Port of `lib/num.ts` plus the PWA's estimated 1RM; shared fixture: contracts/domain/num-cases.json.
 * Values mirror JavaScript `Number()` semantics (hex/binary/octal prefixes, exponents, "Infinity"),
 * which is what the PWA forms accept. Strict field validation for set entry stays in
 * [com.chrisgoac.gymlog.domain.workout.SetValidation].
 */
object Numbers {
    private val DECIMAL = Regex("""^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$""")
    private val INFINITY = Regex("""^[+-]?Infinity$""")
    private val RADIX = Regex("""^0([xX][0-9a-fA-F]+|[bB][01]+|[oO][0-7]+)$""")

    /** `Number(text)`, or null for NaN. Blank text is 0 like JS, but callers here reject blanks first. */
    private fun jsNumber(text: String): Double? {
        val s = text.trim { it.isWhitespace() || it == '﻿' }
        return when {
            s.isEmpty() -> 0.0
            INFINITY.matches(s) -> if (s.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            RADIX.matches(s) -> s.substring(2).toBigInteger(
                when (s[1].lowercaseChar()) { 'x' -> 16; 'b' -> 2; else -> 8 },
            ).toDouble()
            DECIMAL.matches(s) -> s.toDouble()
            else -> null
        }
    }

    /** Integer >= 0, or null when blank/not numeric (series, reps, rest). Like the PWA this can be [Double.POSITIVE_INFINITY] for "Infinity"/"1e999". */
    fun parseIntegerOrNull(text: String): Double? {
        if (text.isBlank()) return null
        val n = jsNumber(text) ?: return null
        return maxOf(0.0, jsRound(n))
    }

    /** Integer >= 0, 0 when blank/not numeric. */
    fun parseInteger(text: String): Double = parseIntegerOrNull(text) ?: 0.0

    /** Number >= 0 keeping decimals, 0 when blank/not numeric (weight). */
    fun parseDecimal(text: String): Double {
        if (text.isBlank()) return 0.0
        val n = jsNumber(text) ?: return 0.0
        return maxOf(0.0, n)
    }

    /** JavaScript `Math.round` (ties toward +infinity). */
    fun jsRound(value: Double): Double {
        val lower = floor(value)
        return if (value - lower >= 0.5) lower + 1 else lower
    }

    /** Rounded to one decimal the way the PWA does (`Math.round(x * 10) / 10`). */
    fun roundToTenth(value: Double): Double = jsRound(value * 10) / 10
}

/** Estimated one-rep max, Epley exactly as `estimar1RM` in `lib/repositories/stats.ts`. */
object OneRepMax {
    /** [weightKg] for up to one rep is returned untouched; otherwise `w * (1 + reps / 30)` rounded to 0.1. */
    fun estimate(weightKg: Double, reps: Int): Double =
        if (reps <= 1) weightKg else Numbers.roundToTenth(weightKg * (1 + reps / 30.0))
}

/** Display unit for weights. Storage is always kilograms as `Double`; only the screen converts. */
enum class WeightUnit(val wire: String, val symbol: String, private val kgPerUnit: Double) {
    KG("kg", "kg", 1.0), LB("lb", "lb", 0.45359237);

    /** Kilograms -> this unit, unrounded (use [Numbers.roundToTenth] to show). Never mutates the stored value. */
    fun fromKg(kg: Double): Double = if (this == KG) kg else kg / kgPerUnit

    /** Value typed in this unit -> kilograms for storage, unrounded. */
    fun toKg(value: Double): Double = if (this == KG) value else value * kgPerUnit

    /** [kg] converted for display and rounded to one decimal. */
    fun display(kg: Double): Double = Numbers.roundToTenth(fromKg(kg))

    companion object {
        /** Unknown or missing values fall back to [KG]. */
        fun fromWire(value: String?): WeightUnit = entries.firstOrNull { it.wire == value } ?: KG
    }
}

/** One stored body measurement: [value] in the metric's storage unit (kg for weight, cm otherwise). */
data class BodyEntry(val value: Double, val at: Instant)

data class BodyPoint(val label: String, val value: Double)

/** Port of `resumenSerie` (`lib/body-stats.ts`); shared fixture: contracts/domain/body-stats-cases.json. */
data class BodySummary(val current: Double?, val first: Double?, val delta: Double?, val points: List<BodyPoint>) {
    companion object {
        /**
         * [entries] are active measurements in chronological order, as the PWA passes them (not re-sorted).
         * Labels are the local "d/M" date in [zone] (es-ES style, no zero padding).
         */
        fun of(entries: List<BodyEntry>, zone: ZoneId): BodySummary {
            if (entries.isEmpty()) return BodySummary(null, null, null, emptyList())
            val first = entries.first().value
            val current = entries.last().value
            val delta = if (entries.size >= 2) current - first else null
            val points = entries.map {
                val date = it.at.atZone(zone).toLocalDate()
                BodyPoint("${date.dayOfMonth}/${date.monthValue}", it.value)
            }
            return BodySummary(current, first, delta, points)
        }
    }
}
