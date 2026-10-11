package com.chrisgoac.gymlog.data.stats

import com.chrisgoac.gymlog.domain.settings.SettingKeys
import com.chrisgoac.gymlog.domain.settings.SettingsCodec
import androidx.room.withTransaction
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.dao.StatBestSetRow
import com.chrisgoac.gymlog.database.dao.StatExerciseVolumeRow
import com.chrisgoac.gymlog.database.dao.StatsDao
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.ExerciseInfo
import com.chrisgoac.gymlog.domain.stats.ExerciseRecords
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.Insights
import com.chrisgoac.gymlog.domain.stats.PeriodSummary
import com.chrisgoac.gymlog.domain.stats.ProgressPoint
import com.chrisgoac.gymlog.domain.stats.ProgressionSuggestion
import com.chrisgoac.gymlog.domain.stats.Stall
import com.chrisgoac.gymlog.domain.stats.StatExercise
import com.chrisgoac.gymlog.domain.stats.StatRows
import com.chrisgoac.gymlog.domain.stats.StatSession
import com.chrisgoac.gymlog.domain.stats.StatSet
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.StatsRepository
import com.chrisgoac.gymlog.domain.stats.Streak
import com.chrisgoac.gymlog.domain.stats.VolumeOverview
import com.chrisgoac.gymlog.domain.stats.WeeklyInsight
import com.chrisgoac.gymlog.domain.stats.WeeklyStreak
import com.chrisgoac.gymlog.domain.stats.WeeklySummary
import com.chrisgoac.gymlog.domain.stats.WorkoutStats
import com.chrisgoac.gymlog.domain.time.Periods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.time.Instant
import java.time.ZoneId

