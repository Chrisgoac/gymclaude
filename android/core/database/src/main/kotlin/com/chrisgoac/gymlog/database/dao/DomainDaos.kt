package com.chrisgoac.gymlog.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import com.chrisgoac.gymlog.database.entity.RoutineEntity
import com.chrisgoac.gymlog.database.entity.RoutineExerciseEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.GymEntity
import com.chrisgoac.gymlog.database.entity.ExercisePhotoEntity
import com.chrisgoac.gymlog.database.entity.UserSettingEntity
import com.chrisgoac.gymlog.database.entity.CoachMessageEntity
import com.chrisgoac.gymlog.database.entity.BodyMetricEntity
import com.chrisgoac.gymlog.database.entity.ProgressPhotoEntity
import com.chrisgoac.gymlog.database.entity.MesocycleEntity
import com.chrisgoac.gymlog.database.entity.AchievementEntity
import kotlinx.coroutines.flow.Flow

// One DAO per synchronized table, all with the same shape:
// - upsert: full-record replace (local edits and pulled changes alike);
// - findById: includes tombstones, so callers can tell "deleted" from "never seen";
// - observeLive: what the UI reads (tombstones excluded);
// - getAll: every row including tombstones (sync, diagnostics, tests).
// There is no physical delete: removing a synced row means upserting a tombstone.

