package com.chrisgoac.gymlog.data.exercise

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.database.entity.CatalogExerciseEntity
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.ExerciseField
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotEditableException
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotFoundException
import com.chrisgoac.gymlog.domain.exercise.ExerciseValidationException
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.ExerciseOperation
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.ContractJson
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
class OfflineExerciseRepositoryTest {
    private lateinit var db: GymLogDatabase
    private lateinit var repository: OfflineExerciseRepository
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
        repository = OfflineExerciseRepository(
            FakeManager(db),
            runnerFor = { RoomLocalMutationRunner(it, DeviceId("test-device"), clock = { now }) },
            newId = { "00000000-0000-4000-8000-%012d".format(++ids) },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedCatalog() = db.catalogDao().upsertAll(
        listOf(
            CatalogExerciseEntity("seed-press", "Press de banca", "pecho", "barra", "compuesto", "https://v/1", "desc", "exec"),
            CatalogExerciseEntity("seed-curl", "Curl con barra Z", "biceps", "barra", "aislamiento", null, null, null),
            CatalogExerciseEntity("seed-sentadilla", "Sentadilla búlgara", "cuadriceps", "mancuerna", "compuesto", null, null, null),
        ),
    )

    private fun outbox() = db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { c ->
        buildList { while (c.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), c.getString(0))) }
    }

    @Test fun `merges catalog and custom sorted by name with accent-insensitive search`() = runTest {
        seedCatalog()
        now = 2_000
        repository.createCustom(ExerciseInput(name = "Zancada", muscleGroup = MuscleGroup.CUADRICEPS, equipment = Equipment.MANCUERNA))

        val all = repository.observeExercises().first()
        assertEquals(listOf("Curl con barra Z", "Press de banca", "Sentadilla búlgara", "Zancada"), all.map { it.name })
        assertEquals(listOf(false, false, false, true), all.map { it.isCustom })

        assertEquals(listOf("Sentadilla búlgara"), repository.observeExercises(ExerciseFilter(query = "BULGARA")).first().map { it.name })
        assertEquals(
            listOf("Sentadilla búlgara", "Zancada"),
            repository.observeExercises(ExerciseFilter(muscleGroup = MuscleGroup.CUADRICEPS)).first().map { it.name },
        )
        assertEquals(
            listOf("Curl con barra Z", "Press de banca"),
            repository.observeExercises(ExerciseFilter(equipment = Equipment.BARRA)).first().map { it.name },
        )
    }

    @Test fun `list heals when the catalog lands after the first emission`() = runTest {
        assertTrue(repository.observeExercises().first().isEmpty())
        seedCatalog()
        assertEquals(3, repository.observeExercises().first().size)
    }

    @Test fun `creating a custom exercise writes one outbox row and is readable offline`() = runTest {
        val created = repository.createCustom(
            ExerciseInput(name = " Remo Pendlay ", muscleGroup = MuscleGroup.ESPALDA, equipment = Equipment.BARRA, incrementKg = "2,5", videoUrl = "https://youtu.be/x"),
        )

        assertTrue(created.isCustom)
        assertEquals("Remo Pendlay", created.name)
        assertEquals(2.5, created.incrementKg!!, 0.0)
        val rows = outbox()
        assertEquals(1, rows.size)
        val op = rows.single().operations.single() as ExerciseOperation
        assertEquals(created.id, op.record.id.value)
        assertTrue(op.record.esPersonalizado)
        assertNull(op.record.deletedAt)
        assertEquals(1_000L, op.record.updatedAt)
        assertNull(op.baseServerVersion)
        assertEquals(created, repository.observeExercise(created.id).first())
    }

    @Test fun `update stamps a strictly newer timestamp and writes one more outbox row`() = runTest {
        val created = repository.createCustom(ExerciseInput(name = "Remo"))
        // The device clock did not advance: nextTimestamp must still move past the stored version.
        val updated = repository.updateCustom(created.id, ExerciseInput(name = "Remo con barra", notes = "codos pegados"))

        assertEquals("Remo con barra", updated.name)
        assertEquals("codos pegados", updated.notes)
        val rows = outbox()
        assertEquals(2, rows.size)
        val first = (rows[0].operations.single() as ExerciseOperation).record
        val second = (rows[1].operations.single() as ExerciseOperation).record
        assertTrue(second.updatedAt > first.updatedAt)
    }

    @Test fun `delete tombstones the row and hides it`() = runTest {
        val created = repository.createCustom(ExerciseInput(name = "Remo"))
        repository.deleteCustom(created.id)

        assertNull(repository.observeExercise(created.id).first())
        assertTrue(repository.observeExercises().first().isEmpty())
        val tombstone = db.exerciseDao().findById(created.id)
        assertNotNull(tombstone!!.deletedAt) // never a physical delete
        val op = outbox().last().operations.single() as ExerciseOperation
        assertEquals(tombstone.deletedAt, op.record.deletedAt)
    }

    @Test fun `catalog exercises cannot be edited or deleted and nothing is written`() = runTest {
        seedCatalog()
        try {
            repository.updateCustom("seed-press", ExerciseInput(name = "Hack"))
            fail("expected ExerciseNotEditableException")
        } catch (expected: ExerciseNotEditableException) {
            assertEquals("seed-press", expected.id)
        }
        try {
            repository.deleteCustom("seed-press")
            fail("expected ExerciseNotEditableException")
        } catch (_: ExerciseNotEditableException) {
        }
        assertTrue(outbox().isEmpty())
        assertEquals("Press de banca", db.catalogDao().findById("seed-press")!!.nombre)
    }

    @Test fun `invalid input and unknown ids are rejected before writing`() = runTest {
        try {
            repository.createCustom(ExerciseInput(name = "", incrementKg = "0"))
            fail("expected ExerciseValidationException")
        } catch (e: ExerciseValidationException) {
            assertEquals(setOf(ExerciseField.NAME, ExerciseField.INCREMENT_KG), e.errors.keys)
        }
        try {
            repository.updateCustom("00000000-0000-4000-8000-0000000000ff", ExerciseInput(name = "x"))
            fail("expected ExerciseNotFoundException")
        } catch (_: ExerciseNotFoundException) {
        }
        assertTrue(outbox().isEmpty())
    }

    @Test fun `synced rows that are not custom are not listed`() = runTest {
        db.exerciseDao().upsert(ExerciseEntity("legacy-1", 1, null, "Legacy", "pecho", "barra", "compuesto", null, null, null, null, null, false, 1))
        assertTrue(repository.observeExercises().first().isEmpty())
    }
}
