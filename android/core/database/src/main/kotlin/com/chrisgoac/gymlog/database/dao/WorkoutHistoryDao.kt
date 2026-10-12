package com.chrisgoac.gymlog.database.dao

import androidx.room.Dao
import androidx.room.Query

/** The logged exercise chosen as "last time" for one exercise, and its session date. */
data class HistoryCandidate(val loggedExerciseId: String, val fecha: Long)

/**
 * For ONE exercise: its logged exercise in the most recent live session other than
 * `:excludeSessionId` (restricted to `:gymId` when not null, PWA `findLastLoggedExercise`) that
 * still has at least one live set.
 *
 * Plain joins only (no window functions, no subqueries), so it runs on SQLite 3.18 (API 26). The
 * CROSS JOIN fixes the loop order: sessions are walked newest-first through
 * `index_workout_sessions_fecha` (which also satisfies the ORDER BY, so there is no sort), each
 * probing (forced with INDEXED BY) `index_logged_exercises_sessionId` (a session holds a handful of
 * exercises; the exerciseId index would re-read the whole history per session) and
 * `index_logged_sets_loggedExerciseId`; LIMIT 1
 * stops at the first hit. The cost depends on how many recent sessions lack the exercise, not on
 * the size of the history. Equal `fecha` values resolve in index (rowid) order.
 */
const val LATEST_HISTORY_CANDIDATE_SQL = """
SELECT le.id AS loggedExerciseId, s.fecha AS fecha
FROM workout_sessions AS s INDEXED BY index_workout_sessions_fecha
CROSS JOIN logged_exercises AS le INDEXED BY index_logged_exercises_sessionId
CROSS JOIN logged_sets AS ls INDEXED BY index_logged_sets_loggedExerciseId
WHERE le.sessionId = s.id
  AND le.exerciseId = :exerciseId
  AND le.deletedAt IS NULL
  AND s.deletedAt IS NULL
  AND s.id != :excludeSessionId
  AND (:gymId IS NULL OR s.gymId = :gymId)
  AND ls.loggedExerciseId = le.id
  AND ls.deletedAt IS NULL
ORDER BY s.fecha DESC
LIMIT 1
"""

/** A history session as the list needs it (no child rows). [duracionSegundos] null = unfinished ("Sin terminar"). */
data class HistorySessionRow(
    val id: String,
    val fecha: Long,
    val duracionSegundos: Int?,
    val gymId: String?,
    val routineId: String?,
    val notas: String?,
)

/** Live sets of every live exercise of one session in display order: `index_logged_exercises_sessionId`, then one `index_logged_sets_loggedExerciseId` probe per exercise. */
const val LIVE_SETS_FOR_SESSION_SQL =
    "SELECT ls.* FROM logged_sets AS ls JOIN logged_exercises AS le ON le.id = ls.loggedExerciseId " +
        "WHERE le.sessionId = :sessionId AND le.deletedAt IS NULL AND ls.deletedAt IS NULL ORDER BY ls.orden, ls.id"

/** Per-session totals over live exercises and live sets. */
data class SessionTotalsRow(val sessionId: String, val exercises: Int, val sets: Int, val volume: Double)

private const val HISTORY_COLUMNS = "s.id AS id, s.fecha AS fecha, s.duracionSegundos AS duracionSegundos, s.gymId AS gymId, s.routineId AS routineId, s.notas AS notas"

// History = every live session except the active workout (`:activeId`, null = none): finished ones and abandoned
// unfinished ones. Ordered through index_workout_sessions_fecha; `:gymId IS NULL` = every gym.
private const val HISTORY_WHERE = "s.deletedAt IS NULL AND (s.duracionSegundos IS NOT NULL OR :activeId IS NULL OR s.id != :activeId) " +
    "AND s.fecha >= :since AND (:gymId IS NULL OR s.gymId = :gymId)"

@Dao
interface WorkoutHistoryDao {
    @Query(LATEST_HISTORY_CANDIDATE_SQL)
    suspend fun latestCandidate(exerciseId: String, excludeSessionId: String, gymId: String?): HistoryCandidate?

    /** Newest-first page of history sessions: the LIMIT stops the walk of the fecha index. */
    @Query("SELECT $HISTORY_COLUMNS FROM workout_sessions AS s WHERE $HISTORY_WHERE ORDER BY s.fecha DESC, s.id LIMIT :limit")
    suspend fun historyPage(since: Long, gymId: String?, activeId: String?, limit: Int): List<HistorySessionRow>

    /** History sessions that have notes (the text is matched accent-insensitively by the caller). */
    @Query("SELECT $HISTORY_COLUMNS FROM workout_sessions AS s WHERE $HISTORY_WHERE AND s.notas IS NOT NULL ORDER BY s.fecha DESC, s.id")
    suspend fun historyWithNotes(since: Long, gymId: String?, activeId: String?): List<HistorySessionRow>

    @Query("SELECT $HISTORY_COLUMNS FROM workout_sessions AS s WHERE $HISTORY_WHERE AND s.routineId IN (:routineIds) ORDER BY s.fecha DESC, s.id")
    suspend fun historyByRoutines(since: Long, gymId: String?, activeId: String?, routineIds: List<String>): List<HistorySessionRow>

    @Query(
        "SELECT DISTINCT $HISTORY_COLUMNS FROM logged_exercises AS le " +
            "JOIN workout_sessions AS s ON s.id = le.sessionId " +
            "WHERE le.exerciseId IN (:exerciseIds) AND le.deletedAt IS NULL AND $HISTORY_WHERE ORDER BY s.fecha DESC, s.id",
    )
    suspend fun historyByExercises(since: Long, gymId: String?, activeId: String?, exerciseIds: List<String>): List<HistorySessionRow>

    @Query(
        "SELECT le.sessionId AS sessionId, COUNT(DISTINCT le.id) AS exercises, COUNT(ls.id) AS sets, COALESCE(SUM(ls.peso * ls.reps), 0.0) AS volume " +
            "FROM logged_exercises AS le LEFT JOIN logged_sets AS ls ON ls.loggedExerciseId = le.id AND ls.deletedAt IS NULL " +
            "WHERE le.sessionId IN (:sessionIds) AND le.deletedAt IS NULL GROUP BY le.sessionId",
    )
    suspend fun totals(sessionIds: List<String>): List<SessionTotalsRow>

    /** Live sets of every live exercise of one session, in display order. */
    @Query(LIVE_SETS_FOR_SESSION_SQL)
    suspend fun liveSetsForSession(sessionId: String): List<com.chrisgoac.gymlog.database.entity.LoggedSetEntity>
}
