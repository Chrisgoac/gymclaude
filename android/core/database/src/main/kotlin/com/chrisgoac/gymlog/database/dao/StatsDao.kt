package com.chrisgoac.gymlog.database.dao

import androidx.room.Dao
import androidx.room.Query

/** A live session (stats). */
data class StatSessionRow(val id: String, val startedAt: Long, val gymId: String?)

/** A live logged exercise of a live session with its session's date and gym. */
data class StatExerciseRow(val id: String, val sessionId: String, val exerciseId: String, val startedAt: Long, val gymId: String?)

/** A live set of a live logged exercise of a live session, denormalized. */
data class StatSetRow(
    val id: String,
    val loggedExerciseId: String,
    val sessionId: String,
    val exerciseId: String,
    val startedAt: Long,
    val gymId: String?,
    val peso: Double,
    val reps: Int,
    val esCalentamiento: Boolean?,
)

/** Total volume (sum of weight × reps over its live sets, 0 without sets) of one live logged exercise. */
data class StatExerciseVolumeRow(val id: String, val sessionId: String, val exerciseId: String, val startedAt: Long, val gymId: String?, val volume: Double)

/** Heaviest live set per (exercise, date or week side, reps): enough to rebuild max weight and best Epley 1RM exactly. */
data class StatBestSetRow(val exerciseId: String, val startedAt: Long, val reps: Int, val peso: Double)

/** First logged-exercise id (PWA iteration order) and last date of one exercise. */
data class StatExerciseSpanRow(val exerciseId: String, val firstLoggedExerciseId: String, val lastStartedAt: Long)

/** Name and group of an exercise (catalog or synced row, deleted ones included like the PWA's `bulkGet`). */
data class StatExerciseInfoRow(val id: String, val nombre: String, val grupoMuscular: String)

// Every query keeps only live rows whose parents are live; `:gymId IS NULL` = every gym. Session ranges walk
// index_workout_sessions_fecha; one exercise's rows come through index_logged_exercises_exerciseId.
private const val LIVE = "s.deletedAt IS NULL AND le.deletedAt IS NULL AND (:gymId IS NULL OR s.gymId = :gymId)"
private const val LIVE_SET = "$LIVE AND ls.deletedAt IS NULL"
private const val FROM_EXERCISES = "FROM workout_sessions AS s JOIN logged_exercises AS le ON le.sessionId = s.id"
private const val FROM_SETS = "$FROM_EXERCISES JOIN logged_sets AS ls ON ls.loggedExerciseId = le.id"
private const val SET_COLUMNS = "ls.id AS id, ls.loggedExerciseId AS loggedExerciseId, le.sessionId AS sessionId, le.exerciseId AS exerciseId, " +
    "s.fecha AS startedAt, s.gymId AS gymId, ls.peso AS peso, ls.reps AS reps, ls.esCalentamiento AS esCalentamiento"

/**
 * Read-only queries behind the progress statistics. Range queries are bounded by the session date
 * index; the "ever" ones (records, stalls, last trained) aggregate in SQL so only a few rows per
 * exercise reach Kotlin.
 */
@Dao
interface StatsDao {
    @Query("SELECT id, fecha AS startedAt, gymId FROM workout_sessions AS s WHERE s.deletedAt IS NULL AND s.fecha >= :since AND (:gymId IS NULL OR s.gymId = :gymId)")
    suspend fun sessionsSince(since: Long, gymId: String?): List<StatSessionRow>

    /** Session dates of every live session (streaks need the whole history; one column). */
    @Query("SELECT fecha FROM workout_sessions WHERE deletedAt IS NULL")
    suspend fun allSessionStarts(): List<Long>

    @Query(
        "SELECT le.id AS id, le.sessionId AS sessionId, le.exerciseId AS exerciseId, s.fecha AS startedAt, s.gymId AS gymId, " +
            "COALESCE((SELECT SUM(ls.peso * ls.reps) FROM logged_sets AS ls WHERE ls.loggedExerciseId = le.id AND ls.deletedAt IS NULL), 0.0) AS volume " +
            "$FROM_EXERCISES WHERE $LIVE AND s.fecha >= :since",
    )
    suspend fun exerciseVolumesSince(since: Long, gymId: String?): List<StatExerciseVolumeRow>

    @Query("SELECT $SET_COLUMNS $FROM_SETS WHERE $LIVE_SET AND le.exerciseId = :exerciseId AND s.fecha >= :since")
    suspend fun setsOfExercise(exerciseId: String, since: Long, gymId: String?): List<StatSetRow>

    @Query("SELECT le.exerciseId AS exerciseId, MAX(s.fecha) AS startedAt, ls.reps AS reps, MAX(ls.peso) AS peso $FROM_SETS WHERE $LIVE_SET AND le.exerciseId = :exerciseId GROUP BY ls.reps")
    suspend fun bestSetsOfExercise(exerciseId: String, gymId: String?): List<StatBestSetRow>

    /** Per exercise in [exerciseIds]: best set per reps before and since [weekStart] (startedAt falls on the right side). */
    @Query(
        "SELECT le.exerciseId AS exerciseId, MAX(s.fecha) AS startedAt, ls.reps AS reps, MAX(ls.peso) AS peso $FROM_SETS " +
            "WHERE $LIVE_SET AND le.exerciseId IN (:exerciseIds) GROUP BY le.exerciseId, s.fecha >= :weekStart, ls.reps",
    )
    suspend fun bestSetsAroundWeek(exerciseIds: List<String>, weekStart: Long, gymId: String?): List<StatBestSetRow>

    /** Best set per (exercise, date, reps) over the whole history (stall detection only needs the best 1RM per date). */
    @Query("SELECT le.exerciseId AS exerciseId, s.fecha AS startedAt, ls.reps AS reps, MAX(ls.peso) AS peso $FROM_SETS WHERE $LIVE_SET GROUP BY le.exerciseId, s.fecha, ls.reps")
    suspend fun bestSetsPerDate(gymId: String?): List<StatBestSetRow>

    /** Every exercise ever logged (in that gym), sets or not. */
    @Query("SELECT le.exerciseId AS exerciseId, MIN(le.id) AS firstLoggedExerciseId, MAX(s.fecha) AS lastStartedAt $FROM_EXERCISES WHERE $LIVE GROUP BY le.exerciseId")
    suspend fun exerciseSpans(gymId: String?): List<StatExerciseSpanRow>

    @Query("SELECT id, nombre, grupoMuscular FROM catalog_exercises WHERE id IN (:ids)")
    suspend fun catalogInfo(ids: List<String>): List<StatExerciseInfoRow>

    @Query("SELECT id, nombre, grupoMuscular FROM exercises WHERE id IN (:ids)")
    suspend fun syncedInfo(ids: List<String>): List<StatExerciseInfoRow>

    /** JSON value of a live user setting. */
    @Query("SELECT valor FROM user_settings WHERE id = :key AND deletedAt IS NULL")
    suspend fun setting(key: String): String?
}
