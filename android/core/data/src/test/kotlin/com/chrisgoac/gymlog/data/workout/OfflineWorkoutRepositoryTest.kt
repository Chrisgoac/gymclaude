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
import com.chrisgoac.gymlog.domain.workout.WorkoutStartException
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymOperation
import com.chrisgoac.gymlog.model.GymRecord
import com.chrisgoac.gymlog.model.LoggedExerciseOperation
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionOperation
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
import com.chrisgoac.gymlog.database.dao.CUSTOM_EXERCISE_NAMES_SQL
import com.chrisgoac.gymlog.database.dao.LIVE_EXERCISES_FOR_SESSION_SQL
import com.chrisgoac.gymlog.database.dao.LIVE_SETS_FOR_SESSION_SQL
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private const val HOUR = 60L * 60 * 1000
private const val DAY = 24 * HOUR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineWorkoutRepositoryTest {
    private var db: GymLogDatabase? = null
    private var now = 1_000_000L
    private var ids = 0
    private val files = mutableListOf<File>()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private class Stack(val db: GymLogDatabase, val routines: OfflineRoutineRepository, val workouts: OfflineWorkoutRepository, val runner: RoomLocalMutationRunner)

    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun stack(database: GymLogDatabase): Stack {
        db = database
        val manager = FakeManager(database)
        val runner = RoomLocalMutationRunner(database, DeviceId("test-device"), clock = { now })
        val runnerFor = { _: GymLogDatabase -> runner }
        val exercises = OfflineExerciseRepository(manager, runnerFor)
        val nextId = { "00000000-0000-4000-8000-%012d".format(++ids) }
        return Stack(
            database,
            OfflineRoutineRepository(manager, runnerFor, exercises, newId = nextId),
            OfflineWorkoutRepository(manager, runnerFor, now = { now }, newId = nextId),
            runner,
        )
    }

    private fun memory() = stack(
        Room.inMemoryDatabaseBuilder(context(), GymLogDatabase::class.java).allowMainThreadQueries().build(),
    )

    private fun fileBacked(file: File) = stack(
        Room.databaseBuilder(context(), GymLogDatabase::class.java, file.path).allowMainThreadQueries().build(),
    )

    @Before
    fun setUp() = Unit

    @After
    fun tearDown() {
        db?.close()
        files.forEach { it.delete() }
    }

    private suspend fun Stack.seedCatalog() = db.catalogDao().upsertAll(
        listOf(
            CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
            CatalogExerciseEntity("seed-curl", "Curl con barra Z", "biceps", "barra", "aislamiento", null, null, null),
        ),
    )

    private fun child(key: String, exerciseId: String, name: String) = RoutineExerciseInput(key, exerciseId, name)

    private suspend fun Stack.createPush() = run {
        seedCatalog()
        routines.create(RoutineInput("Push", "", listOf(child("c-press", "seed-press", "Press"), child("c-curl", "seed-curl", "Curl"))))
    }

    private suspend fun Stack.addGym(id: String, deleted: Boolean = false, archived: Boolean = false) {
        runner.run(
            LocalMutation {
                val stamp = nextTimestamp(SyncTable.GYMS, RecordId(id))
                put(GymRecord(RecordId(id), stamp, if (deleted) stamp else null, "Gym $id", 0, archived))
            },
        )
    }

    private fun outbox(database: GymLogDatabase) =
        database.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { c ->
            buildList { while (c.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), c.getString(0))) }
        }

    private fun sessionCount(database: GymLogDatabase) =
        database.query("SELECT COUNT(*) FROM workout_sessions", null).use { it.moveToFirst(); it.getInt(0) }

    // ---- Start ----

    @Test fun `free start creates one active session in one outbox row`() = runTest {
        val s = memory()
        s.addGym("gym-1")
        val before = outbox(s.db).size

        val started = s.workouts.startFreeWorkout("gym-1")

        assertFalse(started.resumed)
        val active = s.workouts.observeActiveWorkout().first()
        assertNotNull(active)
        assertEquals(started.sessionId, active!!.session.id)
        assertEquals("gym-1", active.session.gymId)
        assertNull(active.session.routineId)
        assertNull(active.session.durationSeconds)
        assertEquals(now, active.session.startedAt)
        assertTrue(active.exercises.isEmpty())
        val rows = outbox(s.db).drop(before)
        assertEquals(1, rows.size)
        assertTrue(rows.single().operations.single() is WorkoutSessionOperation)
    }

    @Test fun `free start without gym is allowed`() = runTest {
        val s = memory()
        s.workouts.startFreeWorkout(null)
        assertNull(s.workouts.observeActiveWorkout().first()!!.session.gymId)
    }

    @Test fun `routine start copies live exercises in order with no sets, in one mutation`() = runTest {
        val s = memory()
        val routine = s.createPush()
        val before = outbox(s.db).size

        val started = s.workouts.startRoutineWorkout(routine.id, null)

        val active = s.workouts.observeActiveWorkout().first()!!
        assertEquals(started.sessionId, active.session.id)
        assertEquals(routine.id, active.session.routineId)
        assertEquals(listOf("seed-press", "seed-curl"), active.exercises.map { it.exerciseId })
        assertEquals(listOf("Press de banca", "Curl con barra Z"), active.exercises.map { it.exerciseName })
        assertEquals(listOf(0, 1), active.exercises.map { it.position })
        assertTrue(active.exercises.all { it.sets.isEmpty() })
        val rows = outbox(s.db).drop(before)
        assertEquals(1, rows.size)
        val ops = rows.single().operations
        assertEquals(3, ops.size)
        assertTrue(ops.first() is WorkoutSessionOperation)
        assertEquals(2, ops.filterIsInstance<LoggedExerciseOperation>().size)
    }

    @Test fun `tombstoned routine exercises are skipped`() = runTest {
        val s = memory()
        val routine = s.createPush()
        s.routines.update(routine.id, RoutineInput("Push", "", listOf(child("c-press", "seed-press", "Press"))))

        s.workouts.startRoutineWorkout(routine.id, null)

        assertEquals(listOf("seed-press"), s.workouts.observeActiveWorkout().first()!!.exercises.map { it.exerciseId })
    }

    @Test fun `a routine with more than 50 exercises is chunked inside one transaction with the session last`() = runTest {
        val s = memory()
        s.seedCatalog()
        val routine = s.routines.create(RoutineInput("Grande", "", List(30) { child("k$it", "seed-press", "Press") }))
        s.routines.update(routine.id, RoutineInput("Grande", "", List(30) { child("k$it", "seed-press", "Press") } + List(25) { child("m$it", "seed-curl", "Curl") }))
        val before = outbox(s.db).size

        s.workouts.startRoutineWorkout(routine.id, null)

        val rows = outbox(s.db).drop(before)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.operations.size <= 50 })
        assertEquals(56, rows.sumOf { it.operations.size })
        assertTrue(rows.last().operations.any { it is WorkoutSessionOperation })
        assertEquals(55, s.workouts.observeActiveWorkout().first()!!.exercises.size)
    }

    // ---- Invariants ----

    @Test fun `starting while a workout is active returns it and writes nothing`() = runTest {
        val s = memory()
        val routine = s.createPush()
        val first = s.workouts.startFreeWorkout(null)
        val before = outbox(s.db).size

        val again = s.workouts.startRoutineWorkout(routine.id, null)
        val freeAgain = s.workouts.startFreeWorkout(null)

        assertEquals(first.sessionId, again.sessionId)
        assertTrue(again.resumed)
        assertEquals(first.sessionId, freeAgain.sessionId)
        assertEquals(1, sessionCount(s.db))
        assertEquals(before, outbox(s.db).size)
    }

    @Test fun `concurrent duplicate taps create a single session`() = runTest {
        val s = memory()
        val routine = s.createPush()

        val results = coroutineScope {
            List(6) { index ->
                async { if (index % 2 == 0) s.workouts.startFreeWorkout(null) else s.workouts.startRoutineWorkout(routine.id, null) }
            }.awaitAll()
        }

        assertEquals(1, results.map { it.sessionId }.toSet().size)
        assertEquals(1, results.count { !it.resumed })
        assertEquals(1, sessionCount(s.db))
    }

    @Test fun `a finished workout is no longer active and a new one can start`() = runTest {
        val s = memory()
        val first = s.workouts.startFreeWorkout(null)
        s.db.openHelper.writableDatabase.execSQL("UPDATE workout_sessions SET duracionSegundos = 600 WHERE id = '${first.sessionId}'")

        assertNull(s.workouts.observeActiveWorkout().first())
        val second = s.workouts.startFreeWorkout(null)

        assertFalse(second.resumed)
        assertTrue(second.sessionId != first.sessionId)
        assertEquals(2, sessionCount(s.db))
    }

    @Test fun `a deleted active session no longer blocks starting`() = runTest {
        val s = memory()
        val first = s.workouts.startFreeWorkout(null)
        s.db.openHelper.writableDatabase.execSQL("UPDATE workout_sessions SET deletedAt = 5 WHERE id = '${first.sessionId}'")

        assertFalse(s.workouts.startFreeWorkout(null).resumed)
    }

    // ---- Invalid input ----

    private suspend fun assertNothingWritten(s: Stack, before: Int, block: suspend () -> Unit): Throwable {
        val error = try {
            block()
            fail("expected a typed failure")
            error("unreachable")
        } catch (e: WorkoutStartException) {
            e
        }
        assertEquals(0, sessionCount(s.db))
        assertEquals(before, outbox(s.db).size)
        return error
    }

    @Test fun `unknown and tombstoned gyms are rejected before writing`() = runTest {
        val s = memory()
        s.addGym("gone", deleted = true)
        val before = outbox(s.db).size

        val unknown = assertNothingWritten(s, before) { s.workouts.startFreeWorkout("nope") }
        val gone = assertNothingWritten(s, before) { s.workouts.startFreeWorkout("gone") }

        assertTrue(unknown is WorkoutStartException.GymUnavailable)
        assertEquals("gone", (gone as WorkoutStartException.GymUnavailable).gymId)
    }

    @Test fun `invalid gym on a routine start is rejected too`() = runTest {
        val s = memory()
        val routine = s.createPush()
        val before = outbox(s.db).size
        assertNothingWritten(s, before) { s.workouts.startRoutineWorkout(routine.id, "nope") }
    }

    @Test fun `missing, deleted and archived routines are typed errors`() = runTest {
        val s = memory()
        val routine = s.createPush()
        val deleted = s.routines.create(RoutineInput("Vieja", "", listOf(child("c-x", "seed-press", "Press"))))
        s.routines.delete(deleted.id)
        val archived = s.routines.create(RoutineInput("Archivada"))
        s.routines.setArchived(archived.id, true)
        val before = outbox(s.db).size

        assertTrue(assertNothingWritten(s, before) { s.workouts.startRoutineWorkout("nope", null) } is WorkoutStartException.RoutineNotFound)
        assertTrue(assertNothingWritten(s, before) { s.workouts.startRoutineWorkout(deleted.id, null) } is WorkoutStartException.RoutineNotFound)
        assertTrue(assertNothingWritten(s, before) { s.workouts.startRoutineWorkout(archived.id, null) } is WorkoutStartException.RoutineArchived)
        // The healthy routine still starts afterwards.
        assertFalse(s.workouts.startRoutineWorkout(routine.id, null).resumed)
    }

    @Test fun `archived gyms are hidden from selection but tombstoned ones too`() = runTest {
        val s = memory()
        s.addGym("a")
        s.addGym("b", archived = true)
        s.addGym("c", deleted = true)
        assertEquals(listOf("a"), s.workouts.observeGyms().first().map { it.id })
    }

    // ---- Offline restart ----

    @Test fun `a new repository over the same database file sees the active workout`() = runTest {
        val file = File.createTempFile("gymlog-workout", ".db").also { files += it }
        val first = fileBacked(file)
        val routine = first.createPush()
        val started = first.workouts.startRoutineWorkout(routine.id, null)
        first.db.close()

        val second = fileBacked(file)
        val active = second.workouts.observeActiveWorkout().first()

        assertEquals(started.sessionId, active!!.session.id)
        assertEquals(listOf("seed-press", "seed-curl"), active.exercises.map { it.exerciseId })
        assertTrue(second.workouts.startFreeWorkout(null).resumed)
        assertEquals(1, sessionCount(second.db))
    }

    // ---- Summary ----

    @Test fun `training summary counts this week's sessions against the goal setting`() = runTest {
        val s = memory()
        val first = s.workouts.startFreeWorkout(null)
        s.db.openHelper.writableDatabase.execSQL("UPDATE workout_sessions SET duracionSegundos = 60 WHERE id = '${first.sessionId}'")
        s.workouts.startFreeWorkout(null)

        val summary = s.workouts.observeTrainingSummary().first()

        assertEquals(2, summary.weekSessions)
        assertEquals(3, summary.weeklyGoal)
        assertEquals(0, summary.streakWeeks)
    }

    @Test fun `the weekly summary counts an abandoned unfinished session of this week too (PWA parity)`() = runTest {
        val s = memory()
        now = 1_800_000_000_000L // Friday 2027-01-15 08:00 UTC: 13 h earlier is the same week in every zone
        val start = now
        now = start - 13 * HOUR
        s.workouts.startFreeWorkout(null) // abandoned by the time it is read
        now = start

        assertNull(s.workouts.observeActiveWorkout().first())
        assertEquals(1, s.workouts.observeTrainingSummary().first().weekSessions)
    }

    // ---- Active window (12 h) ----

    @Test fun `a months-old unfinished session is not active and does not block starting`() = runTest {
        val s = memory()
        now = 100 * DAY
        val old = s.workouts.startFreeWorkout(null)
        now += 90 * DAY

        assertNull(s.workouts.observeActiveWorkout().first())
        val fresh = s.workouts.startFreeWorkout(null)

        assertFalse(fresh.resumed)
        assertTrue(fresh.sessionId != old.sessionId)
        assertEquals(fresh.sessionId, s.workouts.observeActiveWorkout().first()!!.session.id)
        // The abandoned one is untouched (still unfinished, still live).
        assertNull(s.workouts.observeWorkout(old.sessionId).first()!!.session.durationSeconds)
    }

    @Test fun `the window boundary is inclusive at 12 h and stale one millisecond later`() = runTest {
        val s = memory()
        now = 10 * DAY
        val started = s.workouts.startFreeWorkout(null)
        now += 12 * HOUR
        assertEquals(started.sessionId, s.workouts.observeActiveWorkout().first()?.session?.id)
        assertTrue(s.workouts.startFreeWorkout(null).resumed)
        now += 1
        assertNull(s.workouts.observeActiveWorkout().first())
        assertFalse(s.workouts.startFreeWorkout(null).resumed)
    }

    @Test fun `with two fresh unfinished sessions the newest is active and a start resumes it`() = runTest {
        val s = memory()
        now = 10 * DAY
        val local = s.workouts.startFreeWorkout(null)
        val synced = "00000000-0000-4000-8000-999999999999"
        s.runner.run(
            LocalMutation {
                put(WorkoutSessionRecord(id = RecordId(synced), updatedAt = nextTimestamp(SyncTable.WORKOUT_SESSIONS, RecordId(synced)), deletedAt = null, fecha = now + 1_000))
            },
        )
        now += 2_000

        assertEquals(synced, s.workouts.observeActiveWorkout().first()!!.session.id)
        val again = s.workouts.startFreeWorkout(null)
        assertTrue(again.resumed)
        assertEquals(synced, again.sessionId)
        assertTrue(local.sessionId != synced)
    }

    // ---- Scoped reads ----

    @Test fun `observeWorkout reads one workout out of a large history with index-scoped queries`() = runTest {
        val s = memory()
        s.seedCatalog()
        s.runner.run(LocalMutation { put(com.chrisgoac.gymlog.model.ExerciseRecord(RecordId("custom-1"), nextTimestamp(SyncTable.EXERCISES, RecordId("custom-1")), null, nombre = "Mi press", grupoMuscular = "pecho", equipamiento = "barra", tipo = "compuesto", esPersonalizado = true)) })
        // 1,500 past sessions x 3 exercises x 4 sets = 18,000 set rows that must not be read.
        s.db.runInTransaction {
            for (n in 0 until 1_500) {
                val session = "hist-$n"
                s.db.openHelper.writableDatabase.execSQL(
                    "INSERT INTO workout_sessions (id, updatedAt, fecha, duracionSegundos) VALUES ('$session', 1, ${n * 1000L}, 3600)",
                )
                for (e in 0 until 3) {
                    val le = "$session-le$e"
                    s.db.openHelper.writableDatabase.execSQL(
                        "INSERT INTO logged_exercises (id, updatedAt, sessionId, exerciseId, orden) VALUES ('$le', 1, '$session', 'seed-press', $e)",
                    )
                    for (k in 0 until 4) {
                        s.db.openHelper.writableDatabase.execSQL(
                            "INSERT INTO logged_sets (id, updatedAt, loggedExerciseId, orden, peso, reps) VALUES ('$le-s$k', 1, '$le', $k, 50.0, 10)",
                        )
                    }
                }
            }
        }
        assertEquals(18_000, s.db.query("SELECT COUNT(*) FROM logged_sets", null).use { it.moveToFirst(); it.getInt(0) })
        now = 10 * DAY
        val id = s.workouts.startFreeWorkout(null).sessionId
        val log = OfflineWorkoutLogRepository(FakeManager(s.db), { s.runner }, newId = { "00000000-0000-4000-8000-%012d".format(++ids) })
        val press = log.addExercise(id, "seed-press")
        val mine = log.addExercise(id, "custom-1")
        log.addSet(press, 80.0, 5)
        log.addSet(press, 82.5, 4)
        log.addSet(mine, 30.0, 12)

        val workout = s.workouts.observeWorkout(id).first()!!

        assertEquals(listOf("Press de banca", "Mi press"), workout.exercises.map { it.exerciseName })
        assertEquals(listOf(listOf(80.0 to 5, 82.5 to 4), listOf(30.0 to 12)), workout.exercises.map { e -> e.sets.map { it.weight to it.reps } })
        assertEquals(workout, s.workouts.observeActiveWorkout().first())

        // Every query of the read is an index probe: no full scan of sessions, exercises or sets.
        fun plan(sql: String) = s.db.query("EXPLAIN QUERY PLAN $sql", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(3)) } }
        val sets = plan(LIVE_SETS_FOR_SESSION_SQL.replace(":sessionId", "'$id'"))
        assertTrue(sets.toString(), sets.any { "index_logged_exercises_sessionId" in it })
        assertTrue(sets.toString(), sets.any { "index_logged_sets_loggedExerciseId" in it })
        val exercises = plan(LIVE_EXERCISES_FOR_SESSION_SQL.replace(":sessionId", "'$id'"))
        assertTrue(exercises.toString(), exercises.any { "index_logged_exercises_sessionId" in it })
        val names = plan(CUSTOM_EXERCISE_NAMES_SQL.replace(":ids", "'seed-press', 'custom-1'"))
        for (step in sets + exercises + names) {
            assertFalse(step, Regex("""^SCAN (ls|le|logged_sets|logged_exercises|exercises)\b""").containsMatchIn(step) && "INDEX" !in step)
        }
        assertTrue(names.toString(), names.any { "PRIMARY KEY" in it || "sqlite_autoindex" in it })
    }
}
