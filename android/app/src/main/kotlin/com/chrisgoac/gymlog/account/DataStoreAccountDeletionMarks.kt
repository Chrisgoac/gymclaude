package com.chrisgoac.gymlog.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * [AccountDeletionMarks] in a device-level Preferences DataStore ([file]; never under a user's directory, which
 * the wipe deletes). Only account hashes are stored, never raw ids. DataStore edits are atomic and on disk
 * before they return.
 */
class DataStoreAccountDeletionMarks(file: File, scope: CoroutineScope) : AccountDeletionMarks {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

    override suspend fun pending(): Set<String> = store.data.first()[PENDING].orEmpty()

    override suspend fun intents(): Set<String> = store.data.first()[INTENTS].orEmpty()

    override suspend fun addIntent(hash: String) {
        store.edit { it[INTENTS] = it[INTENTS].orEmpty() + hash }
    }

    override suspend fun removeIntent(hash: String) {
        store.edit { it[INTENTS] = it[INTENTS].orEmpty() - hash }
    }

    override suspend fun unconfirmed(): Set<String> = store.data.first()[UNCONFIRMED].orEmpty()

    override suspend fun promote(hash: String, confirmed: Boolean) {
        store.edit {
            it[PENDING] = it[PENDING].orEmpty() + hash
            it[INTENTS] = it[INTENTS].orEmpty() - hash
            it[UNCONFIRMED] = if (confirmed) it[UNCONFIRMED].orEmpty() - hash else it[UNCONFIRMED].orEmpty() + hash
        }
    }

    override suspend fun remove(hash: String) {
        store.edit {
            it[PENDING] = it[PENDING].orEmpty() - hash
            it[UNCONFIRMED] = it[UNCONFIRMED].orEmpty() - hash
        }
    }

    private companion object {
        val PENDING = stringSetPreferencesKey("pending")
        val INTENTS = stringSetPreferencesKey("intents")
        val UNCONFIRMED = stringSetPreferencesKey("unconfirmed")
    }
}