@Dao
interface ExerciseDao {
    @Upsert suspend fun upsert(entity: ExerciseEntity)

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun findById(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE deletedAt IS NULL ORDER BY nombre, id")
    fun observeLive(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises ORDER BY id")
    suspend fun getAll(): List<ExerciseEntity>
}

@Dao
interface RoutineDao {
    @Upsert suspend fun upsert(entity: RoutineEntity)

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun findById(id: String): RoutineEntity?

    @Query("SELECT * FROM routines WHERE deletedAt IS NULL ORDER BY orden, nombre, id")
    fun observeLive(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY id")
    suspend fun getAll(): List<RoutineEntity>
}

@Dao
interface RoutineExerciseDao {
    @Upsert suspend fun upsert(entity: RoutineExerciseEntity)

    @Query("SELECT * FROM routine_exercises WHERE id = :id")
    suspend fun findById(id: String): RoutineExerciseEntity?

    @Query("SELECT * FROM routine_exercises WHERE deletedAt IS NULL ORDER BY orden, id")
    fun observeLive(): Flow<List<RoutineExerciseEntity>>

    /** Live exercises of one routine in order; usable inside a mutation transaction. */
    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId AND deletedAt IS NULL ORDER BY orden, id")
    suspend fun liveForRoutine(routineId: String): List<RoutineExerciseEntity>

    @Query("SELECT * FROM routine_exercises ORDER BY id")
    suspend fun getAll(): List<RoutineExerciseEntity>
}

@Dao
interface WorkoutSessionDao {
    @Upsert suspend fun upsert(entity: WorkoutSessionEntity)

    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun findById(id: String): WorkoutSessionEntity?

    @Query("SELECT * FROM workout_sessions WHERE deletedAt IS NULL ORDER BY fecha DESC, id")
    fun observeLive(): Flow<List<WorkoutSessionEntity>>

    /** Active = not finished (`duracionSegundos` null) and not deleted; newest first. */
    @Query("SELECT * FROM workout_sessions WHERE deletedAt IS NULL AND duracionSegundos IS NULL ORDER BY fecha DESC, id")
    fun observeActive(): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_sessions WHERE deletedAt IS NULL AND duracionSegundos IS NULL ORDER BY fecha DESC, id")
    suspend fun findActive(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_sessions ORDER BY id")
    suspend fun getAll(): List<WorkoutSessionEntity>
}

@Dao
interface LoggedExerciseDao {
    @Upsert suspend fun upsert(entity: LoggedExerciseEntity)

    @Query("SELECT * FROM logged_exercises WHERE id = :id")
    suspend fun findById(id: String): LoggedExerciseEntity?

    @Query("SELECT * FROM logged_exercises WHERE deletedAt IS NULL ORDER BY orden, id")
    fun observeLive(): Flow<List<LoggedExerciseEntity>>

    /** Live exercises of one session in order; usable inside a mutation transaction. */
    @Query("SELECT * FROM logged_exercises WHERE sessionId = :sessionId AND deletedAt IS NULL ORDER BY orden, id")
    suspend fun liveForSession(sessionId: String): List<LoggedExerciseEntity>

    @Query("SELECT * FROM logged_exercises ORDER BY id")
    suspend fun getAll(): List<LoggedExerciseEntity>
}

@Dao
interface LoggedSetDao {
    @Upsert suspend fun upsert(entity: LoggedSetEntity)

    @Query("SELECT * FROM logged_sets WHERE id = :id")
    suspend fun findById(id: String): LoggedSetEntity?

    @Query("SELECT * FROM logged_sets WHERE deletedAt IS NULL ORDER BY orden, id")
    fun observeLive(): Flow<List<LoggedSetEntity>>

    /** Live sets of one logged exercise in order; usable inside a mutation transaction. */
    @Query("SELECT * FROM logged_sets WHERE loggedExerciseId = :loggedExerciseId AND deletedAt IS NULL ORDER BY orden, id")
    suspend fun liveForExercise(loggedExerciseId: String): List<LoggedSetEntity>

    @Query("SELECT * FROM logged_sets ORDER BY id")
    suspend fun getAll(): List<LoggedSetEntity>
}

@Dao
interface GymDao {
    @Upsert suspend fun upsert(entity: GymEntity)

    @Query("SELECT * FROM gyms WHERE id = :id")
    suspend fun findById(id: String): GymEntity?

    @Query("SELECT * FROM gyms WHERE deletedAt IS NULL ORDER BY orden, id")
    fun observeLive(): Flow<List<GymEntity>>

    @Query("SELECT * FROM gyms ORDER BY id")
    suspend fun getAll(): List<GymEntity>
}

@Dao
interface ExercisePhotoDao {
    @Upsert suspend fun upsert(entity: ExercisePhotoEntity)

    @Query("SELECT * FROM exercise_photos WHERE id = :id")
    suspend fun findById(id: String): ExercisePhotoEntity?

    @Query("SELECT * FROM exercise_photos WHERE deletedAt IS NULL ORDER BY updatedAt, id")
    fun observeLive(): Flow<List<ExercisePhotoEntity>>

    @Query("SELECT * FROM exercise_photos ORDER BY id")
    suspend fun getAll(): List<ExercisePhotoEntity>
}

@Dao
interface UserSettingDao {
    @Upsert suspend fun upsert(entity: UserSettingEntity)

    @Query("SELECT * FROM user_settings WHERE id = :id")
    suspend fun findById(id: String): UserSettingEntity?

    @Query("SELECT * FROM user_settings WHERE deletedAt IS NULL ORDER BY id, id")
    fun observeLive(): Flow<List<UserSettingEntity>>

    @Query("SELECT * FROM user_settings ORDER BY id")
    suspend fun getAll(): List<UserSettingEntity>
}

@Dao
interface CoachMessageDao {
    @Upsert suspend fun upsert(entity: CoachMessageEntity)

    @Query("SELECT * FROM coach_messages WHERE id = :id")
    suspend fun findById(id: String): CoachMessageEntity?

    @Query("SELECT * FROM coach_messages WHERE deletedAt IS NULL ORDER BY createdAt, id")
    fun observeLive(): Flow<List<CoachMessageEntity>>

    @Query("SELECT * FROM coach_messages ORDER BY id")
    suspend fun getAll(): List<CoachMessageEntity>
}

@Dao
interface BodyMetricDao {
    @Upsert suspend fun upsert(entity: BodyMetricEntity)

    @Query("SELECT * FROM body_metrics WHERE id = :id")
    suspend fun findById(id: String): BodyMetricEntity?

    @Query("SELECT * FROM body_metrics WHERE deletedAt IS NULL ORDER BY fecha DESC, id")
    fun observeLive(): Flow<List<BodyMetricEntity>>

    @Query("SELECT * FROM body_metrics ORDER BY id")
    suspend fun getAll(): List<BodyMetricEntity>
}

@Dao
interface ProgressPhotoDao {
    @Upsert suspend fun upsert(entity: ProgressPhotoEntity)

    @Query("SELECT * FROM progress_photos WHERE id = :id")
    suspend fun findById(id: String): ProgressPhotoEntity?

    @Query("SELECT * FROM progress_photos WHERE deletedAt IS NULL ORDER BY fecha DESC, id")
    fun observeLive(): Flow<List<ProgressPhotoEntity>>

    @Query("SELECT * FROM progress_photos ORDER BY id")
    suspend fun getAll(): List<ProgressPhotoEntity>
}

@Dao
interface MesocycleDao {
    @Upsert suspend fun upsert(entity: MesocycleEntity)

    @Query("SELECT * FROM mesocycles WHERE id = :id")
    suspend fun findById(id: String): MesocycleEntity?

    @Query("SELECT * FROM mesocycles WHERE deletedAt IS NULL ORDER BY fechaInicio DESC, id")
    fun observeLive(): Flow<List<MesocycleEntity>>

    @Query("SELECT * FROM mesocycles ORDER BY id")
    suspend fun getAll(): List<MesocycleEntity>
}

@Dao
interface AchievementDao {
    @Upsert suspend fun upsert(entity: AchievementEntity)

    @Query("SELECT * FROM achievements WHERE id = :id")
    suspend fun findById(id: String): AchievementEntity?

    @Query("SELECT * FROM achievements WHERE deletedAt IS NULL ORDER BY fechaDesbloqueo, id")
    fun observeLive(): Flow<List<AchievementEntity>>

    @Query("SELECT * FROM achievements ORDER BY id")
    suspend fun getAll(): List<AchievementEntity>
}
