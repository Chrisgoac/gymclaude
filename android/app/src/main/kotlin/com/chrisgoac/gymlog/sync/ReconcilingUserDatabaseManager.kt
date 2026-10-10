package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.database.GymLogDatabase
import com.chrisgoac.gymlog.database.OpenUserDatabase
import com.chrisgoac.gymlog.database.UserDatabaseManager
import kotlinx.coroutines.flow.StateFlow

/** Runs [reconcile] before every open, so a pending deletion is finished before its database can come back. */
class ReconcilingUserDatabaseManager(
    private val delegate: UserDatabaseManager,
    private val reconcile: suspend (userId: String) -> Unit,
) : UserDatabaseManager {
    override val current: StateFlow<OpenUserDatabase?> get() = delegate.current

    override suspend fun open(userId: String): GymLogDatabase {
        if (delegate.current.value?.userId != userId) reconcile(userId)
        return delegate.open(userId)
    }

    override suspend fun close() = delegate.close()
    override suspend fun closeAndDelete(userId: String) = delegate.closeAndDelete(userId)
}
