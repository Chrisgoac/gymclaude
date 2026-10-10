package com.chrisgoac.gymlog.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.network.ServerClock
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ServerClockStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("clock-test", Context.MODE_PRIVATE)
    private var wall = 1_000_000L
    private var elapsed = 50_000L
    private var boot: Int? = 7

    private fun store() = ServerClockStore(prefs, wallNow = { wall }, elapsedNow = { elapsed }, bootCount = { boot })

    @Test fun nothingMeasuredYetSeedsZero() {
        assertEquals(0L, store().seedOffset())
    }

    @Test fun theLastMeasuredOffsetSeedsTheNextProcessBeforeAnyResponse() {
        // First process: a response says the server is 4 s ahead.
        val first = store()
        val clock = ServerClock(deviceNowMillis = { wall }, initialOffsetMillis = first.seedOffset(), onObserved = first::save)
        clock.observe(wall + 4_000)

        // Next process, same boot, 10 s later: stamps are corrected before any network call.
        wall += 10_000
        elapsed += 10_000
        val next = store()
        val restarted = ServerClock(deviceNowMillis = { wall }, initialOffsetMillis = next.seedOffset())
        assertEquals(4_000L, restarted.offsetMillis)
        assertEquals(wall + 4_000, restarted.nowMillis())
    }

    @Test fun aWallClockChangeInTheSameBootIsCompensated() {
        store().save(4_000L)
        wall -= 60_000 // the user set the device clock one minute back
        elapsed += 1_000
        wall += 1_000
        assertEquals(64_000L, store().seedOffset())
    }

    @Test fun afterARebootTheStoredOffsetIsUsedAsIs() {
        store().save(4_000L)
        boot = 8
        elapsed = 10L
        wall -= 60_000
        assertEquals(4_000L, store().seedOffset())
    }
}
