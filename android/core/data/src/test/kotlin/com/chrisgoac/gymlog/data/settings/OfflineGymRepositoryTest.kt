package com.chrisgoac.gymlog.data.settings

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.settings.GymNameProblem
import com.chrisgoac.gymlog.domain.settings.GymNotFoundException
import com.chrisgoac.gymlog.domain.settings.InvalidGymNameException
import com.chrisgoac.gymlog.model.ContractJson
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.GymOperation
import com.chrisgoac.gymlog.model.PushMutation
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.WorkoutSessionRecord
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineGymRepositoryTest {
    private var now = 1_800_000_000_000L
    private var ids = 0
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, GymLogDatabase::class.java).allowMainThreadQueries().build()

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private val runner = RoomLocalMutationRunner(db, DeviceId("test-device"), clock = { now })
    private val gyms = OfflineGymRepository(FakeManager(db), { runner }, newId = { "00000000-0000-4000-8000-%012d".format(++ids) })

    @After fun tearDown() = db.close()

    private fun outbox(): List<PushMutation> =
        db.query("SELECT payloadJson FROM outbox_mutations ORDER BY sequence", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContractJson.decodeFromString(PushMutation.serializer(), cursor.getString(0))) }
        }

    private suspend fun addSession(id: String, gymId: String?) {
        runner.run(
            LocalMutation {
                put(WorkoutSessionRecord(RecordId(id), nextTimestamp(SyncTable.WORKOUT_SESSIONS, RecordId(id)), null, null, null, gymId, now, 3600, null))
            },
        )
        now += 1000
    }

    @Test fun `create rename archive reorder produce one outbox mutation each with advancing timestamps`() = runTest {
        val a = gyms.create("  Smart Fit ")
        now += 1000
        val b = gyms.create("Casa")
        now += 1000
        gyms.rename(a.id, "SmartFit")
        now += 1000
        gyms.setArchived(b.id, true)
        now += 1000
        gyms.reorder(listOf(b.id, a.id))

        assertEquals(listOf("Casa", "SmartFit"), gyms.observeGyms().first().map { it.name })
        assertEquals(listOf(true, false), gyms.observeGyms().first().map { it.archived })
        val records = outbox().flatMap { it.operations }.map { (it as GymOperation).record }.filter { it.id.value == a.id }
        assertEquals(listOf("Smart Fit", "SmartFit", "SmartFit"), records.map { it.nombre }) // create, rename, reorder (position 1)
        assertTrue(records.zipWithNext().all { (x, y) -> x.updatedAt < y.updatedAt })
        // Re-applying the same state writes nothing.
        val before = outbox().size
        gyms.rename(a.id, "SmartFit")
        gyms.setArchived(b.id, true)
        gyms.reorder(listOf(b.id, a.id))
        assertEquals(before, outbox().size)
    }

    @Test fun `names are validated`() = runTest {
        for ((text, problem) in listOf("   " to GymNameProblem.BLANK, "x".repeat(61) to GymNameProblem.TOO_LONG)) {
            try {
                gyms.create(text); fail("expected $problem")
            } catch (e: InvalidGymNameException) {
                assertEquals(problem, e.problem)
            }
        }
        assertTrue(outbox().isEmpty())
    }

    @Test fun `deleting a gym in use tombstones only the gym and leaves every workout untouched`() = runTest {
        val gym = gyms.create("Box")
        now += 1000
        addSession("s1", gym.id)
        addSession("s2", gym.id)
        addSession("s3", null)
        assertEquals(2, gyms.observeGyms().first().single().sessionCount)

        val sessionsBefore = db.workoutSessionDao().let { dao -> listOf("s1", "s2", "s3").map { dao.findById(it)!! } }
        gyms.delete(gym.id)

        assertTrue(gyms.observeGyms().first().isEmpty())
        val tombstone = db.gymDao().findById(gym.id)!!
        assertNotNull(tombstone.deletedAt)
        assertEquals(tombstone.updatedAt, tombstone.deletedAt)
        assertEquals(sessionsBefore, listOf("s1", "s2", "s3").map { db.workoutSessionDao().findById(it)!! })
        assertEquals(gym.id, db.workoutSessionDao().findById("s1")!!.gymId)
        assertNull(db.workoutSessionDao().findById("s3")!!.gymId)
        // Only the gym got a delete mutation; deleting again is a no-op; editing it is refused.
        val deletes = outbox().flatMap { it.operations }.filter { it.record.deletedAt != null }
        assertEquals(1, deletes.size)
        gyms.delete(gym.id)
        assertEquals(1, outbox().flatMap { it.operations }.count { it.record.deletedAt != null })
        try {
            gyms.rename(gym.id, "Otro"); fail("expected GymNotFoundException")
        } catch (_: GymNotFoundException) {
        }
    }
}
