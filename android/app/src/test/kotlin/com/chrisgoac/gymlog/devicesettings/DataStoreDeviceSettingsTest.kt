package com.chrisgoac.gymlog.devicesettings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.data.settings.OfflineSettingsRepository
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.RoomLocalMutationRunner
import com.chrisgoac.gymlog.database.UserDatabaseManager
import com.chrisgoac.gymlog.domain.settings.DeviceSettings
import com.chrisgoac.gymlog.domain.settings.NotificationChoice
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import com.chrisgoac.gymlog.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DataStoreDeviceSettingsTest {
    private lateinit var dir: File
    private val scopes = mutableListOf<CoroutineScope>()
    private val user = MutableStateFlow<String?>("hashA")

    @Before fun setUp() { dir = Files.createTempDirectory("device-settings").toFile() }

    @After fun tearDown() { scopes.forEach { it.cancel() }; dir.deleteRecursively() }

    /** A fresh "process": new DataStore instances over the same files. */
    private fun open(): Pair<DataStoreDeviceSettings, UserScopedPreferenceStores> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val device = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "device_settings.preferences_pb") }
        val stores = UserScopedPreferenceStores(dir)
        return DataStoreDeviceSettings(device, stores, user) to stores
    }

    @Test fun `a weight unit stored by an earlier build is ignored`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val legacy = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "device_settings.preferences_pb") }
        legacy.edit { it[stringPreferencesKey("weightUnit")] = "lb" }
        scope.cancel()

        val (settings, _) = open()
        assertEquals(DeviceSettings(), settings.observe().first())
    }

    @Test fun `defaults then values persist across a restart`() = runTest {
        val (first, firstStores) = open()
        assertEquals(DeviceSettings(), first.observe().first())
        first.setTheme(ThemeMode.DARK)
        first.setNotificationChoice(NotificationChoice.DECLINED)
        first.setDefaultGym("g1")
        first.setOnboardingDone(true)
        first.setFilter("progress.period", "90d")
        first.setFilter("history.gym", "g1")
        first.setFilter("history.gym", null)
        scopes.forEach { it.cancel() }
        firstStores.forget("hashA") // process death: the old DataStore instances are gone

        val (second, _) = open()
        assertEquals(
            DeviceSettings(ThemeMode.DARK, NotificationChoice.DECLINED, "g1", true, mapOf("progress.period" to "90d")),
            second.observe().first(),
        )
    }

    @Test fun `theme and units belong to the device, the rest to the signed-in account`() = runTest {
        val (settings, _) = open()
        settings.setTheme(ThemeMode.LIGHT)
        settings.setDefaultGym("g1")
        settings.setOnboardingDone(true)

        user.value = "hashB"
        val other = settings.observe().first()
        assertEquals(ThemeMode.LIGHT, other.theme)
        assertNull(other.defaultGymId)
        assertEquals(false, other.onboardingDone)

        user.value = "hashA"
        assertEquals("g1", settings.observe().first().defaultGymId)

        // Signed out: account values read as defaults and writes are dropped, device values still work.
        user.value = null
        settings.setDefaultGym("zzz")
        settings.setTheme(ThemeMode.DARK)
        assertNull(settings.observe().first().defaultGymId)
        assertEquals(ThemeMode.DARK, settings.observe().first().theme)
        user.value = "hashA"
        assertEquals("g1", settings.observe().first().defaultGymId)
    }

    @Test fun `forgetting an account after its directory is deleted starts it clean`() = runTest {
        val (settings, stores) = open()
        settings.setDefaultGym("g1")
        File(dir, "users/hashA").deleteRecursively()
        stores.forget("hashA")
        assertNull(settings.observe().first().defaultGymId)
    }

    // ---- Device-only settings never reach Room / the outbox ----

    private class FakeManager(db: GymLogDatabase) : UserDatabaseManager {
        override val current: StateFlow<OpenUserDatabase?> = MutableStateFlow(OpenUserDatabase("u1", db))
        override suspend fun open(userId: String) = current.value!!.database
        override suspend fun close() = Unit
        override suspend fun closeAndDelete(userId: String) = Unit
    }

    @Test fun `no device-only setting is ever staged in the outbox or the synced settings table`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), GymLogDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val synced = OfflineSettingsRepository(FakeManager(db)) { RoomLocalMutationRunner(db, DeviceId("d"), clock = { 1_800_000_000_000L }) }
            val (device, _) = open()

            device.setTheme(ThemeMode.DARK)
            device.setNotificationChoice(NotificationChoice.ALLOWED)
            device.setDefaultGym("g1")
            device.setOnboardingDone(true)
            device.setFilter("progress.gym", "g1")
            assertTrue(db.syncDao().allOutbox().isEmpty())
            assertTrue(db.userSettingDao().getAll().isEmpty())

            // Control: a synced setting does reach both.
            synced.setWeeklyTarget(4)
            assertEquals(1, db.syncDao().allOutbox().size)
            assertEquals(listOf("objetivoSemanal"), db.userSettingDao().getAll().map { it.id })
        } finally {
            db.close()
        }
    }
}
