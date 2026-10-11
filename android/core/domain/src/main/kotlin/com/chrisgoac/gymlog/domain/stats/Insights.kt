package com.chrisgoac.gymlog.domain.stats

import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.math.BodyEntry
import com.chrisgoac.gymlog.domain.math.Numbers

/** Outcome of [Insights.detectStall] (`Estancamiento`). */
data class StallDetection(val stalled: Boolean, val sessionsWithoutImprovement: Int, val lastImprovementAt: Long?)

/** One row of the muscle balance (`FilaEquilibrio`). */
data class BalanceRow(
    val group: MuscleGroup,
    /** Volume (kg·rep) of the selected period. */
    val volume: Double,
    /** Whole days since the last session that trained it; null if never. */
    val daysSinceTrained: Long?,
    /** Never trained (in this gym) or not for more than [Insights.NEGLECTED_AFTER_DAYS] days. */
    val neglected: Boolean,
    /** % of the weekly volume goal reached this week (capped at 100); null without a positive goal. */
    val goalPct: Int?,
)

/** A suggestion attached to a stalled exercise: the PWA's deload advice ([Insights.DELOAD_ADVICE]). */
data class ProgressionSuggestion(val exerciseId: String, val name: String, val sessionsWithoutImprovement: Int, val advice: String)

/** A body measurement series for the Coach: [entries] oldest first. */
data class BodySeries(val type: String, val label: String, val entries: List<BodyEntry>)

/** Inputs of [Insights.coachSnapshot] (`SnapshotInput`); [stalls] already sorted worst first. */
data class CoachSnapshotInput(
    val stalls: List<Stall>,
    val week: WeeklySummary,
    val weeklyTarget: Int,
    val weekRecords: List<WeekRecord>,
    val weekVolumeByGroup: Map<MuscleGroup, Double>,
    val lastTrained: Map<MuscleGroup, Long?>,
    val volumeTargets: Map<MuscleGroup, Double>,
    val now: Long,
    val body: List<BodySeries>,
)

/** Compact context sent to the Coach (`CoachSnapshot`); labels are the PWA's Spanish ones. */
data class CoachSnapshot(val stalls: List<Line>, val week: Week, val groups: List<Group>, val body: Body) {
    data class Line(val exercise: String, val sessionsWithoutImprovement: Int)
    data class Week(val sessions: Int, val target: Int, val volume: Long, val deltaPct: Int?, val records: List<Record>)
    data class Record(val exercise: String, val kind: RecordKind)
    data class Group(val group: String, val weekVolume: Long, val daysWithoutTraining: Long?, val target: Double?)
    data class Measure(val metric: String, val current: Double, val delta4Weeks: Double?)
    data class Weight(val current: Double, val delta4Weeks: Double?)
    data class Body(val weight: Weight?, val measures: List<Measure>)
}

/** Pure ports of `lib/insights.ts` and `lib/coach-snapshot.ts`; shared fixture: contracts/domain/insight-cases.json. */
object Insights {
    /** Shown with a stalled exercise (`DELOAD_CONSEJO`). */
    const val DELOAD_ADVICE = "Prueba bajar ~10% y vuelve a subir, o cambia de ejercicio."

    /** A group not trained for more than this many days is neglected (`UMBRAL_DESCUIDADO_DIAS`). */
    const val NEGLECTED_AFTER_DAYS = 10L

    private const val DAY_MILLIS = 86_400_000L
    private const val MAX_STALLS = 5
    private const val MAX_GROUPS = 8
    private const val MAX_MEASURES = 6
    private const val BODY_WINDOW_MILLIS = 28 * DAY_MILLIS
    private const val WEIGHT_TYPE = "peso"

    /**
     * An exercise is stalled when the best estimated 1RM of its last [n] sessions does not beat the
     * best before them (`detectarEstancamiento`); needs at least n+1 sessions. [points] oldest first.
     */
    fun detectStall(points: List<ProgressPoint>, n: Int = 3): StallDetection {
        if (points.size < n + 1) return StallDetection(false, 0, null)
        var runningMax = Double.NEGATIVE_INFINITY
        var lastImprovement = 0
        points.forEachIndexed { i, p ->
            if (p.bestOneRepMax > runningMax) {
                runningMax = p.bestOneRepMax
                lastImprovement = i
            }
        }
        val recent = points.takeLast(n).maxOf { it.bestOneRepMax }
        val before = points.dropLast(n).maxOf { it.bestOneRepMax }
        return StallDetection(recent <= before, points.size - 1 - lastImprovement, points[lastImprovement].at)
    }