/**
 * [StatsRepository] over the open account's Room database. Each flow re-reads on every commit to
 * the workout/exercise/settings tables (Room invalidation), then runs the shared pure reducers
 * ([WorkoutStats], [Insights]) inside one read transaction. Every statistic here is read from Room,
 * so a historical edit (one local mutation) refreshes each flow exactly once; the edit's
 * [com.chrisgoac.gymlog.domain.stats.StatsInvalidation] scopes are for consumers outside Room
 * (Coach snapshot, achievement reconciliation). Suggestions and the estimated 1RM are derived from
 * the stalls and records flows, never re-queried. Clock ([now], server-corrected) and [zone] are
 * sampled per emission.
 *
 * Cost per emission (n = live rows of the history):
 * - period flows (volume, balance) read the sessions/logged exercises since the period start
 *   through the date index, with set volumes summed in SQL per logged exercise; "Todo" is the whole
 *   history, one row per logged exercise.
 * - the weekly insight reads the last two weeks, plus per-reps best sets of this week's exercises.
 * - exercise series/records read one exercise through its index (records: one row per reps value).
 * - stalls and last-trained aggregate the whole history in SQL (one row per exercise × date × reps,
 *   and one per exercise); streaks read one column of every session. These are the unavoidable
 *   whole-history reads, done once per emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineStatsRepository(
    private val databases: UserDatabaseManager,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
) : StatsRepository {

    override fun observeWeeklyInsight(gym: GymFilter): Flow<WeeklyInsight> =
        observe(WeeklyInsight(WeeklySummary(0, 0.0, 0.0, null), emptyList())) { dao ->
            val at = Instant.ofEpochMilli(now())
            val z = zone()
            val thisWeek = Periods.weekStart(at, z).toEpochMilli()
            val previousWeek = Periods.weekStart(Instant.ofEpochMilli(thisWeek - 1), z).toEpochMilli()
            val volumes = volumeRows(dao, previousWeek, gym)
            val weekExerciseIds = volumes.exercises.filter { it.startedAt >= thisWeek }.map { it.exerciseId }.distinct()
            val best = weekExerciseIds.chunked(CHUNK).flatMap { dao.bestSetsAroundWeek(it, thisWeek, gym.gymId) }
            // Records compare best sets only: never mix in the synthetic per-exercise volume rows.
            val recordRows = StatRows(emptyList(), volumes.exercises, best.toSets(volumes.exercises, gym))
            WeeklyInsight(
                WorkoutStats.weeklySummary(volumes, gym, at, z),
                WorkoutStats.weekRecords(recordRows, infos(dao, weekExerciseIds), gym, at, z),
            )
        }

    override fun observeExerciseSeries(exerciseId: String, filter: StatsFilter): Flow<List<ProgressPoint>> =
        observe(emptyList()) { dao ->
            val since = Periods.since(filter.period, Instant.ofEpochMilli(now())).toEpochMilli()
            val sets = dao.setsOfExercise(exerciseId, since, filter.gym.gymId).map {
                StatSet(it.id, it.loggedExerciseId, it.sessionId, it.exerciseId, it.startedAt, it.gymId, it.peso, it.reps, it.esCalentamiento == true)
            }
            WorkoutStats.exerciseProgress(StatRows(emptyList(), emptyList(), sets), exerciseId, filter.gym, since)
        }

    override fun observeExerciseRecords(exerciseId: String, gym: GymFilter): Flow<ExerciseRecords?> =
        observe(null) { dao -> records(dao, exerciseId, gym) }

    override fun observeEstimatedOneRepMax(exerciseId: String, gym: GymFilter): Flow<Double?> =
        observeExerciseRecords(exerciseId, gym).map { it?.bestOneRepMax }

    override fun observeVolume(filter: StatsFilter): Flow<VolumeOverview> =
        observe(VolumeOverview(PeriodSummary(0, 0.0), emptyList())) { dao ->
            val since = Periods.since(filter.period, Instant.ofEpochMilli(now())).toEpochMilli()
            val rows = volumeRows(dao, since, filter.gym)
            VolumeOverview(
                WorkoutStats.periodSummary(rows, since, filter.gym),
                WorkoutStats.weeklyVolumeDeltas(WorkoutStats.weeklyVolume(rows, since, filter.gym, zone())),
            )
        }

    override fun observeMuscleBalance(filter: StatsFilter): Flow<List<BalanceRow>> =
        observe(emptyList()) { dao ->
            val at = Instant.ofEpochMilli(now())
            val z = zone()
            val since = Periods.since(filter.period, at).toEpochMilli()
            val weekStart = Periods.weekStart(at, z).toEpochMilli()
            val rows = volumeRows(dao, minOf(since, weekStart), filter.gym)
            val spans = dao.exerciseSpans(filter.gym.gymId)
            val infos = infos(dao, (rows.exercises.map { it.exerciseId } + spans.map { it.exerciseId }).distinct())
            val lastTrained = WorkoutStats.lastTrainedByMuscle(
                StatRows(emptyList(), spans.map { StatExercise(it.firstLoggedExerciseId, "", it.exerciseId, it.lastStartedAt, filter.gym.gymId) }, emptyList()),
                infos,
                filter.gym,
            )
            Insights.balance(
                WorkoutStats.volumeByMuscle(rows, infos, since, filter.gym),
                lastTrained,
                at.toEpochMilli(),
                volumeGoals(dao.setting(VOLUME_GOALS_KEY)),
                WorkoutStats.weekVolumeByMuscle(rows, infos, filter.gym, at, z),
            )
        }

    override fun observeStreak(): Flow<Streak> =
        observe(Streak(0, WeeklyStreak(0, 0), DEFAULT_WEEKLY_TARGET)) { dao ->
            val at = Instant.ofEpochMilli(now())
            val z = zone()
            val starts = dao.allSessionStarts()
            val target = weeklyTarget(dao.setting(WEEKLY_TARGET_KEY))
            Streak(WorkoutStats.dailyStreak(starts, at, z), WorkoutStats.weeklyStreak(starts, target, at, z), target)
        }

    override fun observeStalls(gym: GymFilter): Flow<List<Stall>> =
        observe(emptyList()) { dao -> stalls(dao, gym) }

    override fun observeProgressionSuggestions(gym: GymFilter): Flow<List<ProgressionSuggestion>> =
        observeStalls(gym).map(Insights::suggestions)

    /**
     * Re-runs [read] (in one transaction) on every commit to [STATS_TABLES]; [empty] while no account
     * database is open.
     */
    private fun <T> observe(empty: T, read: suspend (StatsDao) -> T): Flow<T> =
        databases.current.flatMapLatest { open ->
            if (open == null) {
                flowOf(empty)
            } else {
                val database: GymLogDatabase = open.database
                database.invalidationTracker.createFlow(*STATS_TABLES)
                    .mapLatest { database.withTransaction { read(database.statsDao()) } }
            }
        }

    private suspend fun records(dao: StatsDao, exerciseId: String, gym: GymFilter): ExerciseRecords? {
        val best = dao.bestSetsOfExercise(exerciseId, gym.gymId)
        return WorkoutStats.exerciseRecords(StatRows(emptyList(), emptyList(), best.toSets(emptyList(), gym)), exerciseId, gym)
    }

    /**
     * Stalls from the best set per (exercise, date, reps): the detector only reads each date's best
     * estimated 1RM, which the heaviest set per reps gives exactly. Exercise order is the PWA's (first
     * logged-exercise id), which only breaks ties of the final sort.
     */
    private suspend fun stalls(dao: StatsDao, gym: GymFilter): List<Stall> {
        val spans = dao.exerciseSpans(gym.gymId)
        val exercises = spans.map { StatExercise(it.firstLoggedExerciseId, "", it.exerciseId, it.lastStartedAt, gym.gymId) }
        val rows = StatRows(emptyList(), exercises, dao.bestSetsPerDate(gym.gymId).toSets(exercises, gym))
        return WorkoutStats.stalls(rows, infos(dao, spans.map { it.exerciseId }), gym)
    }

    /** Sessions plus one synthetic set per logged exercise carrying its SQL-summed volume (weight = volume, 1 rep). */
    private suspend fun volumeRows(dao: StatsDao, since: Long, gym: GymFilter): StatRows {
        val sessions = dao.sessionsSince(since, gym.gymId).map { StatSession(it.id, it.startedAt, it.gymId) }
        val volumes: List<StatExerciseVolumeRow> = dao.exerciseVolumesSince(since, gym.gymId)
        return StatRows(
            sessions,
            volumes.map { StatExercise(it.id, it.sessionId, it.exerciseId, it.startedAt, it.gymId) },
            volumes.map { StatSet(it.id, it.id, it.sessionId, it.exerciseId, it.startedAt, it.gymId, it.volume, 1, false) },
        )
    }

    /**
     * Best-set rows as synthetic sets in [gym], attached to the exercise's first logged exercise in
     * [exercises] (else a per-exercise key). Only exercise, date, weight and reps matter to the
     * reducers that read them (records, stalls), so no volume may be derived from these.
     */
    private fun List<StatBestSetRow>.toSets(exercises: List<StatExercise>, gym: GymFilter): List<StatSet> {
        val firstLogged = exercises.groupBy { it.exerciseId }.mapValues { (_, list) -> list.minOf { it.id } }
        return mapIndexed { i, row ->
            val logged = firstLogged[row.exerciseId] ?: "best:${row.exerciseId}"
            StatSet("best:$i", logged, "", row.exerciseId, row.startedAt, gym.gymId, row.peso, row.reps, false)
        }
    }

    private suspend fun infos(dao: StatsDao, exerciseIds: List<String>): Map<String, ExerciseInfo> {
        val result = HashMap<String, ExerciseInfo>()
        exerciseIds.distinct().chunked(CHUNK).forEach { ids ->
            (dao.catalogInfo(ids) + dao.syncedInfo(ids)).forEach { row ->
                result.putIfAbsent(row.id, ExerciseInfo(row.nombre, MuscleGroup.entries.firstOrNull { it.wire == row.grupoMuscular }))
            }
        }
        return result
    }

    companion object {
        const val WEEKLY_TARGET_KEY = SettingKeys.WEEKLY_TARGET
        const val VOLUME_GOALS_KEY = SettingKeys.VOLUME_GOALS
        const val DEFAULT_WEEKLY_TARGET = com.chrisgoac.gymlog.domain.settings.DEFAULT_WEEKLY_TARGET

        /** Tables whose commits can change a statistic. */
        val STATS_TABLES = arrayOf("workout_sessions", "logged_exercises", "logged_sets", "exercises", "catalog_exercises", "user_settings")


        /** Below SQLite's 999 bound-variable limit (API 26). */
        private const val CHUNK = 400

        /** `objetivoSemanal`, default 3 (decoded by the shared [SettingsCodec]). */
        internal fun weeklyTarget(raw: String?): Int = SettingsCodec.weeklyTarget(raw)

        /** `objetivosVolumen`, decoded by the shared [SettingsCodec]. */
        internal fun volumeGoals(raw: String?): Map<MuscleGroup, Double> = SettingsCodec.volumeGoals(raw)
    }
}
