package com.chrisgoac.gymlog.sync

import android.util.Log
import com.chrisgoac.gymlog.data.catalog.CatalogAsset
import com.chrisgoac.gymlog.data.catalog.CatalogImporter
import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * Makes sure the packaged global exercise catalog is in every database that gets opened (first
 * bootstrap, app start, version upgrade). [CatalogImporter] is a version check once imported. The
 * catalog is local-only reference data, so a failure is logged and never blocks opening the account.
 */
class CatalogSeedingUserDatabaseManager(
    private val delegate: UserDatabaseManager,
    private val loadAsset: () -> CatalogAsset,
) : UserDatabaseManager {
    private val asset by lazy(loadAsset)

    override val current: StateFlow<OpenUserDatabase?> get() = delegate.current

    override suspend fun open(userId: String): GymLogDatabase {
        val database = delegate.open(userId)
        try {
            CatalogImporter.importIfNeeded(database, asset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w("GymLogCatalog", "Could not import the exercise catalog", e)
        }
        return database
    }

    override suspend fun close() = delegate.close()
    override suspend fun closeAndDelete(userId: String) = delegate.closeAndDelete(userId)
}
