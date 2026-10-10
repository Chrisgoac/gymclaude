package com.chrisgoac.gymlog.timer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chrisgoac.gymlog.domain.timer.RestTimerState
import com.chrisgoac.gymlog.domain.timer.RestTimerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import java.io.File

/** One account's timer run in a preferences DataStore. */
class DataStoreRestTimerStore(private val dataStore: DataStore<Preferences>) : RestTimerStore {
    override suspend fun read(): RestTimerState? {
        val p = dataStore.data.first()
        val runId = p[RUN_ID] ?: return null
        return RestTimerState(
            runId = runId,
            sessionId = p[SESSION_ID] ?: return null,
            totalMillis = p[TOTAL] ?: return null,
            endsAtEpochMillis = p[ENDS_AT] ?: return null,
            pausedRemainingMillis = p[PAUSED_REMAINING],
            completionDelivered = p[DELIVERED] ?: false,
        )
    }

    override suspend fun write(state: RestTimerState?) {
        dataStore.edit { p ->
            p.clear()
            if (state != null) {
                p[RUN_ID] = state.runId
                p[SESSION_ID] = state.sessionId
                p[TOTAL] = state.totalMillis
                p[ENDS_AT] = state.endsAtEpochMillis
                state.pausedRemainingMillis?.let { p[PAUSED_REMAINING] = it }
                p[DELIVERED] = state.completionDelivered
            }
        }
    }

    private companion object {
        val RUN_ID = stringPreferencesKey("runId")
        val SESSION_ID = stringPreferencesKey("sessionId")
        val TOTAL = longPreferencesKey("totalMillis")
        val ENDS_AT = longPreferencesKey("endsAtEpochMillis")
        val PAUSED_REMAINING = longPreferencesKey("pausedRemainingMillis")
        val DELIVERED = booleanPreferencesKey("completionDelivered")
    }
}

/**
 * Timer storage scoped per account: the file lives in `files/users/<userHash>/` (the directory the
 * sign-out cleaner already deletes), so another account never reads it. With no signed-in account
 * it reads nothing and drops writes. A DataStore must be a singleton per file, hence the cache;
 * [forget] releases one after its directory is deleted so a re-created account starts clean.
 */
class UserScopedRestTimerStore(
    private val filesDir: File,
    private val currentUserHash: () -> String?,
) : RestTimerStore {
    private class Entry(val scope: CoroutineScope, val store: DataStoreRestTimerStore)

    private val entries = HashMap<String, Entry>()

    override suspend fun read(): RestTimerState? = current()?.read()

    override suspend fun write(state: RestTimerState?) {
        current()?.write(state)
    }

    @Synchronized
    fun forget(userHash: String) {
        entries.remove(userHash)?.scope?.cancel()
    }

    @Synchronized
    private fun current(): DataStoreRestTimerStore? {
        val hash = currentUserHash() ?: return null
        return entries.getOrPut(hash) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(filesDir, "users/$hash/rest_timer.preferences_pb") }
            Entry(scope, DataStoreRestTimerStore(dataStore))
        }.store
    }
}