    /** The deload advice for each stalled exercise, in the same order. */
    fun suggestions(stalls: List<Stall>): List<ProgressionSuggestion> =
        stalls.map { ProgressionSuggestion(it.exerciseId, it.name, it.sessionsWithoutImprovement, DELOAD_ADVICE) }

    /**
     * Muscle balance rows (`filasEquilibrio`, as the Progress screen calls it): groups with volume in
     * the period or trained at some point (so neglected ones surface), largest volume first (ties in
     * [MuscleGroup] order). [goals] are weekly volume goals compared with [weekVolume].
     */
    fun balance(
        periodVolume: List<MuscleVolume>,
        lastTrained: Map<MuscleGroup, Long?>,
        now: Long,
        goals: Map<MuscleGroup, Double>,
        weekVolume: Map<MuscleGroup, Double>,
    ): List<BalanceRow> {
        val volumes = periodVolume.associate { it.group to it.volume }
        return MuscleGroup.entries
            .filter { it in volumes || lastTrained[it] != null }
            .sortedByDescending { volumes[it] ?: 0.0 }
            .map { group ->
                val last = lastTrained[group]
                val days = last?.let { Math.floorDiv(now - it, DAY_MILLIS) }
                val goal = goals[group]
                val pct = if (goal != null && goal > 0) {
                    minOf(100, Numbers.jsRound(((weekVolume[group] ?: 0.0) / goal) * 100).toInt())
                } else {
                    null
                }
                BalanceRow(group, volumes[group] ?: 0.0, days, days == null || days > NEGLECTED_AFTER_DAYS, pct)
            }
    }

    /** `construirSnapshot`: the five worst stalls, the week, up to eight groups and the body summary. */
    fun coachSnapshot(input: CoachSnapshotInput): CoachSnapshot {
        val stalls = input.stalls.take(MAX_STALLS).map { CoachSnapshot.Line(it.name, it.sessionsWithoutImprovement) }
        val week = CoachSnapshot.Week(
            sessions = input.week.sessions,
            target = input.weeklyTarget,
            volume = Numbers.jsRound(input.week.volume).toLong(),
            deltaPct = input.week.deltaPct,
            records = input.weekRecords.map { CoachSnapshot.Record(it.name, it.kind) },
        )
        val groups = MuscleGroup.entries
            .filter { (input.weekVolumeByGroup[it] ?: 0.0) > 0 || it in input.volumeTargets || input.lastTrained[it] != null }
            .sortedByDescending { input.weekVolumeByGroup[it] ?: 0.0 }
            .take(MAX_GROUPS)
            .map { group ->
                val last = input.lastTrained[group]
                CoachSnapshot.Group(
                    group = group.label,
                    weekVolume = Numbers.jsRound(input.weekVolumeByGroup[group] ?: 0.0).toLong(),
                    daysWithoutTraining = last?.let { Math.floorDiv(input.now - it, DAY_MILLIS) },
                    target = input.volumeTargets[group],
                )
            }
        val weight = input.body.firstOrNull { it.type == WEIGHT_TYPE }?.let { bodySummary(it.entries, input.now) }
        val measures = input.body
            .filter { it.type != WEIGHT_TYPE && it.entries.isNotEmpty() }
            .sortedByDescending { it.entries.last().at }
            .take(MAX_MEASURES)
            .map { series ->
                val summary = checkNotNull(bodySummary(series.entries, input.now))
                CoachSnapshot.Measure(series.label, summary.current, summary.delta4Weeks)
            }
        return CoachSnapshot(stalls, week, groups, CoachSnapshot.Body(weight, measures))
    }

    /** Latest value and its change against the oldest entry of the last 28 days (null with fewer than two there). */
    private fun bodySummary(entries: List<BodyEntry>, now: Long): CoachSnapshot.Weight? {
        if (entries.isEmpty()) return null
        val current = entries.last().value
        val window = entries.filter { it.at.toEpochMilli() >= now - BODY_WINDOW_MILLIS }
        return CoachSnapshot.Weight(current, if (window.size >= 2) current - window.first().value else null)
    }
}
