package com.chrisgoac.gymlog.timer

import com.chrisgoac.gymlog.RootState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RestTimerAccountWatcherTest {
    private fun detachesFor(vararg states: RootState): Int {
        var n = 0
        runBlocking { RestTimerAccountWatcher(flowOf(*states)) { n++ }.run() }
        return n
    }

    @Test fun `sign-out and a different user detach, same-user transients do not`() {
        assertEquals(1, detachesFor(RootState.Ready("a"), RootState.SignedOut))
        assertEquals(1, detachesFor(RootState.Ready("a"), RootState.Ready("b")))
        assertEquals(0, detachesFor(RootState.Ready("a"), RootState.Bootstrapping("a", 0.5f), RootState.Ready("a")))
        assertEquals(0, detachesFor(RootState.Loading, RootState.SignedOut, RootState.Ready("a")))
        assertEquals(2, detachesFor(RootState.Ready("a"), RootState.SignedOut, RootState.Ready("b"), RootState.Ready("c")))
    }
}
