package com.chrisgoac.gymlog.data.routine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.exercise.OfflineExerciseRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.WorkoutSessionEntity
import com.chrisgoac.gymlog.domain.routine.RoutineError
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseConflictException
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseInput
import com.chrisgoac.gymlog.domain.routine.RoutineField
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineNotFoundException
import com.chrisgoac.gymlog.domain.routine.RoutineValidationException
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RoutineExerciseOperation
import com.chrisgoac.gymlog.model.RoutineOperation
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineRoutineRepositoryTest {
    private lateinit var db: GymLogDatabase
    private lateinit var repository: OfflineRoutineRepository
    private var now = 1_000L
    private var ids = 0

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val manager = FakeManager(db)
        val runner = { database: GymLogDatabase -> RoomLocalMutationRunner(database, com.chrisgoac.gymlog.model.DeviceId("test-device"), clock = { now }) }
        repository = OfflineRoutineRepository(
            manager,
            runner,
            OfflineExerciseRepository(manager, runner),
            newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedCatalog() = db.catalogDao().upsertAll(
        listOf(
            CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", null, null, null),
            CatalogExerciseEntity("seed-curl", "Curl con barra Z", "biceps", "barra", "aislamiento", null, null, null),
            CatalogExerciseEntity("seed-remo", "Remo con barra", "espalda", "barra", "compuesto", null, null, null),
        ),
    )

    private fun outbox() = db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { c ->
        buildList { while (c.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), c.getString(0))) }
    }

    private fun child(key: String, exerciseId: String, name: String, sets: String = "", repsMin: String = "", repsMax: String = "", rest: String = "", notes: String = "") =
        RoutineExerciseInput(key, exerciseId, name, sets, repsMin, repsMax, rest, notes)

    private suspend fun createPush(): com.chrisgoac.gymlog.domain.routine.Routine {
        seedCatalog()
        return repository.create(
            RoutineInput(
                "Push", "Pecho y hombro",
                listOf(
                    child("c-press", "seed-press", "Press de banca", sets = "4", repsMin = "6", repsMax = "10", rest = "120", notes = "pausa"),
                    child("c-curl", "seed-curl", "Curl con barra Z", sets = "3"),
                ),
            ),
        )
    }

    @Test fun `create writes routine and ordered exercises in one outbox row`() = runTest {
        val routine = createPush()

        assertEquals("Push", routine.name)
        assertEquals(listOf("c-press", "c-curl"), routine.exercises.map { it.id })
        assertEquals(listOf(0, 1), routine.exercises.map { it.position })
        val press = routine.exercises.first()
        assertEquals("Press de banca", press.exerciseName)
        assertEquals(4, press.sets)
        assertEquals(6, press.repsMin)
        assertEquals(10, press.repsMax)
        assertEquals(120, press.restSeconds)
        assertEquals("pausa", press.notes)

        val rows = outbox()
        assertEquals(1, rows.size)
        val ops = rows.single().operations
        assertEquals(3, ops.size)
        val routineOp = ops.filterIsInstance<RoutineOperation>().single()
        assertEquals(0, routineOp.record.orden)
        assertEquals(false, routineOp.record.archivada)
        val wire = ops.filterIsInstance<RoutineExerciseOperation>().first { it.record.id.value == "c-press" }.record
        assertEquals(10, wire.repsObjetivo) // the range max travels in repsObjetivo
        assertEquals(6, wire.repsObjetivoMin)
        assertEquals(routineOp.record.id.value, wire.routineId)
    }

    @Test fun `new routines are appended at the end`() = runTest {
        repository.create(RoutineInput("A"))
        repository.create(RoutineInput("B"))
        repository.create(RoutineInput("C"))
        assertEquals(listOf("A", "B", "C"), repository.observeRoutines().first().map { it.name })
    }

    @Test fun `invalid input writes nothing`() = runTest {
        try {
            repository.create(RoutineInput("Push", exercises = listOf(child("k", "seed-press", "x", repsMin = "12", repsMax = "8"))))
            fail("expected validation failure")
        } catch (e: RoutineValidationException) {
            assertEquals(RoutineError.RANGE_INVERTED, e.issues.single { it.field == RoutineField.REPS_MIN }.error)
        }
        assertTrue(outbox().isEmpty())
        assertTrue(repository.observeRoutines().first().isEmpty())
    }

    @Test fun `reordering exercises rewrites orden on the same row ids in one mutation`() = runTest {
        val routine = createPush()
        now = 2_000
        val input = RoutineInput.from(routine)
        val reordered = input.copy(exercises = input.exercises.reversed())

        val updated = repository.update(routine.id, reordered)

        assertEquals(listOf("c-curl", "c-press"), updated.exercises.map { it.id })
        assertEquals(listOf(0, 1), updated.exercises.map { it.position })
        val rows = outbox()
        assertEquals(2, rows.size)
        val ops = rows.last().operations
        assertEquals(2, ops.size) // only the two children changed; routine row untouched
        assertTrue(ops.all { it is RoutineExerciseOperation })
        assertEquals(setOf("c-press", "c-curl"), ops.map { (it as RoutineExerciseOperation).record.id.value }.toSet())
        assertTrue(ops.all { (it as RoutineExerciseOperation).record.deletedAt == null })
    }

    @Test fun `saving without changes writes nothing`() = runTest {
        val routine = createPush()
        now = 2_000
        repository.update(routine.id, RoutineInput.from(routine))
        assertEquals(1, outbox().size)
    }

    @Test fun `removing an exercise tombstones that child and compacts the rest`() = runTest {
        val routine = createPush()
        now = 2_000
        val input = RoutineInput.from(routine)
        val updated = repository.update(routine.id, input.copy(exercises = listOf(input.exercises[1])))

        assertEquals(listOf("c-curl"), updated.exercises.map { it.id })
        assertEquals(listOf(0), updated.exercises.map { it.position })
        val ops = outbox().last().operations.filterIsInstance<RoutineExerciseOperation>().associateBy { it.record.id.value }
        assertEquals(2_000L, ops.getValue("c-press").record.deletedAt)
        assertNull(ops.getValue("c-curl").record.deletedAt)
        assertEquals(0, ops.getValue("c-curl").record.orden)
        // the tombstone is kept locally for sync
        assertNotNull(db.routineExerciseDao().findById("c-press")!!.deletedAt)
    }

    @Test fun `editing targets and adding a new exercise`() = runTest {
        val routine = createPush()
        seedCatalog()
        now = 2_000
        val input = RoutineInput.from(routine)
        val edited = input.exercises.map { if (it.key == "c-curl") it.copy(sets = "5", repsMax = "15", restSeconds = "60") else it } +
            child("c-remo", "seed-remo", "Remo con barra", sets = "3")
        val updated = repository.update(routine.id, input.copy(name = "Push A", exercises = edited))

        assertEquals("Push A", updated.name)
        assertEquals(listOf("c-press", "c-curl", "c-remo"), updated.exercises.map { it.id })
        assertEquals(5, updated.exercises[1].sets)
        assertEquals(15, updated.exercises[1].repsMax)
        assertEquals(60, updated.exercises[1].restSeconds)
        val ops = outbox().last().operations
        assertEquals(setOf("Push A"), ops.filterIsInstance<RoutineOperation>().map { it.record.nombre }.toSet())
        assertEquals(setOf("c-curl", "c-remo"), ops.filterIsInstance<RoutineExerciseOperation>().map { it.record.id.value }.toSet())
    }

    @Test fun `reusing a deleted or foreign exercise row id is rejected`() = runTest {
        val routine = createPush()
        val other = repository.create(RoutineInput("Other", exercises = listOf(child("o-1", "seed-press", "Press de banca"))))
        now = 2_000
        val input = RoutineInput.from(routine)
        try {
            repository.update(routine.id, input.copy(exercises = input.exercises + child("o-1", "seed-press", "Press de banca")))
            fail("expected conflict")
        } catch (e: RoutineExerciseConflictException) {
            assertEquals("o-1", e.key)
        }
        assertEquals(listOf("o-1"), repository.observeRoutine(other.id).first()!!.exercises.map { it.id })
        assertEquals(2, outbox().size) // nothing from the rejected update
    }

    @Test fun `archive hides from rotation but keeps children and restore reverses it`() = runTest {
        val routine = createPush()
        now = 2_000
        repository.setArchived(routine.id, true)
        val archived = repository.observeRoutines().first().single()
        assertTrue(archived.archived)
        assertEquals(2, archived.exerciseCount)
        assertNull(repository.observeNextRoutineId().first())
        now = 3_000
        repository.setArchived(routine.id, false)
        assertEquals(routine.id, repository.observeNextRoutineId().first())
        assertEquals(3, outbox().size)
        repository.setArchived(routine.id, false) // already live: no write
        assertEquals(3, outbox().size)
    }

    @Test fun `delete tombstones the routine and its children in one mutation`() = runTest {
        val routine = createPush()
        now = 2_000
        repository.delete(routine.id)

        assertTrue(repository.observeRoutines().first().isEmpty())
        assertNull(repository.observeRoutine(routine.id).first())
        val rows = outbox()
        assertEquals(2, rows.size)
        val ops = rows.last().operations
        assertEquals(3, ops.size)
        assertTrue(ops.all { op ->
            when (op) {
                is RoutineOperation -> op.record.deletedAt == 2_000L
                is RoutineExerciseOperation -> op.record.deletedAt == 2_000L
                else -> false
            }
        })
        try {
            repository.delete(routine.id)
            fail("expected not found")
        } catch (_: RoutineNotFoundException) {
        }
    }

    @Test fun `reorder compacts positions and writes only changed rows`() = runTest {
        val a = repository.create(RoutineInput("A"))
        val b = repository.create(RoutineInput("B"))
        val c = repository.create(RoutineInput("C"))
        now = 2_000
        repository.reorder(listOf(c.id, a.id))

        assertEquals(listOf("C", "A", "B"), repository.observeRoutines().first().map { it.name })
        val ops = outbox().last().operations.filterIsInstance<RoutineOperation>().associateBy { it.record.id.value }
        assertEquals(mapOf(c.id to 0, a.id to 1, b.id to 2), ops.mapValues { it.value.record.orden })

        now = 3_000
        repository.reorder(listOf(c.id, a.id, b.id)) // same order: no-op
        assertEquals(4, outbox().size)
    }

    @Test fun `reorder ignores unknown and deleted ids and heals legacy gaps`() = runTest {
        val a = repository.create(RoutineInput("A"))
        val b = repository.create(RoutineInput("B"))
        now = 2_000
        repository.delete(b.id)
        now = 3_000
        repository.reorder(listOf("nope", b.id, a.id))
        assertEquals(0, db.routineDao().findById(a.id)!!.orden)
    }

    @Test fun `next routine follows the last routine session and cycles`() = runTest {
        seedCatalog()
        val a = repository.create(RoutineInput("A"))
        val b = repository.create(RoutineInput("B"))
        val c = repository.create(RoutineInput("C"))
        assertEquals(a.id, repository.observeNextRoutineId().first()) // no session yet

        fun session(id: String, routineId: String?, fecha: Long) = WorkoutSessionEntity(id, 1, null, null, routineId, null, fecha, 3600, null, null)
        db.workoutSessionDao().upsert(session("s1", a.id, 100))
        assertEquals(b.id, repository.observeNextRoutineId().first())
        db.workoutSessionDao().upsert(session("s2", c.id, 200))
        assertEquals(a.id, repository.observeNextRoutineId().first()) // wraps
        db.workoutSessionDao().upsert(session("s3", null, 300)) // free workout does not count
        assertEquals(a.id, repository.observeNextRoutineId().first())
        db.workoutSessionDao().upsert(session("s4", "deleted-routine", 400))
        assertEquals(a.id, repository.observeNextRoutineId().first()) // unknown last -> first
    }

    @Test fun `missing exercise shows a null name instead of failing`() = runTest {
        val routine = repository.create(RoutineInput("A", exercises = listOf(child("k1", "custom-gone", "x"))))
        assertNull(routine.exercises.single().exerciseName)
        assertEquals(emptyList<String>(), repository.observeRoutines().first().single().exercisePreview)
    }
}
