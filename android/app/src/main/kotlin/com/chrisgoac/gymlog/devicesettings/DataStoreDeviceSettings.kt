package com.chrisgoac.gymlog.devicesettings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.domain.settings.DeviceSettings
import com.chrisgoac.gymlog.domain.settings.DeviceSettingsRepository
import com.chrisgoac.gymlog.domain.settings.NotificationChoice
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.io.File

/**
 * Per-account preference files in `files/users/<userHash>/` (the directory sign-out already
 * deletes). A DataStore must be a singleton per file, hence the cache; [forget] releases one after
 * its directory is deleted so a re-created account starts clean.
 */
class UserScopedPreferenceStores(private val filesDir: File, private val fileName: String = "device_settings.preferences_pb") {
    private class Entry(val scope: CoroutineScope, val store: DataStore<Preferences>)

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun get(userHash: String): DataStore<Preferences> = entries.getOrPut(userHash) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        Entry(scope, PreferenceDataStoreFactory.create(scope = scope) { File(filesDir, "users/$userHash/$fileName") })
    }.store

    @Synchronized
    fun forget(userHash: String) {
        entries.remove(userHash)?.scope?.cancel()
    }
}

/**
 * Settings that never sync: nothing here touches Room or the outbox. [deviceStore] holds what
 * belongs to the device (theme, units, notification answer); [accountStores] what belongs to the
 * signed-in account on this device (default gym, onboarding, last filters). With no account the
 * account part reads defaults and drops writes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreDeviceSettings(
    private val deviceStore: DataStore<Preferences>,
    private val accountStores: UserScopedPreferenceStores,
    private val currentUserHash: StateFlow<String?>,
) : DeviceSettingsRepository {

    override fun observe(): Flow<DeviceSettings> {
        val account = currentUserHash.flatMapLatest { hash -> if (hash == null) flowOf(null) else accountStores.get(hash).data }
        return combine(deviceStore.data, account) { device, acct ->
            DeviceSettings(
                theme = ThemeMode.fromWire(device[THEME]),
                weightUnit = WeightUnit.fromWire(device[UNIT]),
                notifications = NotificationChoice.fromWire(device[NOTIFICATIONS]),
                defaultGymId = acct?.get(DEFAULT_GYM),
                onboardingDone = acct?.get(ONBOARDING) ?: false,
                filters = acct?.asMap().orEmpty().mapNotNull { (key, value) ->
                    val name = key.name
                    if (name.startsWith(FILTER_PREFIX) && value is String) name.removePrefix(FILTER_PREFIX) to value else null
                }.toMap(),
            )
        }.distinctUntilChanged()
    }

    override suspend fun setTheme(theme: ThemeMode) { deviceStore.edit { it[THEME] = theme.wire } }

    override suspend fun setWeightUnit(unit: WeightUnit) { deviceStore.edit { it[UNIT] = unit.wire } }

    override suspend fun setNotificationChoice(choice: NotificationChoice) { deviceStore.edit { it[NOTIFICATIONS] = choice.wire } }

    override suspend fun setDefaultGym(gymId: String?) = editAccount { if (gymId == null) it.remove(DEFAULT_GYM) else it[DEFAULT_GYM] = gymId }

    override suspend fun setOnboardingDone(done: Boolean) = editAccount { it[ONBOARDING] = done }

    override suspend fun setFilter(key: String, value: String?) = editAccount {
        val prefKey = stringPreferencesKey(FILTER_PREFIX + key)
        if (value == null) it.remove(prefKey) else it[prefKey] = value
    }

    private suspend fun editAccount(change: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        val hash = currentUserHash.value ?: return
        accountStores.get(hash).edit(change)
    }

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val UNIT = stringPreferencesKey("weightUnit")
        val NOTIFICATIONS = stringPreferencesKey("notifications")
        val DEFAULT_GYM = stringPreferencesKey("defaultGymId")
        val ONBOARDING = booleanPreferencesKey("onboardingDone")
        const val FILTER_PREFIX = "filter."
    }
}
