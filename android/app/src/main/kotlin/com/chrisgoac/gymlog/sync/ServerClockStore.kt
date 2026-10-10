package com.chrisgoac.gymlog.sync

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.edit
import com.chrisgoac.gymlog.network.ClockOffsetSample

/**
 * Persists the last server clock offset per installation (not per account: it describes this
 * device's clock), with the wall time, elapsed-realtime anchor and boot it was measured in, so the
 * next process can seed [com.chrisgoac.gymlog.network.ServerClock] before any response arrives.
 */
class ServerClockStore(
    private val prefs: SharedPreferences,
    private val wallNow: () -> Long = System::currentTimeMillis,
    private val elapsedNow: () -> Long = SystemClock::elapsedRealtime,
    private val bootCount: () -> Int? = { null },
) {
    constructor(context: Context) : this(
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        bootCount = { readBootCount(context) },
    )

    /** The offset to start from: 0 when nothing was measured yet. */
    fun seedOffset(): Long {
        val sample = load() ?: return 0L
        return sample.seedOffset(wallNow(), elapsedNow(), bootCount())
    }

    fun save(offsetMillis: Long) {
        prefs.edit {
            putLong(KEY_OFFSET, offsetMillis)
            putLong(KEY_WALL, wallNow())
            putLong(KEY_ELAPSED, elapsedNow())
            val boot = bootCount()
            if (boot != null) putInt(KEY_BOOT, boot) else remove(KEY_BOOT)
        }
    }

    private fun load(): ClockOffsetSample? {
        if (!prefs.contains(KEY_OFFSET)) return null
        return ClockOffsetSample(
            offsetMillis = prefs.getLong(KEY_OFFSET, 0L),
            measuredAtWallMillis = prefs.getLong(KEY_WALL, 0L),
            measuredAtElapsedMillis = prefs.getLong(KEY_ELAPSED, 0L),
            bootCount = if (prefs.contains(KEY_BOOT)) prefs.getInt(KEY_BOOT, 0) else null,
        )
    }

    private companion object {
        const val PREFS = "gymlog.clock"
        const val KEY_OFFSET = "offsetMillis"
        const val KEY_WALL = "measuredAtWallMillis"
        const val KEY_ELAPSED = "measuredAtElapsedMillis"
        const val KEY_BOOT = "bootCount"

        fun readBootCount(context: Context): Int? =
            runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrNull()
    }
}
