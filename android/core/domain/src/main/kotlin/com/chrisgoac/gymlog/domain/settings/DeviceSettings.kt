package com.chrisgoac.gymlog.domain.settings

import kotlinx.coroutines.flow.Flow

enum class ThemeMode(val wire: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromWire(value: String?): ThemeMode = entries.firstOrNull { it.wire == value } ?: SYSTEM
    }
}

/** What the user answered about the (local, rest-timer) notification prompt; the system permission is separate. */
enum class NotificationChoice(val wire: String) {
    UNDECIDED("undecided"), ALLOWED("allowed"), DECLINED("declined");

    companion object {
        fun fromWire(value: String?): NotificationChoice = entries.firstOrNull { it.wire == value } ?: UNDECIDED
    }
}

/**
 * Settings that never leave the device (the PWA has no synced key for any of them, and nothing
 * here ever reaches the outbox). Theme and the notification answer belong to the device; the rest
 * belong to the signed-in account on this device and are deleted with its local data. Weights are
 * always shown in kilograms (PWA parity): there is no unit setting, and a `weightUnit` value stored
 * by an earlier build is ignored.
 */
data class DeviceSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val notifications: NotificationChoice = NotificationChoice.UNDECIDED,
    /** Gym preselected on the Train tab; null = none. May name a gym that no longer exists (readers validate). */
    val defaultGymId: String? = null,
    val onboardingDone: Boolean = false,
    /** Last local filters (e.g. `progress.period`, `progress.gym`, `history.gym`); absent key = never chosen. */
    val filters: Map<String, String> = emptyMap(),
)

interface DeviceSettingsRepository {
    fun observe(): Flow<DeviceSettings>
    suspend fun setTheme(theme: ThemeMode)
    suspend fun setNotificationChoice(choice: NotificationChoice)
    suspend fun setDefaultGym(gymId: String?)
    suspend fun setOnboardingDone(done: Boolean)

    /** Stores the last filter value for [key]; null forgets it. */
    suspend fun setFilter(key: String, value: String?)
}
