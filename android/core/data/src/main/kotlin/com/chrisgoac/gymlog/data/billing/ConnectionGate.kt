package com.chrisgoac.gymlog.data.billing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One Play connection attempt at a time: concurrent [connect] calls await the attempt in progress instead of
 * calling `startConnection` again. A disconnection reported before setup finished resumes the waiters with
 * [BillingCode.SERVICE_DISCONNECTED] (transient: the caller may retry); no answer within [timeoutMillis] is
 * [BillingCode.SERVICE_TIMEOUT] and lets the next call start a fresh attempt.
 */
internal class ConnectionGate(private val timeoutMillis: Long) {
    /** Callbacks for one attempt; later calls after the first are ignored. */
    interface Callbacks {
        fun onSetupFinished(code: BillingCode)
        fun onDisconnected()
    }

    private val lock = Any()
    private var attempt: CompletableDeferred<BillingCode>? = null

    suspend fun connect(start: (Callbacks) -> Unit): BillingCode {
        val (deferred, starter) = synchronized(lock) {
            attempt?.takeIf { it.isActive }?.let { it to false }
                ?: CompletableDeferred<BillingCode>().also { attempt = it }.let { it to true }
        }
        if (starter) {
            start(
                object : Callbacks {
                    override fun onSetupFinished(code: BillingCode) {
                        deferred.complete(code)
                    }

                    override fun onDisconnected() {
                        deferred.complete(BillingCode.SERVICE_DISCONNECTED)
                    }
                },
            )
        }
        return withTimeoutOrNull(timeoutMillis) { deferred.await() }
            ?: BillingCode.SERVICE_TIMEOUT.also { deferred.complete(it) }
    }
}
