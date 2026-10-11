package com.chrisgoac.gymlog.domain.stats

import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.math.Numbers
import com.chrisgoac.gymlog.domain.math.OneRepMax
import com.chrisgoac.gymlog.domain.time.Periods
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Progress gym filter (`lib/gym-filter.ts`): every gym, or the sessions of one gym. */
sealed interface GymFilter {
    /** The gym id the stats filter on; null = every gym (`filtroAGymId`). */
    val gymId: String?

    data object All : GymFilter {
        override val gymId: String? = null
    }

    data class Gym(val id: String) : GymFilter {
        override val gymId: String get() = id
    }

    companion object {
        /** Stored filter value -> filter; "all" (the PWA default) and null mean every gym. Any other value is a gym id, even "". */
        fun fromWire(value: String?): GymFilter = if (value == null || value == ALL_WIRE) All else Gym(value)

        const val ALL_WIRE = "all"
    }
}

/** A live session (not tombstoned). Unfinished sessions count like the PWA (it never filters on duration). */
data class StatSession(val id: String, val startedAt: Long, val gymId: String?)

/** A live logged exercise of a live session, denormalized with its session. One without sets still counts as training its group. */
data class StatExercise(val id: String, val sessionId: String, val exerciseId: String, val startedAt: Long, val gymId: String?)

/**
 * A live set of a live logged exercise of a live session, denormalized with both. Warm-ups
 * ([warmup]) are kept: the PWA counts them in every progress metric (only the next-set suggestion
 * skips them).
 */
data class StatSet(
    val id: String,
    val loggedExerciseId: String,
    val sessionId: String,
    val exerciseId: String,
    val startedAt: Long,
    val gymId: String?,
    val weight: Double,
    val reps: Int,
    val warmup: Boolean,
)

/**
 * Rows the reducers read: live rows only, children of tombstoned parents excluded. Every reducer
 * applies its own gym/date filter exactly like the PWA, so a caller may pass any superset of the
 * rows a query needs (the Room repository narrows with SQL).
 */
data class StatRows(val sessions: List<StatSession>, val exercises: List<StatExercise>, val sets: List<StatSet>) {
    companion object {
        val EMPTY = StatRows(emptyList(), emptyList(), emptyList())
    }
}

/** Name and group of an exercise; [muscleGroup] null = a group the PWA does not know (counted nowhere). */
data class ExerciseInfo(val name: String, val muscleGroup: MuscleGroup?)

data class ProgressPoint(val at: Long, val maxWeight: Double, val bestOneRepMax: Double, val volume: Double)

data class ExerciseRecords(val maxWeight: Double, val bestOneRepMax: Double)

data class MuscleVolume(val group: MuscleGroup, val volume: Double)

data class PeriodSummary(val sessions: Int, val volume: Double)

data class WeeklyVolume(val weekStart: Long, val volume: Double)

data class WeeklyVolumeDelta(val weekStart: Long, val volume: Double, val deltaPct: Int?)

data class WeeklySummary(val sessions: Int, val volume: Double, val previousVolume: Double, val deltaPct: Int?)

enum class RecordKind(val wire: String) { WEIGHT("peso"), ONE_REP_MAX("1rm") }

/** An exercise that beat its previous best this week. */
data class WeekRecord(val exerciseId: String, val name: String, val kind: RecordKind)

/** A stalled exercise (`Estancado`). */
data class Stall(val exerciseId: String, val name: String, val sessionsWithoutImprovement: Int, val lastImprovementAt: Long?)

data class WeeklyStreak(val current: Int, val best: Int)

/**
 * Pure ports of the progress statistics in `lib/repositories/stats.ts`; shared fixture:
 * contracts/domain/insight-cases.json. Times are epoch ms; the clock ([Instant]) and the user's
 * [ZoneId] are always explicit.
 *
 * Float sums follow the PWA's iteration order (logged exercises, then sets, by id; sessions by id),
 * so volumes match the PWA to the last bit for the same rows. A logged exercise without sets counts
 * as training its group (last trained, a 0 in the period's group volume), like the PWA.
 */
object WorkoutStats {
    const val UNKNOWN_NAME = "—"
    private const val WEEK_MILLIS = 7L * 86_400_000L

