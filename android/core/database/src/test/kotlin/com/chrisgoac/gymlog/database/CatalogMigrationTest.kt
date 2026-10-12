package com.chrisgoac.gymlog.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CatalogMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymLogDatabase::class.java)

    @Test
    fun `migration 1 to 2 adds the catalog tables and keeps existing data`() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO exercises (id, updatedAt, deletedAt, nombre, grupoMuscular, equipamiento, tipo, " +
                    "videoUrl, notas, descripcion, execution, incrementoKg, esPersonalizado, serverVersion) " +
                    "VALUES ('custom-1', 5, NULL, 'Mine', 'pecho', 'barra', 'compuesto', NULL, NULL, NULL, NULL, NULL, 1, 3)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *GYMLOG_MIGRATIONS)

        db.query("SELECT nombre, serverVersion FROM exercises WHERE id = 'custom-1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Mine", it.getString(0))
            assertEquals(3, it.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM catalog_exercises").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM catalog_meta").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test"
    }
}
