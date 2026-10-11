package com.chrisgoac.gymlog.billing

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chrisgoac.gymlog.data.billing.EntitlementCache
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.devicesettings.UserScopedPreferenceStores
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.first

/**
 * The account's last server entitlement answer, in its preference file under `files/users/<hash>/`
 * (deleted with the account's local data on sign-out). Holds no purchase token.
 *
 * After [forget] (the account's local data is being deleted) writes for that account are dropped, so a late
 * answer can never recreate the deleted directory; the next [read] (the account signs in again) re-enables them.
 */
class DataStoreEntitlementCache(private val stores: UserScopedPreferenceStores) : EntitlementCache {
    private val forgotten = ConcurrentHashMap.newKeySet<String>()

    override suspend fun read(userId: String): String? {
        val hash = UserDatabaseManagerImpl.userHash(userId)
        forgotten -= hash
        return stores.get(hash).data.first()[KEY]
    }

    override suspend fun write(userId: String, json: String) {
        val hash = UserDatabaseManagerImpl.userHash(userId)
        if (hash in forgotten) return
        stores.get(hash).edit { it[KEY] = json }
    }

    /** Called by the sign-out cleaner before it deletes `users/<hash>/`. */
    fun forget(userHash: String) {
        forgotten += userHash
    }

    private companion object {
        val KEY = stringPreferencesKey("billing.entitlement")
    }
}
