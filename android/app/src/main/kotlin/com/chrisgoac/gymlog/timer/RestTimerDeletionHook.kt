package com.chrisgoac.gymlog.timer

/**
 * Reaction to an account's local data being deleted: always release that account's open timer
 * store; cancel the wake-up only when it is the CURRENT account's (a startup sweep or reconcile of
 * another hash must not disarm the open account's timer).
 */
class RestTimerDeletionHook(
    private val forgetStore: (userHash: String) -> Unit,
    private val cancelWakeUp: () -> Unit,
    private val currentUserHash: () -> String?,
) {
    fun onDeleted(userHash: String) {
        forgetStore(userHash)
        if (userHash == currentUserHash()) cancelWakeUp()
    }
}
