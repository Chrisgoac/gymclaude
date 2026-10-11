package com.chrisgoac.gymlog.data.workout

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.data.routine.OfflineRoutineRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseInput
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.workout.FinishResult
import com.chrisgoac.gymlog.domain.workout.FinishWorkoutUseCase
import com.chrisgoac.gymlog.domain.workout.HistoryFilter
import com.chrisgoac.gymlog.domain.workout.HistoryPeriod
import com.chrisgoac.gymlog.domain.workout.WorkoutRowMissingException
import com.chrisgoac.gymlog.domain.workout.WorkoutStartException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private const val DAY = 24L * 60 * 60 * 1000

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineHistoryRepositoryTest {
    private var db: GymLogDatabase? = null
    private var now = 1_000_000_000_000L
    private var ids = 0
    private val files = mutableListOf<File>()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private class Stack(
        val db: GymLogDatabase,
        val routines: OfflineRoutineRepository,
        val workouts: OfflineWorkoutRepository,
        val log: OfflineWorkoutLogRepository,
        val history: OfflineHistoryRepository,
        val runner: RoomLocalMutationRunner,
        val finish: FinishWorkoutUseCase,
    )

    private fun stack(database: GymLogDatabase): Stack {
        db = database
        val manager = FakeManager(database)
        val runner = RoomLocalMutationRunner(database, DeviceId("test-device"), clock = { now })
        val runnerFor = { _: GymLogDatabase -> runner }
        val exercises = OfflineExerciseRepository(manager, runnerFor)
        val nextId = { "00000000-0000-4000-8000-%012d".format(++ids) }
        val routines = OfflineRoutineRepository(manager, runnerFor, exercises, newId = nextId)
        val workouts = OfflineWorkoutRepository(manager, runnerFor, exercises, now = { now }, newId = nextId)
        return Stack(
            database, routines, workouts,
            OfflineWorkoutLogRepository(manager, runnerFor, newId = nextId),
            OfflineHistoryRepository(manager, runnerFor, exercises, routines, now = { now }),
            runner,
            FinishWorkoutUseCase(workouts, workouts),
        )
    }

    private fun context() = ApplicationProvider.getApplicationContext<Context>()
    private fun memory() = stack(Room.inMemoryDatabaseBuilder(context(), GymLogDatabase::class.java).allowMainThreadQueries().build())
    private fun fileBacked(file: File) = stack(Room.databaseBuilder(context(), GymLogDatabase::class.java, file.path).allowMainThreadQueries().build())

    @After fun tearDown() {
        db?.close()
        files.forEach { it.delete() }
    }

    private suspend fun Stack.seed() = db.catalogDao().upsertAll(
        listOf(
            CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
            CatalogExerciseEntity("seed-remo", "Remo con barra", "espalda", "barra", "compuesto", null, null, null),
        ),
    )

    private suspend fun Stack.addGym(id: String, name: String = "Gym $id") {
        runner.run(LocalMutation { put(GymRecord(RecordId(id), nextTimestamp(SyncTable.GYMS, RecordId(id)), null, name, 0, false)) })
    }

    /** Starts, logs one set per given (exercise, weight, reps) and finishes [minutes] later. */
    private suspend fun Stack.workout(
        at: Long,
        gym: String? = null,
        routineId: String? = null,
        sets: List<Triple<String, Double, Int>> = listOf(Triple("seed-press", 100.0, 5)),
        notes: String? = null,
        minutes: Int = 30,
        finished: Boolean = true,
    ): String {
        val base = now // the test's "present" is restored afterwards, so relative times stay stable
        now = at
        val id = if (routineId == null) workouts.startFreeWorkout(gym).sessionId else workouts.startRoutineWorkout(routineId, gym).sessionId
        val logged = mutableMapOf<String, String>()
        val agg = workouts.observeWorkout(id).first()!!
        agg.exercises.forEach { logged[it.exerciseId] = it.id }
        sets.forEach { (exerciseId, weight, reps) ->
            val le = logged.getOrPut(exerciseId) { log.addExercise(id, exerciseId) }
            log.addSet(le, weight, reps)
        }
        if (finished) {
            now = at + minutes * 60_000L
            check(finish(id, notes) is FinishResult.Finished)
        }
        now = base
        return id
    }

    private suspend fun Stack.finish(id: String, notes: String?, confirmEmpty: Boolean = false) = finish.invoke(id, notes, confirmEmpty)

    private fun outbox(database: GymLogDatabase) =
        database.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { c ->
            buildList { while (c.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), c.getString(0))) }
        }

    // ---- Finish ----

    @Test fun `finish stores the rounded duration and notes in ONE outbox row and the session leaves the active slot`() = runTest {
        val s = memory(); s.seed()
        now = 5_000_000L
        val id = s.workouts.startFreeWorkout(null).sessionId
        val le = s.log.addExercise(id, "seed-press")
        s.log.addSet(le, 80.0, 5)
        val before = outbox(s.db!!).size

        now = 5_000_000L + 1_800_499L // 1800.499 s rounds to 1800
        val result = s.finish(id, "  buen día ")

        val finished = result as FinishResult.Finished
        assertFalse(finished.alreadyFinished)
        assertEquals(1800, finished.summary.durationSeconds)
        assertEquals(1, finished.summary.exerciseCount)
        assertEquals(1, finished.summary.setCount)
        assertEquals(400.0, finished.summary.volume, 0.0)
        val rows = outbox(s.db!!).drop(before)
        assertEquals(1, rows.size)
        val op = rows.single().operations.single() as WorkoutSessionOperation
        assertEquals(1800, op.record.duracionSegundos)
        assertEquals("buen día", op.record.notas)
        assertNull(s.workouts.observeActiveWorkout().first())
    }

    @Test fun `finishing twice is a no-op success that keeps the first completion`() = runTest {
        val s = memory(); s.seed()
        val id = s.workout(at = now)
        val before = outbox(s.db!!).size
        val first = s.history.observeDetail(id).first()!!.summary.durationSeconds

        now += 10 * DAY
        val again = s.finish(id, "otra nota") as FinishResult.Finished

        assertTrue(again.alreadyFinished)
        assertEquals(first, again.summary.durationSeconds)
        assertEquals(before, outbox(s.db!!).size)
        assertNull(s.history.observeDetail(id).first()!!.workout.session.notes)
    }

    @Test fun `a workout without a repetition asks for confirmation and writes nothing until confirmed`() = runTest {
        val s = memory(); s.seed()
        val id = s.workout(at = now, sets = listOf(Triple("seed-press", 0.0, 0)), finished = false)
        val before = outbox(s.db!!).size

        assertEquals(FinishResult.NeedsConfirmation, s.finish(id, null))
        assertEquals(before, outbox(s.db!!).size)
        assertNotNull(s.workouts.observeActiveWorkout().first())

        assertTrue(s.finish(id, null, confirmEmpty = true) is FinishResult.Finished)
        assertNull(s.workouts.observeActiveWorkout().first())
    }

    @Test fun `finishing a deleted or unknown workout is NotFound`() = runTest {
        val s = memory(); s.seed()
        val id = s.workout(at = now)
        s.history.deleteSession(id)
        assertEquals(FinishResult.NotFound, s.finish(id, null))
        assertEquals(FinishResult.NotFound, s.finish("nope", null))
        try {
            s.workouts.finish("nope", null)
            fail()
        } catch (_: WorkoutRowMissingException) {
        }
    }

    @Test fun `a finished workout survives restarting the app offline`() = runTest {
        val file = File.createTempFile("gymlog-history", ".db").also { files += it }
        val first = fileBacked(file); first.seed()
        val id = first.workout(at = now, notes = "pierna")
        first.db.close()

        val second = fileBacked(file)
        val page = second.history.observeHistory(HistoryFilter(), 10).first()
        assertEquals(listOf(id), page.items.map { it.sessionId })
        assertEquals("pierna", page.items.single().notes)
        assertEquals(1, outbox(second.db).count { it.operations.any { op -> op is WorkoutSessionOperation && op.record.duracionSegundos != null } })
    }

    // ---- List ----

    @Test fun `the list shows finished sessions only, newest first, with totals`() = runTest {
        val s = memory(); s.seed()
        val old = s.workout(at = now - 3 * DAY, sets = listOf(Triple("seed-press", 100.0, 5), Triple("seed-remo", 50.0, 10)))
        val recent = s.workout(at = now - DAY, sets = listOf(Triple("seed-press", 60.0, 10)))
        s.workout(at = now, finished = false) // active

        val page = s.history.observeHistory(HistoryFilter(), 10).first()

        assertEquals(listOf(recent, old), page.items.map { it.sessionId })
        assertFalse(page.hasMore)
        val oldItem = page.items.last()
        assertEquals(2, oldItem.exerciseCount)
        assertEquals(2, oldItem.setCount)
        assertEquals(1000.0, oldItem.volume, 0.0)
        assertEquals(1800, oldItem.durationSeconds)
    }

    @Test fun `the page is limited and reports more`() = runTest {
        val s = memory(); s.seed()
        repeat(5) { s.workout(at = now - (5 - it) * DAY) }

        val page = s.history.observeHistory(HistoryFilter(), 3).first()

        assertEquals(3, page.items.size)
        assertTrue(page.hasMore)
        assertTrue(page.items.zipWithNext().all { (a, b) -> a.startedAt > b.startedAt })
    }

    @Test fun `period filter uses the PWA windows`() = runTest {
        val s = memory(); s.seed()
        val today = s.workout(at = now - 2 * DAY)
        val month2 = s.workout(at = now - 60 * DAY)
        val months6 = s.workout(at = now - 200 * DAY)
        val over = s.workout(at = now - 400 * DAY)
        suspend fun ids(p: HistoryPeriod) = s.history.observeHistory(HistoryFilter(period = p), 20).first().items.map { it.sessionId }

        assertEquals(listOf(today), ids(HistoryPeriod.WEEKS_4))
        assertEquals(listOf(today, month2), ids(HistoryPeriod.MONTHS_3))
        assertEquals(listOf(today, month2, months6), ids(HistoryPeriod.YEAR))
        assertEquals(listOf(today, month2, months6, over), ids(HistoryPeriod.ALL))
    }

    @Test fun `gym filter keeps only that gym and names resolve`() = runTest {
        val s = memory(); s.seed(); s.addGym("g1", "Fitness Park"); s.addGym("g2")
        val a = s.workout(at = now - 2 * DAY, gym = "g1")
        s.workout(at = now - DAY, gym = "g2")
        s.workout(at = now)

        val page = s.history.observeHistory(HistoryFilter(gymId = "g1"), 10).first()

        assertEquals(listOf(a), page.items.map { it.sessionId })
        assertEquals("Fitness Park", page.items.single().gymName)
    }

    @Test fun `text query matches routine, exercise and notes ignoring case and accents`() = runTest {
        val s = memory(); s.seed()
        val routine = s.routines.create(RoutineInput("Empuje", "", listOf(RoutineExerciseInput("k", "seed-press", "Press"))))
        val byRoutine = s.workout(at = now - 4 * DAY, routineId = routine.id)
        val byExercise = s.workout(at = now - 3 * DAY, sets = listOf(Triple("seed-remo", 60.0, 8)))
        val byNotes = s.workout(at = now - 2 * DAY, notes = "Hombro MOLESTO", sets = listOf(Triple("seed-press", 20.0, 8)))
        suspend fun ids(q: String) = s.history.observeHistory(HistoryFilter(query = q), 20).first().items.map { it.sessionId }

        assertEquals(listOf(byRoutine), ids("EMPUJE"))
        assertEquals(listOf(byExercise), ids("remo"))
        assertEquals(listOf(byNotes), ids("hómbro molestó"))
        // "press" is an exercise name: the routine session and the notes session both log it.
        assertEquals(listOf(byNotes, byRoutine), ids("press"))
        assertEquals(emptyList<String>(), ids("sentadilla"))
    }

    @Test fun `text query combines with gym and period filters`() = runTest {
        val s = memory(); s.seed(); s.addGym("g1")
        s.workout(at = now - 100 * DAY, gym = "g1")
        val keep = s.workout(at = now - DAY, gym = "g1")
        s.workout(at = now - 2 * DAY)

        val page = s.history.observeHistory(HistoryFilter(HistoryPeriod.WEEKS_4, "g1", "banca"), 20).first()

        assertEquals(listOf(keep), page.items.map { it.sessionId })
    }

    // ---- Detail ----

    @Test fun `detail lists ordered exercises and sets without deleted children`() = runTest {
        val s = memory(); s.seed(); s.addGym("g1", "Casa")
        now -= DAY
        val id = s.workouts.startFreeWorkout("g1").sessionId
        val press = s.log.addExercise(id, "seed-press")
        val remo = s.log.addExercise(id, "seed-remo")
        s.log.addSet(press, 100.0, 5)
        val gone = s.log.addSet(press, 105.0, 3)
        s.log.addSet(remo, 50.0, 10)
        s.log.removeSet(gone)
        s.log.moveExercise(remo, -1)
        now += 3_600_000
        s.finish(id, "ok")

        val detail = s.history.observeDetail(id).first()!!

        assertEquals(listOf("Remo con barra", "Press de banca"), detail.workout.exercises.map { it.exerciseName })
        assertEquals(listOf(100.0), detail.workout.exercises.last().sets.map { it.weight })
        assertEquals("Casa", detail.gymName)
        assertEquals("ok", detail.workout.session.notes)
        assertEquals(3600, detail.workout.session.durationSeconds)
        assertEquals(2, detail.summary.setCount)
        assertEquals(1000.0, detail.summary.volume, 0.0)
    }

    @Test fun `change gym is one mutation, a no-op when unchanged and validates the gym`() = runTest {
        val s = memory(); s.seed(); s.addGym("g1"); s.addGym("g2")
        val id = s.workout(at = now - DAY, gym = "g1")
        val before = outbox(s.db!!).size

        now += 5
        s.history.changeGym(id, "g2")
        assertEquals(before + 1, outbox(s.db!!).size)
        assertEquals("g2", s.history.observeDetail(id).first()!!.workout.session.gymId)
        assertTrue((outbox(s.db!!).last().operations.single() as WorkoutSessionOperation).record.duracionSegundos != null)

        s.history.changeGym(id, "g2")
        assertEquals(before + 1, outbox(s.db!!).size)

        s.history.changeGym(id, null)
        assertNull(s.history.observeDetail(id).first()!!.gymName)
        try {
            s.history.changeGym(id, "nope")
            fail()
        } catch (e: WorkoutStartException.GymUnavailable) {
            assertEquals("nope", e.gymId)
        }
    }

    // ---- Delete ----

    @Test fun `delete tombstones session, exercises and sets in one mutation and is idempotent`() = runTest {
        val s = memory(); s.seed()
        val id = s.workout(at = now - DAY, sets = listOf(Triple("seed-press", 100.0, 5), Triple("seed-press", 90.0, 8), Triple("seed-remo", 50.0, 10)))
        val before = outbox(s.db!!).size
        now += 100

        s.history.deleteSession(id)

        val rows = outbox(s.db!!).drop(before)
        assertEquals(1, rows.size)
        val ops = rows.single().operations
        assertEquals(1, ops.filterIsInstance<WorkoutSessionOperation>().size)
        assertEquals(2, ops.filterIsInstance<LoggedExerciseOperation>().size)
        assertEquals(3, ops.filterIsInstance<LoggedSetOperation>().size)
        assertTrue(ops.last() is WorkoutSessionOperation)
        assertTrue(ops.all { op -> op.record.deletedAt != null })
        assertNull(s.history.observeDetail(id).first())
        assertTrue(s.history.observeHistory(HistoryFilter(), 10).first().items.isEmpty())

        s.history.deleteSession(id)
        assertEquals(before + 1, outbox(s.db!!).size)
    }

    @Test fun `deleting a big workout is split in chunks of at most 50 inside one transaction`() = runTest {
        val s = memory(); s.seed()
        val id = s.workout(at = now - DAY, sets = List(60) { Triple("seed-press", 20.0 + it, 5) })
        val before = outbox(s.db!!).size
        now += 100

        s.history.deleteSession(id)

        val rows = outbox(s.db!!).drop(before)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.operations.size <= 50 })
        assertEquals(62, rows.sumOf { it.operations.size })
        assertTrue(rows.last().operations.any { it is WorkoutSessionOperation })
        assertNull(s.history.observeDetail(id).first())
        val live = s.db!!.query("SELECT COUNT(*) FROM logged_sets WHERE deletedAt IS NULL", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, live)
    }
}
