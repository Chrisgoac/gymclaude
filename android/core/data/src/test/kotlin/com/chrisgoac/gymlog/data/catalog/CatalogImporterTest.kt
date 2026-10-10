package com.chrisgoac.gymlog.data.catalog

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.entity.ExerciseEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatalogImporterTest {
    private lateinit var db: GymLogDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun exercise(id: String, nombre: String = id, video: String? = null) =
        CatalogExercise(id, nombre, "pecho", "barra", "compuesto", videoUrl = video)

    @Test
    fun `first import writes every row and records the version`() = runTest {
        val asset = CatalogAsset(1, listOf(exercise("seed-a", video = "https://example.com/v"), exercise("seed-b")))

        assertTrue(CatalogImporter.importIfNeeded(db, asset))

        val rows = db.catalogDao().getAll()
        assertEquals(listOf("seed-a", "seed-b"), rows.map { it.id })
        assertEquals("https://example.com/v", rows[0].videoUrl)
        assertNull(rows[1].videoUrl)
        assertEquals(1, db.catalogDao().importedVersion())
    }

    @Test
    fun `second import of the same version is a no-op`() = runTest {
        val asset = CatalogAsset(1, listOf(exercise("seed-a")))
        CatalogImporter.importIfNeeded(db, asset)
        db.catalogDao().upsertAll(listOf(db.catalogDao().findById("seed-a")!!.copy(nombre = "locally changed")))

        assertFalse(CatalogImporter.importIfNeeded(db, asset))

        assertEquals("locally changed", db.catalogDao().findById("seed-a")!!.nombre)
        assertEquals(1, db.catalogDao().getAll().size)
    }

    @Test
    fun `a higher version upserts changed and new rows`() = runTest {
        CatalogImporter.importIfNeeded(db, CatalogAsset(1, listOf(exercise("seed-a", "Old"))))

        assertTrue(CatalogImporter.importIfNeeded(db, CatalogAsset(2, listOf(exercise("seed-a", "New"), exercise("seed-b")))))

        assertEquals(listOf("New", "seed-b"), db.catalogDao().getAll().map { it.nombre })
        assertEquals(2, db.catalogDao().importedVersion())
        // An older asset never downgrades.
        assertFalse(CatalogImporter.importIfNeeded(db, CatalogAsset(1, listOf(exercise("seed-a", "Old")))))
        assertEquals("New", db.catalogDao().findById("seed-a")!!.nombre)
    }

    @Test
    fun `custom synced exercises and the outbox are never touched`() = runTest {
        val custom = ExerciseEntity(
            id = "custom-1", updatedAt = 5, deletedAt = null, nombre = "Mine", grupoMuscular = "pecho",
            equipamiento = "barra", tipo = "compuesto", videoUrl = null, notas = null, descripcion = null,
            execution = null, incrementoKg = null, esPersonalizado = true, serverVersion = 3,
        )
        db.exerciseDao().upsert(custom)

        CatalogImporter.importIfNeeded(db, CatalogAsset(1, listOf(exercise("seed-a"), exercise("custom-1", "Catalog clash"))))

        assertEquals(listOf(custom), db.exerciseDao().getAll())
        assertTrue(db.syncDao().allOutbox().isEmpty())
    }

    @Test
    fun `shipped asset parses and has unique seed ids and names`() {
        val asset = CatalogAsset.read(File(checkNotNull(System.getProperty("gymlog.catalogAsset"))).inputStream())
        assertEquals(1, asset.version)
        assertTrue(asset.exercises.size > 100)
        assertEquals(asset.exercises.size, asset.exercises.map { it.id }.toSet().size)
        assertEquals(asset.exercises.size, asset.exercises.map { it.nombre.lowercase() }.toSet().size)
        assertTrue(asset.exercises.all { it.id.startsWith("seed-") })
    }
}
