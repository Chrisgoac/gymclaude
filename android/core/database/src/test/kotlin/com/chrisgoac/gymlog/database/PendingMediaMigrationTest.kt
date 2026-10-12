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
class PendingMediaMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymLogDatabase::class.java)

    @Test
    fun `migration 2 to 3 adds the pending media queue and keeps photos`() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO exercise_photos (id, updatedAt, deletedAt, exerciseId, url, `key`, serverVersion) " +
                    "VALUES ('p1', 5, NULL, 'e1', '/api/media/read/p1', 'users/h/exercise/x', 7)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *GYMLOG_MIGRATIONS)

        db.query("SELECT `key`, serverVersion FROM exercise_photos WHERE id = 'p1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("users/h/exercise/x", it.getString(0))
            assertEquals(7, it.getInt(1))
        }
        db.query("SELECT COUNT(*) FROM pending_media").use {
            it.moveToFirst()
            assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "pending-media-migration-test"
    }
}
