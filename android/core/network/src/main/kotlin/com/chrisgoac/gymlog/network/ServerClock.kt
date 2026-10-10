package com.chrisgoac.gymlog.network

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the offset between the server clock and the device clock, learned from the HTTP `Date`
 * header of every response (contract rule: stamp updatedAt with `localNow + offset`).
 */
class ServerClock(private val deviceNowMillis: () -> Long = System::currentTimeMillis) {
    private val offset = AtomicLong(0L)

    /** serverDate - deviceNow, in ms; 0 until a response has been observed. */
    val offsetMillis: Long get() = offset.get()

    fun nowMillis(): Long = deviceNowMillis() + offset.get()

    /** Records the server's wall clock, as observed "now" on the device. */
    fun observe(serverMillis: Long) {
        offset.set(serverMillis - deviceNowMillis())
    }
}
