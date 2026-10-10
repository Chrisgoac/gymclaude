package com.chrisgoac.gymlog.database

import com.chrisgoac.gymlog.database.entity.toEntity
import com.chrisgoac.gymlog.database.entity.toRecord
import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.ExercisePhotoRecord
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.ProgressPhotoRecord
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord

/** A wire record as stored locally, with the last server version the client saw for it. */
data class StoredRecord(val record: SyncRecord, val serverVersion: Long?)

/** Table-dispatched access to the 14 synced tables in wire-record terms (explicit, no reflection). */
class RecordStore(private val db: GymLogDatabase) {

    suspend fun find(table: SyncTable, id: RecordId): StoredRecord? {
        val key = id.value
        return when (table) {
            SyncTable.EXERCISES -> db.exerciseDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.ROUTINES -> db.routineDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.ROUTINE_EXERCISES -> db.routineExerciseDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.WORKOUT_SESSIONS -> db.workoutSessionDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.LOGGED_EXERCISES -> db.loggedExerciseDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.LOGGED_SETS -> db.loggedSetDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.GYMS -> db.gymDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.EXERCISE_PHOTOS -> db.exercisePhotoDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.USER_SETTINGS -> db.userSettingDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.COACH_MESSAGES -> db.coachMessageDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.BODY_METRICS -> db.bodyMetricDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.PROGRESS_PHOTOS -> db.progressPhotoDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.MESOCYCLES -> db.mesocycleDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
            SyncTable.ACHIEVEMENTS -> db.achievementDao().findById(key)?.let { StoredRecord(it.toRecord(), it.serverVersion) }
        }
    }

    /** Full-record replace of [record] (a tombstone is just a record with deletedAt set). */
    suspend fun put(record: SyncRecord, serverVersion: Long?) {
        when (record) {
            is ExerciseRecord -> db.exerciseDao().upsert(record.toEntity(serverVersion))
            is RoutineRecord -> db.routineDao().upsert(record.toEntity(serverVersion))
            is RoutineExerciseRecord -> db.routineExerciseDao().upsert(record.toEntity(serverVersion))
            is WorkoutSessionRecord -> db.workoutSessionDao().upsert(record.toEntity(serverVersion))
            is LoggedExerciseRecord -> db.loggedExerciseDao().upsert(record.toEntity(serverVersion))
            is LoggedSetRecord -> db.loggedSetDao().upsert(record.toEntity(serverVersion))
            is GymRecord -> db.gymDao().upsert(record.toEntity(serverVersion))
            is ExercisePhotoRecord -> db.exercisePhotoDao().upsert(record.toEntity(serverVersion))
            is UserSettingRecord -> db.userSettingDao().upsert(record.toEntity(serverVersion))
            is CoachMessageRecord -> db.coachMessageDao().upsert(record.toEntity(serverVersion))
            is BodyMetricRecord -> db.bodyMetricDao().upsert(record.toEntity(serverVersion))
            is ProgressPhotoRecord -> db.progressPhotoDao().upsert(record.toEntity(serverVersion))
            is MesocycleRecord -> db.mesocycleDao().upsert(record.toEntity(serverVersion))
            is AchievementRecord -> db.achievementDao().upsert(record.toEntity(serverVersion))
        }
    }
}

/** The wire table a record belongs to. */
val SyncRecord.table: SyncTable
    get() = when (this) {
        is ExerciseRecord -> SyncTable.EXERCISES
        is RoutineRecord -> SyncTable.ROUTINES
        is RoutineExerciseRecord -> SyncTable.ROUTINE_EXERCISES
        is WorkoutSessionRecord -> SyncTable.WORKOUT_SESSIONS
        is LoggedExerciseRecord -> SyncTable.LOGGED_EXERCISES
        is LoggedSetRecord -> SyncTable.LOGGED_SETS
        is GymRecord -> SyncTable.GYMS
        is ExercisePhotoRecord -> SyncTable.EXERCISE_PHOTOS
        is UserSettingRecord -> SyncTable.USER_SETTINGS
        is CoachMessageRecord -> SyncTable.COACH_MESSAGES
        is BodyMetricRecord -> SyncTable.BODY_METRICS
        is ProgressPhotoRecord -> SyncTable.PROGRESS_PHOTOS
        is MesocycleRecord -> SyncTable.MESOCYCLES
        is AchievementRecord -> SyncTable.ACHIEVEMENTS
    }