    private fun StatSet.inGym(gym: GymFilter) = gym.gymId == null || gymId == gym.gymId
    private fun StatExercise.inGym(gym: GymFilter) = gym.gymId == null || gymId == gym.gymId
    private fun StatSession.inGym(gym: GymFilter) = gym.gymId == null || gymId == gym.gymId
    private val SET_ORDER = compareBy<StatSet>({ it.loggedExerciseId }, { it.id })

    /**
     * Volume of each logged exercise in [exercises] (0 without sets), in logged-exercise id order,
     * each summed over its sets in id order: the PWA's `les.forEach(reduce over its sets)`.
     */
    private fun volumes(exercises: List<StatExercise>, rows: StatRows): List<Pair<StatExercise, Double>> {
        val setsByExercise = rows.sets.groupBy { it.loggedExerciseId }
        return exercises.sortedBy { it.id }.map { le ->
            le to setsByExercise[le.id].orEmpty().sortedBy { it.id }.fold(0.0) { acc, s -> acc + s.weight * s.reps }
        }
    }

    /** Per-date points of one exercise since [since] (`getExerciseProgress`): same-`startedAt` sessions merge into one point. */
    fun exerciseProgress(rows: StatRows, exerciseId: String, gym: GymFilter, since: Long): List<ProgressPoint> {
        val byDate = LinkedHashMap<Long, MutableList<StatSet>>()
        rows.sets.filter { it.exerciseId == exerciseId && it.inGym(gym) }.sortedWith(SET_ORDER)
            .forEach { byDate.getOrPut(it.startedAt) { mutableListOf() }.add(it) }
        return byDate.map { (at, sets) ->
            ProgressPoint(
                at = at,
                maxWeight = sets.maxOf { it.weight },
                bestOneRepMax = sets.maxOf { OneRepMax.estimate(it.weight, it.reps) },
                volume = sets.fold(0.0) { acc, s -> acc + s.weight * s.reps },
            )
        }.filter { it.at >= since }.sortedBy { it.at }
    }

    /** Heaviest weight and best estimated 1RM of one exercise (`getExercisePRs`); null without sets. */
    fun exerciseRecords(rows: StatRows, exerciseId: String, gym: GymFilter): ExerciseRecords? {
        val sets = rows.sets.filter { it.exerciseId == exerciseId && it.inGym(gym) }
        if (sets.isEmpty()) return null
        return ExerciseRecords(
            maxWeight = sets.fold(0.0) { acc, s -> maxOf(acc, s.weight) },
            bestOneRepMax = sets.fold(0.0) { acc, s -> maxOf(acc, OneRepMax.estimate(s.weight, s.reps)) },
        )
    }

    /** Volume per muscle group of the sessions since [since], largest first (`getVolumeByMuscle`). */
    fun volumeByMuscle(rows: StatRows, exercises: Map<String, ExerciseInfo>, since: Long, gym: GymFilter): List<MuscleVolume> {
        val byGroup = LinkedHashMap<MuscleGroup, Double>()
        volumes(rows.exercises.filter { it.startedAt >= since && it.inGym(gym) }, rows).forEach { (le, volume) ->
            val group = exercises[le.exerciseId]?.muscleGroup ?: return@forEach
            byGroup[group] = (byGroup[group] ?: 0.0) + volume
        }
        return byGroup.map { (group, volume) -> MuscleVolume(group, volume) }.sortedByDescending { it.volume }
    }

    /** This week's volume for every group, 0 when none (`getVolumenSemanaByMuscle`). */
    fun weekVolumeByMuscle(rows: StatRows, exercises: Map<String, ExerciseInfo>, gym: GymFilter, now: Instant, zone: ZoneId): Map<MuscleGroup, Double> {
        val volumes = volumeByMuscle(rows, exercises, Periods.weekStart(now, zone).toEpochMilli(), gym).associate { it.group to it.volume }
        return MuscleGroup.entries.associateWith { volumes[it] ?: 0.0 }
    }

