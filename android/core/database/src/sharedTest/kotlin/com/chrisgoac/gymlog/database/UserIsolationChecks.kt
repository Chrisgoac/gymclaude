package com.chrisgoac.gymlog.database

import android.content.Context
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

/** Per-user database isolation, shared by the Robolectric and the instrumented test. */
class UserIsolationChecks(private val context: Context) {
    private val userA = "user_isolation_a_" + System.nanoTime()
    private val userB = "user_isolation_b_" + System.nanoTime()
    private val manager = UserDatabaseManagerImpl(context)

    private fun runner(db: GymLogDatabase) = RoomLocalMutationRunner(db, DeviceId("device-isolation"))

    fun cleanUp() = runBlocking {
        manager.closeAndDelete(userA)
        manager.closeAndDelete(userB)
    }

    fun twoUsersNeverSeeEachOthersData() = runBlocking {
        val dbA = manager.open(userA)
        runner(dbA).run(LocalMutation { put(SampleRecords.gym("gy-a")) })
        assertEquals(userA, manager.current.value?.userId)
        assertTrue(dbA.isOpen)

        val dbB = manager.open(userB)
        assertFalse("switching user closes the previous database first", dbA.isOpen)
        assertEquals(userB, manager.current.value?.userId)
        assertNull(RecordStore(dbB).find(SyncTable.GYMS, RecordId("gy-a")))
        assertTrue(dbB.syncDao().allOutbox().isEmpty())
        runner(dbB).run(LocalMutation { put(SampleRecords.gym("gy-b")) })

        val reopenedA = manager.open(userA)
        assertFalse(dbB.isOpen)
        assertEquals(SampleRecords.gym("gy-a"), RecordStore(reopenedA).find(SyncTable.GYMS, RecordId("gy-a"))?.record)
        assertNull(RecordStore(reopenedA).find(SyncTable.GYMS, RecordId("gy-b")))
        assertEquals(1, reopenedA.syncDao().allOutbox().size)
    }

    fun openingTheSameUserTwiceReturnsTheOpenDatabase() = runBlocking {
        val first = manager.open(userA)
        first.syncDao().allOutbox() // Room opens the file lazily, on first use.
        assertSame(first, manager.open(userA))
        assertTrue("reopening the active user must not close it", first.isOpen)
    }

    fun fileNamesAreHashedAndDistinct() {
        val nameA = UserDatabaseManagerImpl.databaseFileName(userA)
        val nameB = UserDatabaseManagerImpl.databaseFileName(userB)
        val pattern = Regex("^gymlog-[0-9a-f]{32}\\.db$")
        assertTrue(nameA, pattern.matches(nameA))
        assertTrue(nameB, pattern.matches(nameB))
        assertNotEquals(nameA, nameB)
        assertFalse(nameA.contains(userA))
        // printf 'user_isolation_vector' | sha256sum -> 34a992fe299fad4238ff7f01c9563b16...
        assertEquals("gymlog-34a992fe299fad4238ff7f01c9563b16.db", UserDatabaseManagerImpl.databaseFileName("user_isolation_vector"))
    }

    fun closeAndDeleteRemovesOnlyThatUsersFiles() = runBlocking {
        runner(manager.open(userB)).run(LocalMutation { put(SampleRecords.gym("gy-b")) })
        val dbA = manager.open(userA)
        runner(dbA).run(LocalMutation { put(SampleRecords.gym("gy-a")) })
        val filesA = manager.databaseFiles(userA)
        assertTrue(filesA.first().exists())

        manager.closeAndDelete(userA)

        assertFalse(dbA.isOpen)
        assertNull(manager.current.value)
        filesA.forEach { assertFalse("${it.name} must be deleted", it.exists()) }
        assertTrue("other user's database is untouched", manager.databaseFiles(userB).first().exists())
        val freshA = manager.open(userA)
        assertNull(RecordStore(freshA).find(SyncTable.GYMS, RecordId("gy-a")))
        val dbB = manager.open(userB)
        assertEquals(SampleRecords.gym("gy-b"), RecordStore(dbB).find(SyncTable.GYMS, RecordId("gy-b"))?.record)
    }

    fun closeKeepsTheFile() = runBlocking {
        val dbA = manager.open(userA)
        runner(dbA).run(LocalMutation { put(SampleRecords.gym("gy-a")) })
        manager.close()
        assertFalse(dbA.isOpen)
        assertNull(manager.current.value)
        assertTrue(manager.databaseFiles(userA).first().exists())
        assertEquals(SampleRecords.gym("gy-a"), RecordStore(manager.open(userA)).find(SyncTable.GYMS, RecordId("gy-a"))?.record)
    }
}
