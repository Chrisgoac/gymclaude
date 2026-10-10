package com.chrisgoac.gymlog.database

import kotlinx.coroutines.flow.StateFlow

/** The database currently open, and whose it is. */
data class OpenUserDatabase(val userId: String, val database: GymLogDatabase)

/**
 * Owns the per-user Room files (spec 8.4): one physical database per account, at most one open at
 * a time, never shared between accounts.
 */
interface UserDatabaseManager {
    /** The open database, or null when none is open. */
    val current: StateFlow<OpenUserDatabase?>

    /** Opens [userId]'s database, closing any other user's database first. Idempotent. */
    suspend fun open(userId: String): GymLogDatabase

    /** Closes the open database without deleting it (e.g. session expired, data kept). */
    suspend fun close()

    /**
     * Closes [userId]'s database if open, then deletes its files. Callers must have resolved the
     * outbox (or obtained explicit confirmation) first: unsynced changes are lost.
     */
    suspend fun closeAndDelete(userId: String)
}
