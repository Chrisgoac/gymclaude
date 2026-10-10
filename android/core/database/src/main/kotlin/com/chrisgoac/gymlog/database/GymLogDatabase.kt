package com.chrisgoac.gymlog.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.chrisgoac.gymlog.database.dao.AchievementDao
import com.chrisgoac.gymlog.database.dao.BodyMetricDao
import com.chrisgoac.gymlog.database.dao.CoachMessageDao
import com.chrisgoac.gymlog.database.dao.ExerciseDao
import com.chrisgoac.gymlog.database.dao.ExercisePhotoDao
import com.chrisgoac.gymlog.database.dao.GymDao
import com.chrisgoac.gymlog.database.dao.LoggedExerciseDao
import com.chrisgoac.gymlog.database.dao.LoggedSetDao
import com.chrisgoac.gymlog.database.dao.MesocycleDao
import com.chrisgoac.gymlog.database.dao.ProgressPhotoDao
import com.chrisgoac.gymlog.database.dao.RoutineDao
import com.chrisgoac.gymlog.database.dao.RoutineExerciseDao
import com.chrisgoac.gymlog.database.dao.SyncDao
import com.chrisgoac.gymlog.database.dao.UserSettingDao
import com.chrisgoac.gymlog.database.dao.WorkoutSessionDao
import com.chrisgoac.gymlog.database.entity.AchievementEntity
import com.chrisgoac.gymlog.database.entity.BodyMetricEntity
import com.chrisgoac.gymlog.database.entity.BootstrapCheckpointEntity
import com.chrisgoac.gymlog.database.entity.CoachMessageEntity
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import com.chrisgoac.gymlog.database.entity.ExercisePhotoEntity
import com.chrisgoac.gymlog.database.entity.GymEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.MesocycleEntity
import com.chrisgoac.gymlog.database.entity.OutboxMutationEntity
import com.chrisgoac.gymlog.database.entity.ProgressPhotoEntity
import com.chrisgoac.gymlog.database.entity.RoutineEntity
import com.chrisgoac.gymlog.database.entity.RoutineExerciseEntity
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.database.entity.UserSettingEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity

/**
 * The local source of truth of ONE user (spec 8.4, 10.1). Every account gets its own physical
 * file, opened through [UserDatabaseManager]; there is no userId column anywhere.
 *
 * Holds the 14 synchronized tables, the durable outbox and the sync bookkeeping (pull cursor,
 * bootstrap checkpoint).
 *
 * **No SQL foreign keys between synced tables, on purpose.** The v1 contract
 * (contracts/v1/README.md, "References are not enforced" and "Pull semantics") requires clients to
 * accept dangling references: the server accepts ids it does not know yet, and pull has no
 * parent-before-child ordering within or across pages, so a `logged_sets` row may legitimately
 * arrive before its `logged_exercises` parent (or the parent may never arrive). Foreign-key
 * constraints would make applying such a page fail. Reference columns are indexed for joins
 * instead; referential checks belong in domain validation before a local write.
 *
 * Schema version 1 is the first published schema; exported schemas live in
 * `core/database/schemas/` and every future change needs a tested migration.
 */
@Database(
    entities = [
        ExerciseEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        WorkoutSessionEntity::class,
        LoggedExerciseEntity::class,
        LoggedSetEntity::class,
        GymEntity::class,
        ExercisePhotoEntity::class,
        UserSettingEntity::class,
        CoachMessageEntity::class,
        BodyMetricEntity::class,
        ProgressPhotoEntity::class,
        MesocycleEntity::class,
        AchievementEntity::class,
        OutboxMutationEntity::class,
        SyncCursorEntity::class,
        BootstrapCheckpointEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GymLogDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun routineDao(): RoutineDao
    abstract fun routineExerciseDao(): RoutineExerciseDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun loggedExerciseDao(): LoggedExerciseDao
    abstract fun loggedSetDao(): LoggedSetDao
    abstract fun gymDao(): GymDao
    abstract fun exercisePhotoDao(): ExercisePhotoDao
    abstract fun userSettingDao(): UserSettingDao
    abstract fun coachMessageDao(): CoachMessageDao
    abstract fun bodyMetricDao(): BodyMetricDao
    abstract fun progressPhotoDao(): ProgressPhotoDao
    abstract fun mesocycleDao(): MesocycleDao
    abstract fun achievementDao(): AchievementDao
    abstract fun syncDao(): SyncDao
}