    /** Sessions and volume since [since] (`getPeriodSummary`). */
    fun periodSummary(rows: StatRows, since: Long, gym: GymFilter): PeriodSummary {
        val sessions = rows.sessions.count { it.startedAt >= since && it.inGym(gym) }
        if (sessions == 0) return PeriodSummary(0, 0.0)
        val volume = volumes(rows.exercises.filter { it.startedAt >= since && it.inGym(gym) }, rows)
            .fold(0.0) { acc, (_, v) -> acc + v }
        return PeriodSummary(sessions, volume)
    }

    /** Session id -> volume (accumulated per logged exercise, in logged-exercise id order). */
    private fun volumeBySession(sessionIds: Set<String>, rows: StatRows): Map<String, Double> {
        val bySession = HashMap<String, Double>()
        volumes(rows.exercises.filter { it.sessionId in sessionIds }, rows).forEach { (le, volume) ->
            bySession[le.sessionId] = (bySession[le.sessionId] ?: 0.0) + volume
        }
        return bySession
    }

    /** Volume per week (Monday 00:00 in [zone]) since [since], oldest first (`getWeeklyVolume`). */
    fun weeklyVolume(rows: StatRows, since: Long, gym: GymFilter, zone: ZoneId): List<WeeklyVolume> {
        val sessions = rows.sessions.filter { it.startedAt >= since && it.inGym(gym) }.sortedBy { it.id }
        if (sessions.isEmpty()) return emptyList()
        val bySession = volumeBySession(sessions.mapTo(HashSet()) { it.id }, rows)
        val byWeek = LinkedHashMap<Long, Double>()
        sessions.forEach { s ->
            val week = Periods.weekStart(Instant.ofEpochMilli(s.startedAt), zone).toEpochMilli()
            byWeek[week] = (byWeek[week] ?: 0.0) + (bySession[s.id] ?: 0.0)
        }
        return byWeek.map { (week, volume) -> WeeklyVolume(week, volume) }.sortedBy { it.weekStart }
    }

    /** Each week's change against the previous point, the first one null (`weeklyVolumeDeltas`). */
    fun weeklyVolumeDeltas(points: List<WeeklyVolume>): List<WeeklyVolumeDelta> = points.mapIndexed { i, p ->
        val previous = if (i == 0) null else points[i - 1].volume
        WeeklyVolumeDelta(p.weekStart, p.volume, if (previous != null && previous > 0) percentChange(p.volume, previous) else null)
    }

    private fun percentChange(value: Double, previous: Double): Int = Numbers.jsRound(((value - previous) / previous) * 100).toInt()

    /** This ISO week (in [zone]) against the previous one (`getWeeklySummary`); sessions after now count as this week. */
    fun weeklySummary(rows: StatRows, gym: GymFilter, now: Instant, zone: ZoneId): WeeklySummary {
        val thisWeek = Periods.weekStart(now, zone).toEpochMilli()
        val previousWeek = Periods.weekStart(Instant.ofEpochMilli(thisWeek - 1), zone).toEpochMilli()
        val sessions = rows.sessions.filter { it.inGym(gym) && it.startedAt >= previousWeek }.sortedBy { it.id }
        val bySession = volumeBySession(sessions.mapTo(HashSet()) { it.id }, rows)
        var count = 0
        var volume = 0.0
        var previous = 0.0
        sessions.forEach { s ->
            val v = bySession[s.id] ?: 0.0
            if (s.startedAt >= thisWeek) {
                count++
                volume += v
            } else {
                previous += v
            }
        }
        return WeeklySummary(count, volume, previous, if (previous > 0) percentChange(volume, previous) else null)
    }

