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

@Dao
interface WorkoutHistoryDao {
    @Query(LATEST_HISTORY_CANDIDATE_SQL)
    suspend fun latestCandidate(exerciseId: String, excludeSessionId: String, gymId: String?): HistoryCandidate?
}
