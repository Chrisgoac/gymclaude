package com.chrisgoac.gymlog.billing

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.chrisgoac.gymlog.data.billing.EntitlementCache
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.devicesettings.UserScopedPreferenceStores
import kotlinx.coroutines.flow.first

/**
 * The account's last server entitlement answer, in its own preference file under `files/users/<hash>/`
 * (deleted with the account's local data on sign-out). Holds no purchase token.
 */
class DataStoreEntitlementCache(private val stores: UserScopedPreferenceStores) : EntitlementCache {
    override suspend fun read(userId: String): String? = stores.get(UserDatabaseManagerImpl.userHash(userId)).data.first()[KEY]

    override suspend fun write(userId: String, json: String) {
        stores.get(UserDatabaseManagerImpl.userHash(userId)).edit { it[KEY] = json }
    }

    private companion object {
        val KEY = stringPreferencesKey("billing.entitlement")
    }
}
