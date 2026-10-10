package com.chrisgoac.gymlog.database.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One live set of the latest earlier session of an exercise (see [WorkoutHistoryDao.observeLatestHistory]). */
data class ExerciseHistoryRow(
    val exerciseId: String,
    val fecha: Long,
    val setId: String,
    val orden: Int,
    val peso: Double,
    val reps: Int,
    val esCalentamiento: Boolean?,
)

/**
 * For each exercise in `:exerciseIds`, the logged exercise of its most recent live session (by
 * `fecha` desc, then id) other than `:excludeSessionId` that still has live sets, restricted to
 * `:gymId` when it is not null (PWA `findLastLoggedExercise`). Returns only that logged
 * exercise's live sets, so the cost depends on the exercises asked for, not on the size of the
 * history: candidates come from `index_logged_exercises_exerciseId`, sessions by primary key and
 * sets from `index_logged_sets_loggedExerciseId`.
 */
const val LATEST_HISTORY_SQL = """
SELECT le.exerciseId AS exerciseId, s.fecha AS fecha, ls.id AS setId, ls.orden AS orden,
       ls.peso AS peso, ls.reps AS reps, ls.esCalentamiento AS esCalentamiento
FROM logged_exercises le
JOIN workout_sessions s ON s.id = le.sessionId
JOIN logged_sets ls ON ls.loggedExerciseId = le.id AND ls.deletedAt IS NULL
WHERE le.exerciseId IN (:exerciseIds)
  AND le.id = (
    SELECT le2.id FROM logged_exercises le2
    JOIN workout_sessions s2 ON s2.id = le2.sessionId
    WHERE le2.exerciseId = le.exerciseId
      AND le2.deletedAt IS NULL
      AND le2.sessionId != :excludeSessionId
      AND s2.deletedAt IS NULL
      AND (:gymId IS NULL OR s2.gymId = :gymId)
      AND EXISTS (SELECT 1 FROM logged_sets ls2 WHERE ls2.loggedExerciseId = le2.id AND ls2.deletedAt IS NULL)
    ORDER BY s2.fecha DESC, le2.id ASC
    LIMIT 1
  )
ORDER BY le.exerciseId, ls.orden, ls.id
"""

@Dao
interface WorkoutHistoryDao {
    @Query(LATEST_HISTORY_SQL)
    fun observeLatestHistory(exerciseIds: List<String>, excludeSessionId: String, gymId: String?): Flow<List<ExerciseHistoryRow>>

    @Query(LATEST_HISTORY_SQL)
    suspend fun latestHistory(exerciseIds: List<String>, excludeSessionId: String, gymId: String?): List<ExerciseHistoryRow>
}