    /**
     * Exercises trained this week that beat their best from before this week (`getPRsThisWeek`):
     * by weight first, else by estimated 1RM. Without earlier history it is not a record.
     */
    fun weekRecords(rows: StatRows, exercises: Map<String, ExerciseInfo>, gym: GymFilter, now: Instant, zone: ZoneId): List<WeekRecord> {
        val start = Periods.weekStart(now, zone).toEpochMilli()
        val gymSets = rows.sets.filter { it.inGym(gym) }
        val weekExerciseIds = rows.exercises.filter { it.inGym(gym) && it.startedAt >= start }.sortedBy { it.id }.map { it.exerciseId }.distinct()
        return weekExerciseIds.mapNotNull { exerciseId ->
            val sets = gymSets.filter { it.exerciseId == exerciseId }
            val week = sets.filter { it.startedAt >= start }
            val before = sets.filter { it.startedAt < start }
            if (week.isEmpty() || before.isEmpty()) return@mapNotNull null
            val name = exercises[exerciseId]?.name ?: UNKNOWN_NAME
            when {
                week.maxOf { it.weight } > before.maxOf { it.weight } -> WeekRecord(exerciseId, name, RecordKind.WEIGHT)
                week.maxOf { OneRepMax.estimate(it.weight, it.reps) } > before.maxOf { OneRepMax.estimate(it.weight, it.reps) } ->
                    WeekRecord(exerciseId, name, RecordKind.ONE_REP_MAX)
                else -> null
            }
        }
    }

    /** Last session date per muscle group, null if never trained (in that gym) (`getLastTrainedByMuscle`). */
    fun lastTrainedByMuscle(rows: StatRows, exercises: Map<String, ExerciseInfo>, gym: GymFilter): Map<MuscleGroup, Long?> {
        val last = HashMap<MuscleGroup, Long>()
        rows.exercises.filter { it.inGym(gym) }.forEach { le ->
            val group = exercises[le.exerciseId]?.muscleGroup ?: return@forEach
            if ((last[group] ?: Long.MIN_VALUE) < le.startedAt) last[group] = le.startedAt
        }
        return MuscleGroup.entries.associateWith { last[it] }
    }

    /** Exercises (in that gym) whose best estimated 1RM stalled, most sessions without improvement first (`listEstancados`). */
    fun stalls(rows: StatRows, exercises: Map<String, ExerciseInfo>, gym: GymFilter): List<Stall> {
        val exerciseIds = rows.exercises.filter { it.inGym(gym) }.sortedBy { it.id }.map { it.exerciseId }.distinct()
        return exerciseIds.mapNotNull { exerciseId ->
            val detection = Insights.detectStall(exerciseProgress(rows, exerciseId, gym, since = 0))
            if (!detection.stalled) return@mapNotNull null
            Stall(exerciseId, exercises[exerciseId]?.name ?: UNKNOWN_NAME, detection.sessionsWithoutImprovement, detection.lastImprovementAt)
        }.sortedByDescending { it.sessionsWithoutImprovement }
    }

    /** Consecutive local days (in [zone]) with a session, counting back from today; 0 if not today (`getCurrentStreakDays`). Every gym. */
    fun dailyStreak(sessionStarts: Collection<Long>, now: Instant, zone: ZoneId): Int {
        val days = sessionStarts.mapTo(HashSet()) { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        var day: LocalDate = now.atZone(zone).toLocalDate()
        var streak = 0
        while (day in days) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    /**
     * Consecutive weeks with at least [target] sessions (`getRachaSemanal` + `calcularRacha`), every gym:
     * best run ever, and the current run back from this week (or from last week while this one is not met yet).
     */
    fun weeklyStreak(sessionStarts: Collection<Long>, target: Int, now: Instant, zone: ZoneId): WeeklyStreak {
        val perWeek = HashMap<Long, Int>()
        sessionStarts.forEach { at ->
            val week = Periods.weekStart(Instant.ofEpochMilli(at), zone).toEpochMilli()
            perWeek[week] = (perWeek[week] ?: 0) + 1
        }
        // Week index = Math.round(weekStartMs / week) like the PWA; a DST hour never moves it.
        fun index(weekStart: Long) = Numbers.jsRound(weekStart.toDouble() / WEEK_MILLIS).toLong()
        val met = perWeek.filterValues { it >= target }.keys.mapTo(HashSet()) { index(it) }
        var best = 0
        var run = 0
        var previous: Long? = null
        met.sorted().forEach { i ->
            run = if (previous == i - 1) run + 1 else 1
            if (run > best) best = run
            previous = i
        }
        val thisWeek = index(Periods.weekStart(now, zone).toEpochMilli())
        var cursor = if (thisWeek in met) thisWeek else thisWeek - 1
        var current = 0
        while (cursor in met) {
            current++
            cursor--
        }
        return WeeklyStreak(current, best)
    }
}
