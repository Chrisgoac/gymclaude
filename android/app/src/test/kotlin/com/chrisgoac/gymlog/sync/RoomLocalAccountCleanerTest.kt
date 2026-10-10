package com.chrisgoac.gymlog.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.OutboxMutationEntity
import com.chrisgoac.gymlog.database.entity.OutboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomLocalAccountCleanerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databases = UserDatabaseManagerImpl(context) { it.allowMainThreadQueries() }
    private val cleaner = RoomLocalAccountCleaner(context, databases)

    @After fun tearDown() = runBlocking { databases.close() }

    private fun row(id: String, status: OutboxStatus, sequence: Long) = OutboxMutationEntity(id, "dev", "{}", 1L, 0, null, sequence, status)

    @Test fun countsPendingAndRejectedChangesAndDeletesEverythingOfThatUser() = runBlocking {
        val dao = databases.open("user_1").syncDao()
        dao.insertOutbox(row("a", OutboxStatus.PENDING, 1))
        dao.insertOutbox(row("b", OutboxStatus.QUARANTINED, 2))
        val privateDir = File(context.filesDir, "users/${UserDatabaseManagerImpl.userHash("user_1")}").apply { mkdirs() }
        File(privateDir, "photo.tmp").writeText("x")

        assertEquals(2, cleaner.unsyncedChangeCount("user_1"))

        cleaner.deleteLocalData("user_1")

        assertTrue(databases.databaseFiles("user_1").none { it.exists() })
        assertFalse(privateDir.exists())
        assertEquals(null, databases.current.value)
    }

    @Test fun anEmptyOutboxCountsZero() = runBlocking {
        databases.open("user_2")
        assertEquals(0, cleaner.unsyncedChangeCount("user_2"))
    }
}
