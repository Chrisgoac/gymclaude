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
 * the wipe deletes). Only account hashes are stored, never raw ids. DataStore writes are atomic and on disk
 * before [add] returns.
 */
class DataStoreAccountDeletionMarks(file: File, scope: CoroutineScope) : AccountDeletionMarks {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) { file }

    override suspend fun pending(): Set<String> = store.data.first()[PENDING].orEmpty()

    override suspend fun add(hash: String) {
        store.edit { it[PENDING] = it[PENDING].orEmpty() + hash }
    }

    override suspend fun remove(hash: String) {
        store.edit { it[PENDING] = it[PENDING].orEmpty() - hash }
    }

    private companion object {
        val PENDING = stringSetPreferencesKey("pending")
    }
}
