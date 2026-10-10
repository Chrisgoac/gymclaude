package com.chrisgoac.gymlog.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Durable, hash-only record of sign-outs that discarded local data. Two states per account:
 * - **intent**: written before the session ends. If the app dies, the session may still be alive,
 *   so an intent never deletes data; it is just dropped at the next start or open of that account.
 * - **confirmed**: written once the sign-out completed. It obliges deletion before that account's
 *   database can be opened again (startup sweep, or [RoomLocalAccountCleaner.reconcile] at open).
 */
class PendingDeletionMarks(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    suspend fun intents(): Set<String> = read(INTENT)
    suspend fun confirmed(): Set<String> = read(CONFIRMED)

    suspend fun markIntent(hash: String) = edit { intent, confirmed -> intent + hash to confirmed }
    suspend fun confirm(hash: String) = edit { intent, confirmed -> intent - hash to confirmed + hash }
    suspend fun clear(hash: String) = edit { intent, confirmed -> intent - hash to confirmed - hash }
    suspend fun clearIntent(hash: String) = edit { intent, confirmed -> intent - hash to confirmed }

    private suspend fun read(key: String): Set<String> = withContext(Dispatchers.IO) {
        synchronized(prefs) { prefs.getStringSet(key, emptySet()).orEmpty().toSet() }
    }

    /** Synchronous (`commit`) so a mark is on disk before the caller proceeds. */
    private suspend fun edit(change: (Set<String>, Set<String>) -> Pair<Set<String>, Set<String>>) = withContext(Dispatchers.IO) {
        synchronized(prefs) {
            val (intent, confirmed) = change(
                prefs.getStringSet(INTENT, emptySet()).orEmpty().toSet(),
                prefs.getStringSet(CONFIRMED, emptySet()).orEmpty().toSet(),
            )
            check(prefs.edit().putStringSet(INTENT, intent).putStringSet(CONFIRMED, confirmed).commit()) {
                "Could not persist the pending deletion"
            }
        }
    }

    private companion object {
        const val FILE = "gymlog.pending-deletion"
        const val INTENT = "intent"
        const val CONFIRMED = "confirmed"
    }
}
