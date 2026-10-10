package com.chrisgoac.gymlog.network

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks the offset between the server clock and the device clock, learned from the HTTP `Date`
 * header of every response (contract rule: stamp updatedAt with `localNow + offset`).
 *
 * [initialOffsetMillis] seeds the offset before any response is seen (the last one measured, see
 * [ClockOffsetSample.seedOffset]); [onObserved] is told every new measurement so it can be persisted.
 */
class ServerClock(
    initialOffsetMillis: Long = 0L,
    private val onObserved: (offsetMillis: Long) -> Unit = {},
    /** Last so that `ServerClock { fakeNow }` keeps meaning the device clock. */
    private val deviceNowMillis: () -> Long = System::currentTimeMillis,
) {
    private val offset = AtomicLong(initialOffsetMillis)

    /** serverDate - deviceNow, in ms; the seeded value (0 by default) until a response has been observed. */
    val offsetMillis: Long get() = offset.get()

    fun nowMillis(): Long = deviceNowMillis() + offset.get()

    /** Records the server's wall clock, as observed "now" on the device. */
    fun observe(serverMillis: Long) {
        val measured = serverMillis - deviceNowMillis()
        offset.set(measured)
        onObserved(measured)
    }
}

/**
 * A persisted offset measurement: [offsetMillis] measured when the device wall clock read
 * [measuredAtWallMillis] and the monotonic clock (elapsed realtime) read [measuredAtElapsedMillis],
 * during boot number [bootCount] (null when unknown).
 */
data class ClockOffsetSample(
    val offsetMillis: Long,
    val measuredAtWallMillis: Long,
    val measuredAtElapsedMillis: Long,
    val bootCount: Int?,
) {
    /**
     * The offset to seed at startup. In the same boot the monotonic clock tells how much real time
     * passed, so a change of the device wall clock since the measurement (the user setting the
     * time) is compensated. After a reboot the monotonic anchor is meaningless and the stored offset
     * is used as is.
     */
    fun seedOffset(nowWallMillis: Long, nowElapsedMillis: Long, currentBootCount: Int?): Long {
        val sameBoot = bootCount != null && bootCount == currentBootCount && nowElapsedMillis >= measuredAtElapsedMillis
        if (!sameBoot) return offsetMillis
        val wallDrift = (nowWallMillis - measuredAtWallMillis) - (nowElapsedMillis - measuredAtElapsedMillis)
        return offsetMillis - wallDrift
    }
}
