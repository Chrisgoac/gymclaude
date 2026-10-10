package com.chrisgoac.gymlog.data.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.dao.LATEST_HISTORY_SQL
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotFoundException
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.workout.HistorySet
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import com.chrisgoac.gymlog.domain.workout.SetChange
import com.chrisgoac.gymlog.domain.workout.WorkoutRowMissingException
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.LoggedSetOperation
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineWorkoutLogRepositoryTest {
    private var now = 1_000_000L
    private var ids = 0
    private val db: GymLogDatabase = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java)
        .allowMainThreadQueries().build()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private val manager = FakeManager(db)
    private val runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
    private val nextId = { "00000000-0000-4000-8000-%012d".format(++ids) }
    private val exercises = OfflineExerciseRepository(manager, { runner })
    private val workouts = OfflineWorkoutRepository(manager, { runner }, exercises, now = { now }, newId = nextId)
    private val log = OfflineWorkoutLogRepository(manager, { runner }, exercises, newId = nextId)

    @Before fun seedCatalog() = runTest {
        db.catalogDao().upsertAll(
            listOf(
                CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
                CatalogExerciseEntity("seed-curl", "Curl con barra Z", "biceps", "barra", "aislamiento", null, null, null),
            ),
        )
    }

    @After fun tearDown() = db.close()

    private fun outboxCount() = db.query("SELECT COUNT(*) FROM outbox_mutations", null).use { it.moveToFirst(); it.getInt(0) }

    private fun lastOutbox(): PushMutation =
        db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence DESC LIMIT 1", null).use {
            it.moveToFirst()
            ContractJson.decodeFromString(PushMutation.serializer(), it.getString(0))
        }

    private suspend fun session(id: String, fecha: Long, gymId: String? = null, finished: Boolean = true) = runner.run(
        LocalMutation {
            put(WorkoutSessionRecord(RecordId(id), nextTimestamp(SyncTable.WORKOUT_SESSIONS, RecordId(id)), null, gymId = gymId, fecha = fecha, duracionSegundos = if (finished) 60 else null))
        },
    )

    private suspend fun sets(sessionId: String) = workouts.observeWorkout(sessionId).first()!!.exercises.map { e -> e.sets.map { Triple(it.position, it.weight, it.reps) } }

    @Test fun `each logging write is one outbox row and the aggregate reflects it`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        val start = outboxCount()

        val exercise = log.addExercise(sessionId, "seed-press")
        val first = log.addSet(exercise, 60.0, 8)
        log.addSet(exercise, 62.5, 6, warmup = false)
        log.updateSet(first, SetChange(weight = 61.0))

        assertEquals(start + 4, outboxCount())
        assertEquals(listOf(listOf(Triple(0, 61.0, 8), Triple(1, 62.5, 6))), sets(sessionId))
        val op = lastOutbox().operations.single() as LoggedSetOperation
        assertEquals(61.0, op.record.peso, 0.0)
    }

    @Test fun `unchanged updates and repeated removals write nothing`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        val exercise = log.addExercise(sessionId, "seed-press")
        val set = log.addSet(exercise, 60.0, 8)
        val before = outboxCount()

        log.updateSet(set, SetChange(weight = 60.0, reps = 8))
        log.updateSet(set, SetChange())
        assertEquals(before, outboxCount())

        log.removeSet(set)
        log.removeSet(set)
        log.updateSet(set, SetChange(reps = 9))
        log.removeExercise(exercise)
        log.removeExercise(exercise)
        assertEquals(before + 2, outboxCount())
    }

    @Test fun `invalid values are rejected before the transaction`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        val exercise = log.addExercise(sessionId, "seed-press")
        val set = log.addSet(exercise, 60.0, 8)
        val before = outboxCount()
        listOf<suspend () -> Unit>(
            { log.addSet(exercise, -1.0, 8) },
            { log.addSet(exercise, Double.NaN, 8) },
            { log.addSet(exercise, 1000.5, 8) },
            { log.addSet(exercise, 50.0, 1001) },
            { log.updateSet(set, SetChange(weight = Double.POSITIVE_INFINITY)) },
            { log.updateSet(set, SetChange(reps = -2)) },
            { log.updateNotes(sessionId, "x".repeat(2001)) },
        ).forEach { write ->
            try {
                write()
                fail("expected rejection")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(before, outboxCount())
        try {
            log.addSet("missing", 10.0, 5)
            fail("expected missing")
        } catch (_: WorkoutRowMissingException) {
        }
    }

    @Test fun `removing a set compacts its siblings on stable ids`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        val exercise = log.addExercise(sessionId, "seed-press")
        val a = log.addSet(exercise, 10.0, 1)
        val b = log.addSet(exercise, 20.0, 2)
        val c = log.addSet(exercise, 30.0, 3)

        log.removeSet(a)

        val live = workouts.observeWorkout(sessionId).first()!!.exercises.single().sets
        assertEquals(listOf(b to 0, c to 1), live.map { it.id to it.position })
        // Tombstone + two re-positioned siblings in ONE mutation.
        assertEquals(3, lastOutbox().operations.size)
        assertTrue(db.loggedSetDao().findById(a)!!.deletedAt != null)
    }

    @Test fun `moving sets and exercises keeps ids and compact positions`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        val press = log.addExercise(sessionId, "seed-press")
        val curl = log.addExercise(sessionId, "seed-curl")
        val a = log.addSet(press, 10.0, 1)
        val b = log.addSet(press, 20.0, 2)

        log.moveSet(b, -1)
        log.moveSet(b, -5) // already first: nothing to write
        log.moveExercise(curl, -1)
        val before = outboxCount()
        log.moveExercise(curl, -1)
        assertEquals(before, outboxCount())

        val workout = workouts.observeWorkout(sessionId).first()!!
        assertEquals(listOf(curl, press), workout.exercises.map { it.id })
        assertEquals(listOf(0, 1), workout.exercises.map { it.position })
        assertEquals(listOf(b, a), workout.exercises[1].sets.map { it.id })
        assertEquals(listOf(0, 1), workout.exercises[1].sets.map { it.position })
    }

    @Test fun `removing an exercise tombstones its sets and compacts the session`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        val press = log.addExercise(sessionId, "seed-press")
        val curl = log.addExercise(sessionId, "seed-curl")
        val set = log.addSet(press, 10.0, 1)

        log.removeExercise(press)

        val workout = workouts.observeWorkout(sessionId).first()!!
        assertEquals(listOf(curl to 0), workout.exercises.map { it.id to it.position })
        assertTrue(db.loggedSetDao().findById(set)!!.deletedAt != null)
        assertEquals(3, lastOutbox().operations.size)
    }

    @Test fun `notes are trimmed, blank clears them`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        log.updateNotes(sessionId, "  buena sesión  ")
        assertEquals("buena sesión", workouts.observeWorkout(sessionId).first()!!.session.notes)
        log.updateNotes(sessionId, "   ")
        assertNull(workouts.observeWorkout(sessionId).first()!!.session.notes)
    }

    @Test fun `history is the latest earlier session with sets, same gym, excluding the current one`() = runTest {
        session("old", fecha = 1_000, gymId = "g1")
        session("newer", fecha = 2_000, gymId = "g1")
        session("other-gym", fecha = 3_000, gymId = "g2")
        session("empty", fecha = 4_000, gymId = "g1")
        val oldEx = log.addExercise("old", "seed-press")
        log.addSet(oldEx, 50.0, 10)
        val newerEx = log.addExercise("newer", "seed-press")
        log.addSet(newerEx, 20.0, 12, warmup = true)
        log.addSet(newerEx, 60.0, 8)
        log.addSet(newerEx, 62.5, 6)
        log.addSet(log.addExercise("other-gym", "seed-press"), 99.0, 1)
        log.addExercise("empty", "seed-press") // no sets: skipped
        val current = "current".also { session(it, fecha = 5_000, gymId = "g1", finished = false) }
        log.addSet(log.addExercise(current, "seed-press"), 200.0, 1)

        val history = log.observeExerciseHistory(setOf("seed-press", "seed-curl"), current, "g1").first()

        val press = history.getValue("seed-press")
        assertEquals(2_000L, press.date)
        assertEquals(listOf(HistorySet(20.0, 12, true), HistorySet(60.0, 8, false), HistorySet(62.5, 6, false)), press.sets)
        assertEquals(HistorySet(62.5, 6, false), press.lastSet)
        assertEquals(2, press.workingSets!!.size)
        assertTrue("seed-curl" !in history)
        // Without a gym, every gym counts: the newest is the other gym's session.
        assertEquals(3_000L, log.observeExerciseHistory(setOf("seed-press"), current, null).first().getValue("seed-press").date)
    }

    @Test fun `progression settings come from the synced user settings`() = runTest {
        assertEquals(ProgressionMode.OBJETIVO, log.observeProgressionSettings().first().mode)
        runner.run(
            LocalMutation {
                put(UserSettingRecord(RecordId("modoProgresion"), nextTimestamp(SyncTable.USER_SETTINGS, RecordId("modoProgresion")), null, "\"doble\""))
                put(UserSettingRecord(RecordId("incrementos"), nextTimestamp(SyncTable.USER_SETTINGS, RecordId("incrementos")), null, """{"barra":1}"""))
            },
        )
        val settings = log.observeProgressionSettings().first()
        assertEquals(ProgressionMode.DOBLE, settings.mode)
        assertEquals(1.0, settings.increments.getValue(Equipment.BARRA), 0.0)
    }

    @Test fun `adding an unknown exercise is rejected before anything is written`() = runTest {
        val sessionId = workouts.startFreeWorkout(null).sessionId
        val before = outboxCount()
        try {
            log.addExercise(sessionId, "no-such-exercise")
            fail("expected ExerciseNotFoundException")
        } catch (_: ExerciseNotFoundException) {
        }
        assertEquals(before, outboxCount())
    }

    @Test fun `history over a large log reads only the latest earlier session per exercise`() = runTest {
        // 300 finished sessions of press (4 sets each) + 300 of curl, alternating gyms.
        repeat(300) { i ->
            val session = "hist-$i"
            db.workoutSessionDao().upsert(WorkoutSessionEntity(session, 1, null, null, null, if (i % 2 == 0) "g1" else "g2", 10_000L + i, 60, null, 1))
            listOf("seed-press", "seed-curl").forEach { exercise ->
                val le = "$session-$exercise"
                db.loggedExerciseDao().upsert(LoggedExerciseEntity(le, 1, null, session, exercise, 0, 1))
                repeat(4) { k -> db.loggedSetDao().upsert(LoggedSetEntity("$le-$k", 1, null, le, k, 20.0 + i, 5 + k, k == 0, 1)) }
            }
        }
        // The newest press session lost all its sets (tombstoned): skipped. The newest session overall is deleted.
        repeat(4) { k -> db.loggedSetDao().upsert(LoggedSetEntity("hist-298-seed-press-$k", 2, 2, "hist-298-seed-press", k, 0.0, 0, false, 1)) }
        db.workoutSessionDao().upsert(WorkoutSessionEntity("hist-299", 2, 2, null, null, "g2", 10_299L, 60, null, 1))
        db.workoutSessionDao().upsert(WorkoutSessionEntity("current", 1, null, null, null, "g1", 99_999L, null, null, 1))
        db.loggedExerciseDao().upsert(LoggedExerciseEntity("current-press", 1, null, "current", "seed-press", 0, 1))
        db.loggedSetDao().upsert(LoggedSetEntity("current-set", 1, null, "current-press", 0, 500.0, 1, false, 1))

        val anyGym = log.observeExerciseHistory(setOf("seed-press", "seed-curl"), "current", null).first()
        assertEquals(10_297L, anyGym.getValue("seed-press").date)
        assertEquals(10_298L, anyGym.getValue("seed-curl").date)
        assertEquals(listOf(HistorySet(317.0, 5, true), HistorySet(317.0, 6, false), HistorySet(317.0, 7, false), HistorySet(317.0, 8, false)), anyGym.getValue("seed-press").sets)
        val gym1 = log.observeExerciseHistory(setOf("seed-press"), "current", "g1").first()
        assertEquals(10_296L, gym1.getValue("seed-press").date)

        // Query scope: exactly the chosen sessions' sets come back, never the whole history.
        val rows = db.workoutHistoryDao().latestHistory(listOf("seed-press", "seed-curl"), "current", null)
        assertEquals(8, rows.size)
        // And the plan walks the indexes instead of scanning the history tables.
        val sql = LATEST_HISTORY_SQL.replace(":exerciseIds", "'seed-press','seed-curl'").replace(":excludeSessionId", "'current'").replace(":gymId", "NULL")
        val plan = db.query("EXPLAIN QUERY PLAN $sql", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
        assertTrue(plan.toString(), plan.any { "index_logged_exercises_exerciseId" in it })
        assertTrue(plan.toString(), plan.any { "index_logged_sets_loggedExerciseId" in it })
        assertTrue(plan.toString(), plan.none { Regex("""^SCAN (logged_sets|logged_exercises|workout_sessions)\b""").containsMatchIn(it) && "INDEX" !in it })
    }
}
