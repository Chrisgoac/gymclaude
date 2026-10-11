package com.chrisgoac.gymlog.data.stats

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.coach.toDto
import com.chrisgoac.gymlog.data.workout.OfflineWorkoutEditRepository
import com.chrisgoac.gymlog.data.workout.readWorkout
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.BodyMetricEntity
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.UserSettingEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.stats.CoachSnapshot
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ai.CoachSnapshotDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** The Coach snapshot is read from Room for every turn: a historical edit changes the next one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoachSnapshotReadTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java).build()
    private val now = Instant.parse("2026-06-10T12:00:00Z").toEpochMilli()
    private val days = listOf("2026-05-14", "2026-05-17", "2026-05-20", "2026-05-23", "2026-05-26", "2026-05-29", "2026-06-01", "2026-06-04", "2026-06-07", "2026-06-09")

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current = MutableStateFlow<OpenUserDatabase?>(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    @After fun tearDown() = db.close()

    @Test fun `a historical edit changes the next snapshot`() = runBlocking {
        db.catalogDao().upsertAll(listOf(CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null)))
        db.userSettingDao().upsert(UserSettingEntity("objetivoSemanal", 1, null, "4", null))
        db.userSettingDao().upsert(UserSettingEntity("objetivosVolumen", 1, null, """{"espalda":3000}""", null))
        db.userSettingDao().upsert(UserSettingEntity("metricasPersonalizadas", 1, null, """[{"clave":"biceps-izq","label":"Bíceps izq.","unidad":"cm"}]""", null))
        days.forEachIndexed { i, day ->
            val at = Instant.parse("${day}T08:00:00Z").toEpochMilli()
            db.workoutSessionDao().upsert(WorkoutSessionEntity("s$i", 1, null, null, null, null, at, 3600, null, null))
            db.loggedExerciseDao().upsert(LoggedExerciseEntity("le$i", 1, null, "s$i", "seed-press", 0, null))
            db.loggedSetDao().upsert(LoggedSetEntity("set$i", 1, null, "le$i", 0, 100.0, 5, false, null))
        }
        db.bodyMetricDao().upsert(BodyMetricEntity("b1", 1, null, "peso", 81.0, Instant.parse("2026-05-20T08:00:00Z").toEpochMilli(), null))
        db.bodyMetricDao().upsert(BodyMetricEntity("b2", 1, null, "peso", 80.0, Instant.parse("2026-06-08T08:00:00Z").toEpochMilli(), null))
        db.bodyMetricDao().upsert(BodyMetricEntity("b3", 1, null, "biceps-izq", 38.0, Instant.parse("2026-06-01T08:00:00Z").toEpochMilli(), null))
        db.bodyMetricDao().upsert(BodyMetricEntity("b4", 1, 5, "peso", 70.0, Instant.parse("2026-06-09T08:00:00Z").toEpochMilli(), null)) // tombstone
        val manager = Manager(db)
        val stats = OfflineStatsRepository(manager, now = { now }, zone = { ZoneId.of("UTC") })

        val before = stats.readCoachSnapshot(GymFilter.All)

        assertEquals(listOf(CoachSnapshot.Line("Press de banca", 9)), before.stalls)
        assertEquals(4, before.week.target)
        assertEquals(1, before.week.sessions)
        assertEquals(emptyList<CoachSnapshot.Record>(), before.week.records)
        assertEquals(listOf("Pecho", "Espalda"), before.groups.map { it.group })
        assertEquals(3000.0, before.groups.single { it.group == "Espalda" }.target!!, 0.0)
        assertEquals(CoachSnapshot.Weight(80.0, -1.0), before.body.weight)
        assertEquals(listOf(CoachSnapshot.Measure("Bíceps izq.", 38.0, null)), before.body.measures)

        // ONE historical edit: the last session moves to today with 110 kg.
        val original = checkNotNull(db.readWorkout("s9")).let { w ->
            w.copy(session = w.session.copy(updatedAt = 1), exercises = w.exercises.map { e -> e.copy(updatedAt = 1, sets = e.sets.map { it.copy(updatedAt = 1) }) })
        }
        val draft = WorkoutDraft.from(original).withSet("set9") { it.copy(weight = "110") }.copy(startedAt = Instant.parse("2026-06-10T08:00:00Z"))
        val runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
        val result = DefaultSaveWorkoutDraftUseCase(OfflineWorkoutEditRepository(manager) { runner }) { now }(original, draft)
        assertTrue("$result", result is SaveResult.Saved)

        val after = stats.readCoachSnapshot(GymFilter.All)

        assertEquals("the stall is gone", emptyList<CoachSnapshot.Line>(), after.stalls)
        assertEquals(listOf(CoachSnapshot.Record("Press de banca", RecordKind.WEIGHT)), after.week.records)
        assertEquals(550L, after.week.volume)
        assertEquals(0L, after.groups.single { it.group == "Pecho" }.daysWithoutTraining)

        // The wire form carries no ids, keys or URLs.
        val json = ContractJson.encodeToString(CoachSnapshotDto.serializer(), after.toDto())
        listOf("seed-press", "s9", "le9", "set9", "b1", "biceps-izq", "http").forEach { assertFalse("leaks $it: $json", json.contains(it)) }
    }
}
