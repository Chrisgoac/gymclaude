package com.chrisgoac.gymlog.data.workout

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.LoggedSetEntity
import com.chrisgoac.gymlog.domain.history.DefaultSaveWorkoutDraftUseCase
import com.chrisgoac.gymlog.domain.history.DraftError
import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.history.LoggedExerciseDraft
import com.chrisgoac.gymlog.domain.history.LoggedSetDraft
import com.chrisgoac.gymlog.domain.history.SaveResult
import com.chrisgoac.gymlog.domain.history.WorkoutDraft
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseOperation
import com.chrisgoac.gymlog.model.LoggedSetOperation
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

private const val DAY = 24L * 60 * 60 * 1000

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineWorkoutEditRepositoryTest {
    private var now = 1_000_000_000_000L
    private var ids = 0
    private lateinit var db: GymLogDatabase
    private lateinit var runner: RoomLocalMutationRunner
    private lateinit var workouts: OfflineWorkoutRepository
    private lateinit var log: OfflineWorkoutLogRepository
    private lateinit var history: OfflineHistoryRepository
    private lateinit var edits: OfflineWorkoutEditRepository
    private lateinit var finish: FinishWorkoutUseCase
    private lateinit var save: DefaultSaveWorkoutDraftUseCase

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java)
            .allowMainThreadQueries().build()
        val manager = FakeManager(db)
        runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
        val runnerFor = { _: GymLogDatabase -> runner }
        val exercises = OfflineExerciseRepository(manager, runnerFor)
        val nextId = { "00000000-0000-4000-8000-%012d".format(++ids) }
        val routines = OfflineRoutineRepository(manager, runnerFor, exercises, newId = nextId)
        workouts = OfflineWorkoutRepository(manager, runnerFor, exercises, now = { now }, newId = nextId)
        log = OfflineWorkoutLogRepository(manager, runnerFor, newId = nextId)
        history = OfflineHistoryRepository(manager, runnerFor, exercises, routines, now = { now })
        edits = OfflineWorkoutEditRepository(manager, runnerFor)
        finish = FinishWorkoutUseCase(workouts, workouts)
        save = DefaultSaveWorkoutDraftUseCase(edits) { now }
    }

    @After fun tearDown() = db.close()

    private suspend fun seed() {
        db.catalogDao().upsertAll(
            listOf(
                CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
                CatalogExerciseEntity("seed-remo", "Remo con barra", "espalda", "barra", "compuesto", null, null, null),
                CatalogExerciseEntity("seed-squat", "Sentadilla", "cuadriceps", "barra", "compuesto", null, null, null),
            ),
        )
        listOf("g1", "g2").forEach { id ->
            runner.run(LocalMutation { put(GymRecord(RecordId(id), nextTimestamp(SyncTable.GYMS, RecordId(id)), null, "Gym $id", 0, false)) })
        }
    }

    /** A finished workout at g1: press 3 sets, remo 2 sets. Returns its session id. */
    private suspend fun finishedWorkout(): String {
        val id = workouts.startFreeWorkout("g1").sessionId
        val press = log.addExercise(id, "seed-press")
        listOf(60.0, 62.5, 65.0).forEach { log.addSet(press, it, 8) }
        val remo = log.addExercise(id, "seed-remo")
        log.addSet(remo, 50.0, 10, warmup = true)
        log.addSet(remo, 70.0, 8)
        now += 45 * 60_000L
        finish(id, "Bien")
        now += DAY
        return id
    }

    private suspend fun original(id: String): WorkoutAggregate = history.observeDetail(id).first()!!.workout

    private fun outbox(): List<PushMutation> =
        db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { c ->
            buildList { while (c.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), c.getString(0))) }
        }

    @Test fun `a full edit is ONE outbox row with only the changed rows and stable ids`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        val (press, remo) = original.exercises
        val before = outbox().size
        val newStart = original.session.startedAt - 2 * DAY

        val draft = WorkoutDraft.from(original)
            .copy(startedAt = Instant.ofEpochMilli(newStart), gymId = "g2", notes = "Editado")
            .withSet(press.sets[0].id) { it.copy(weight = "61") } // value change
            .removeSet(press.sets[1].id) // removal: set 3 moves up
            .removeExercise(remo.id) // removal of an exercise and its 2 sets
            .let { it.copy(exercises = listOf(LoggedExerciseDraft("le-new", "seed-squat", "Sentadilla", listOf(LoggedSetDraft("set-new", "100", "5", false)))) + it.exercises) }

        val result = save(original, draft) as SaveResult.Saved

        val rows = outbox().drop(before)
        assertEquals(1, rows.size)
        val ops = rows.single().operations
        val sessions = ops.filterIsInstance<WorkoutSessionOperation>().map { it.record }
        val exercises = ops.filterIsInstance<LoggedExerciseOperation>().map { it.record }.associateBy { it.id.value }
        val sets = ops.filterIsInstance<LoggedSetOperation>().map { it.record }.associateBy { it.id.value }
        assertTrue("session row goes last", ops.last() is WorkoutSessionOperation)
        assertEquals(newStart, sessions.single().fecha)
        assertEquals("g2", sessions.single().gymId)
        assertEquals("Editado", sessions.single().notas)
        assertEquals(45 * 60, sessions.single().duracionSegundos)
        // Tombstones only for real removals.
        assertNotNull(sets.getValue(press.sets[1].id).deletedAt)
        assertNotNull(exercises.getValue(remo.id).deletedAt)
        remo.sets.forEach { assertNotNull(sets.getValue(it.id).deletedAt) }
        // Updated / moved rows keep their ids and become compact.
        assertNull(sets.getValue(press.sets[0].id).deletedAt)
        assertEquals(61.0, sets.getValue(press.sets[0].id).peso, 0.0)
        assertEquals(1, sets.getValue(press.sets[2].id).orden)
        assertEquals(1, exercises.getValue(press.id).orden)
        assertEquals(0, exercises.getValue("le-new").orden)
        assertEquals(0, sets.getValue("set-new").orden)
        assertEquals(setOf("le-new", press.id, remo.id), exercises.keys)
        assertEquals(setOf(press.sets[0].id, press.sets[1].id, press.sets[2].id, "set-new") + remo.sets.map { it.id }, sets.keys)
        assertTrue(InvalidationScope.MUSCLE_BALANCE in result.invalidatedScopes && InvalidationScope.STREAKS in result.invalidatedScopes)

        val stored = original(id)
        assertEquals(listOf("le-new", press.id), stored.exercises.map { it.id })
        assertEquals(listOf(press.sets[0].id, press.sets[2].id), stored.exercises[1].sets.map { it.id })
        assertEquals(listOf(61.0, 65.0), stored.exercises[1].sets.map { it.weight })
    }

    @Test fun `saving an unchanged draft writes nothing`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        val before = outbox().size
        assertEquals(SaveResult.Saved(emptySet()), save(original, WorkoutDraft.from(original)))
        assertEquals(before, outbox().size)
    }

    @Test fun `a newer synced version is a conflict and is not overwritten`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        val target = original.exercises[0].sets[0]
        val before = outbox().size
        // A pull applies a newer version of the set (no outbox row: it came from the server).
        val pulled = db.loggedSetDao().findById(target.id)!!
        db.loggedSetDao().upsert(pulled.copy(reps = 12, updatedAt = pulled.updatedAt + 5_000))

        val draft = WorkoutDraft.from(original).withSet(original.exercises[1].sets[1].id) { it.copy(reps = "9") }
        assertEquals(SaveResult.Conflict, save(original, draft))
        assertEquals(before, outbox().size)
        assertEquals(12, db.loggedSetDao().findById(target.id)!!.reps)
        assertEquals(8, db.loggedSetDao().findById(original.exercises[1].sets[1].id)!!.reps)
    }

    @Test fun `a row added elsewhere is a conflict too`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        db.loggedSetDao().upsert(LoggedSetEntity("remote-set", now, null, original.exercises[0].id, 3, 70.0, 3, false, 7L))
        val before = outbox().size
        assertEquals(SaveResult.Conflict, save(original, WorkoutDraft.from(original).copy(notes = "x")))
        assertEquals(before, outbox().size)
    }

    @Test fun `a deleted workout is not found and nothing is written`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        history.deleteSession(id)
        val before = outbox().size
        assertEquals(SaveResult.NotFound, save(original, WorkoutDraft.from(original).copy(notes = "x")))
        assertEquals(before, outbox().size)
    }

    @Test fun `invalid values and unknown references write nothing`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        val before = outbox().size
        val draft = WorkoutDraft.from(original)
            .withSet(original.exercises[0].sets[0].id) { it.copy(weight = "abc") }
            .copy(gymId = "missing-gym")
            .let { it.copy(exercises = it.exercises + LoggedExerciseDraft("le-x", "custom-missing", null, emptyList())) }
        val result = save(original, draft) as SaveResult.Invalid
        assertEquals(3, result.summary.errors.size)
        assertTrue(result.summary.errors.contains(DraftError.UnknownExercise("le-x", "custom-missing")))
        assertTrue(result.summary.errors.contains(DraftError.GymUnavailable("missing-gym")))
        assertEquals(before, outbox().size)
    }

    @Test fun `an edit past 50 operations is chunked in one transaction with the session last`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        val before = outbox().size
        val many = (0 until 60).map { LoggedSetDraft("bulk-$it", "20", "10", false) }
        val draft = WorkoutDraft.from(original).copy(notes = "Muchas series")
            .withExercise(original.exercises[0].id) { it.copy(sets = it.sets + many) }

        assertTrue(save(original, draft) is SaveResult.Saved)

        val rows = outbox().drop(before)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.operations.size <= 50 })
        assertEquals(61, rows.sumOf { it.operations.size })
        assertTrue(rows.last().operations.last() is WorkoutSessionOperation)
        assertTrue(rows.first().operations.none { it is WorkoutSessionOperation })
        assertEquals(63, original(id).exercises[0].sets.size)
    }

    @Test fun `a failure in the same transaction rolls the whole edit back`() = runTest {
        setUp(); seed()
        val id = finishedWorkout()
        val original = original(id)
        val before = outbox().size
        val draft = WorkoutDraft.from(original).copy(notes = "No llega").removeExercise(original.exercises[1].id)
        try {
            db.withTransaction {
                edits.apply(original, com.chrisgoac.gymlog.domain.history.WorkoutEdit.between(original, draft))
                error("disk full")
            }
            fail("expected the failure")
        } catch (_: IllegalStateException) {
        }
        assertEquals(before, outbox().size)
        assertEquals(original, original(id))
    }
}
