package com.chrisgoac.gymlog.domain.time

import com.chrisgoac.gymlog.domain.math.Numbers
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlin.math.floor

/** Progress range selector (`Periodo` in `lib/period.ts`); wire ids match the PWA. */
enum class Period(val wire: String, val days: Long) {
    FOUR_WEEKS("4s", 28), THREE_MONTHS("3m", 90), YEAR("ano", 365), ALL("todo", 0);

    companion object {
        fun fromWire(value: String): Period? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Pure date helpers ported from `lib/period.ts` and `lib/fecha.ts`; shared fixtures:
 * contracts/domain/{period,fecha}-cases.json. Nothing reads the device clock or zone: callers pass
 * the (server-corrected) [Instant] and the user's [ZoneId].
 */
object Periods {
    private const val DAY_MILLIS = 86_400_000L

    /** Start of [period] ending at [now]: a fixed number of 24 h days back (not calendar days); [Instant.EPOCH] for [Period.ALL] (no lower bound). */
    fun since(period: Period, now: Instant): Instant =
        if (period.days == 0L) Instant.EPOCH else Instant.ofEpochMilli(now.toEpochMilli() - period.days * DAY_MILLIS)

    /** Monday (local date in [zone]) of the ISO week containing [at]. */
    fun mondayOf(at: Instant, zone: ZoneId): LocalDate =
        at.atZone(zone).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Monday 00:00 in [zone] of the week containing [at] (`inicioSemana`); DST-safe because it goes through the local date. */
    fun weekStart(at: Instant, zone: ZoneId): Instant = mondayOf(at, zone).atStartOfDay(zone).toInstant()

    /** Whole local calendar days from [at] to [now], 0 or negative when [at] is today or later (`formatHaceDias` arithmetic). */
    fun daysAgo(at: Instant, now: Instant, zone: ZoneId): Long {
        val today = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val day = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return Numbers.jsRound((today - day).toDouble() / DAY_MILLIS).toLong()
    }

    /** "hoy" / "ayer" / "hace N días" like the PWA; a future date also reads "hoy". */
    fun formatDaysAgo(at: Instant, now: Instant, zone: ZoneId): String {
        val days = daysAgo(at, now, zone)
        return when {
            days <= 0 -> "hoy"
            days == 1L -> "ayer"
            else -> "hace $days días"
        }
    }

    /** Seconds -> "m:ss" ("1:05"); negatives are 0, minutes are not capped ("62:05"). */
    fun formatSeconds(seconds: Double): String {
        val total = floor(maxOf(0.0, seconds)).toLong()
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
