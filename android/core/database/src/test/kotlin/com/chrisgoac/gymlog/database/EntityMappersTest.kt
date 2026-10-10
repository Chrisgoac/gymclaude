package com.chrisgoac.gymlog.database

import com.chrisgoac.gymlog.database.entity.toEntity
import com.chrisgoac.gymlog.database.entity.toRecord
import com.chrisgoac.gymlog.model.AchievementRecord
import com.chrisgoac.gymlog.model.BodyMetricRecord
import com.chrisgoac.gymlog.model.CoachMessageRecord
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.ExercisePhotoRecord
import com.chrisgoac.gymlog.model.ExerciseRecord
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseRecord
import com.chrisgoac.gymlog.model.LoggedSetRecord
import com.chrisgoac.gymlog.model.MesocycleRecord
import com.chrisgoac.gymlog.model.ProgressPhotoRecord
import com.chrisgoac.gymlog.model.PullResponse
import com.chrisgoac.gymlog.model.RoutineExerciseRecord
import com.chrisgoac.gymlog.model.RoutineRecord
import com.chrisgoac.gymlog.model.SyncRecord
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** entity <-> record round trips, one per table, with every field set and with the fixture's nulls. */
class EntityMappersTest {

    private fun roundTrip(record: SyncRecord): SyncRecord = when (record) {
        is ExerciseRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is RoutineRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is RoutineExerciseRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is WorkoutSessionRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is LoggedExerciseRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is LoggedSetRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is GymRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is ExercisePhotoRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is UserSettingRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is CoachMessageRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is BodyMetricRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is ProgressPhotoRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is MesocycleRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
        is AchievementRecord -> record.toEntity(42).also { assertEquals(42L, it.serverVersion) }.toRecord()
    }

    @Test fun exercise() = assertEquals(SampleRecords.exercise(), roundTrip(SampleRecords.exercise()))
    @Test fun routine() = assertEquals(SampleRecords.routine(), roundTrip(SampleRecords.routine()))
    @Test fun routineExercise() = assertEquals(SampleRecords.routineExercise(), roundTrip(SampleRecords.routineExercise()))
    @Test fun workoutSession() = assertEquals(SampleRecords.session(), roundTrip(SampleRecords.session()))
    @Test fun loggedExercise() = assertEquals(SampleRecords.loggedExercise(), roundTrip(SampleRecords.loggedExercise()))
    @Test fun loggedSet() = assertEquals(SampleRecords.loggedSet(), roundTrip(SampleRecords.loggedSet()))
    @Test fun gym() = assertEquals(SampleRecords.gym(), roundTrip(SampleRecords.gym()))
    @Test fun exercisePhoto() = assertEquals(SampleRecords.exercisePhoto(), roundTrip(SampleRecords.exercisePhoto()))
    @Test fun userSetting() = assertEquals(SampleRecords.userSetting(), roundTrip(SampleRecords.userSetting()))
    @Test fun coachMessage() = assertEquals(SampleRecords.coachMessage(), roundTrip(SampleRecords.coachMessage()))
    @Test fun bodyMetric() = assertEquals(SampleRecords.bodyMetric(), roundTrip(SampleRecords.bodyMetric()))
    @Test fun progressPhoto() = assertEquals(SampleRecords.progressPhoto(), roundTrip(SampleRecords.progressPhoto()))
    @Test fun mesocycle() = assertEquals(SampleRecords.mesocycle(), roundTrip(SampleRecords.mesocycle()))
    @Test fun achievement() = assertEquals(SampleRecords.achievement(), roundTrip(SampleRecords.achievement()))

    @Test
    fun everyBootstrapFixtureRecordRoundTrips() {
        val text = File(System.getProperty("gymlog.fixturesDir"), "bootstrap-all-entities.json").readText()
        val changes = ContractJson.decodeFromString(PullResponse.serializer(), text).changes
        assertEquals(SyncTable.entries.toSet(), changes.map { it.table }.toSet())
        changes.forEach { change -> assertEquals(change.record, roundTrip(change.record)) }
    }
}
