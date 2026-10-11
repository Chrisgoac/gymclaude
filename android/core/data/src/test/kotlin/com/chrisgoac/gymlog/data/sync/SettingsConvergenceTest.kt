package com.chrisgoac.gymlog.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.settings.OfflineGymRepository
import com.chrisgoac.gymlog.data.settings.OfflineSettingsRepository
import com.chrisgoac.gymlog.database.GYMLOG_MIGRATIONS
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.LocalMutation
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.model.DeviceId
import com.chrisgoac.gymlog.model.RecordId
import com.chrisgoac.gymlog.model.SyncTable
import com.chrisgoac.gymlog.model.UserSettingRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan 4 Task 6 gate: settings and gyms written on two devices converge through [FakeSyncServer];
 * a key this version does not know (written by another client) rides along unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsConvergenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var now = 1_800_000_000_000L
    private var ids = 0
    private val devices = mutableListOf<Device>()

    @After fun tearDown() {
        devices.forEach { it.db.close(); context.deleteDatabase(it.file) }
    }

    private class Manager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    private inner class Device(val file: String, deviceId: String, link: FakeSyncServer.Link) {
        val db: GymLogDatabase = Room.databaseBuilder(context, GymLogDatabase::class.java, file)
            .addMigrations(*GYMLOG_MIGRATIONS).allowMainThreadQueries().build()
        private val manager = Manager(db)
        val runner = RoomLocalMutationRunner(db, DeviceId(deviceId), clock = { now })
        val settings = OfflineSettingsRepository(manager) { runner }
        val gyms = OfflineGymRepository(manager, { runner }, newId = { "00000000-0000-4000-8000-%012d".format(++ids) })
        private val locks = SyncLocks()
        val sync = SyncRepositoryImpl(manager, link, BootstrapRepository(link, locks, clock = { now }), locks, clock = { now })
        init { link.online = true }
    }

    private fun device(name: String, id: String, server: FakeSyncServer) = Device(name, id, server.Link("u1")).also { devices += it }

    private suspend fun Device.sync() = assertTrue(sync.syncOnce("u1") is SyncResult.Success)

    @Test fun `settings and gyms converge, last write wins per key and unknown keys survive`() = runTest {
        val server = FakeSyncServer(now = { now })
        val a = device("settings-a.db", "device-a", server)
        val b = device("settings-b.db", "device-b", server)

        // A newer client wrote a key this version has never heard of.
        a.runner.run(LocalMutation { put(UserSettingRecord(RecordId("futureSetting"), nextTimestamp(SyncTable.USER_SETTINGS, RecordId("futureSetting")), null, """{"v":9}""")) })
        now += 1000
        a.settings.setWeeklyTarget(5)
        now += 1000
        a.settings.setIncrement(Equipment.BARRA, 5.0)
        val gym = a.gyms.create("Box")
        a.sync()
        b.sync()
        assertEquals(5, b.settings.observeTraining().first().weeklyTarget)
        assertEquals("Box", b.gyms.observeGyms().first().single().name)

        // Concurrent edits of the same key: the later write wins on both devices.
        now += 60_000
        a.settings.setWeeklyTarget(2)
        now += 1000
        b.settings.setWeeklyTarget(6)
        b.settings.setIncrement(Equipment.MANCUERNA, 1.0)
        b.gyms.rename(gym.id, "Box Centro")
        now += 1000
        b.sync()
        a.sync()
        b.sync()

        for (d in listOf(a, b)) {
            val training = d.settings.observeTraining().first()
            assertEquals(6, training.weeklyTarget)
            assertEquals(5.0, training.progression.increments.getValue(Equipment.BARRA), 0.0)
            assertEquals(1.0, training.progression.increments.getValue(Equipment.MANCUERNA), 0.0)
            assertEquals("Box Centro", d.gyms.observeGyms().first().single().name)
            assertEquals("""{"v":9}""", d.db.userSettingDao().findById("futureSetting")!!.valor)
        }
        assertEquals(server.snapshot().keys.filter { it.first == SyncTable.USER_SETTINGS }.toSet(), a.db.userSettingDao().getAll().map { SyncTable.USER_SETTINGS to it.id }.toSet())
    }
}
