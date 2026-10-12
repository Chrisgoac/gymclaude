package com.chrisgoac.gymlog.domain.timer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RestTimerTest {
    private val t0 = 1_700_000_000_000L

    @Test fun `remaining is endsAt minus now and never negative`() {
        val s = RestTimer.start("r1", "s1", t0, 90)
        assertEquals(90_000L, RestTimer.remaining(s, t0))
        assertEquals(30_000L, RestTimer.remaining(s, t0 + 60_000))
        assertEquals(0L, RestTimer.remaining(s, t0 + 90_000))
        assertEquals(0L, RestTimer.remaining(s, t0 + 500_000))
    }

    @Test fun `wall clock advance while the process is away counts as elapsed`() {
        val s = RestTimer.start("r1", "s1", t0, 120)
        // Nothing ticked for 100 s; the next read still sees the wall clock.
        assertEquals(20_000L, RestTimer.remaining(s, t0 + 100_000))
    }

    @Test fun `a clock set backwards never shows more than the total`() {
        val s = RestTimer.start("r1", "s1", t0, 60)
        assertEquals(60_000L, RestTimer.remaining(s, t0 - 3_600_000))
    }

    @Test fun `time zone changes do not matter because only epoch millis are stored`() {
        val original = TimeZone.getDefault()
        try {
            val s = RestTimer.start("r1", "s1", t0, 90)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val a = RestTimer.remaining(s, t0 + 30_000)
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val b = RestTimer.remaining(s, t0 + 30_000)
            assertEquals(60_000L, a)
            assertEquals(a, b)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun `pause freezes the remaining and resume restarts from it`() {
        val s = RestTimer.start("r1", "s1", t0, 90)
        val paused = RestTimer.pause(s, t0 + 30_000)
        assertTrue(RestTimer.isPaused(paused))
        assertEquals(60_000L, RestTimer.remaining(paused, t0 + 500_000))
        assertFalse(RestTimer.completionDue(paused, t0 + 500_000))
        val resumed = RestTimer.resume(paused, t0 + 100_000)
        assertFalse(RestTimer.isPaused(resumed))
        assertEquals(t0 + 160_000L, resumed.endsAtEpochMillis)
        assertEquals(60_000L, RestTimer.remaining(resumed, t0 + 100_000))
    }

    @Test fun `pause and resume are idempotent`() {
        val s = RestTimer.start("r1", "s1", t0, 90)
        assertEquals(s, RestTimer.resume(s, t0 + 10_000))
        val paused = RestTimer.pause(s, t0 + 30_000)
        assertEquals(paused, RestTimer.pause(paused, t0 + 50_000))
    }

    @Test fun `extend adds time to a running or paused timer`() {
        val s = RestTimer.start("r1", "s1", t0, 60)
        val more = RestTimer.extend(s, t0 + 10_000, 30, nextRunId = "r2")
        assertEquals(80_000L, RestTimer.remaining(more, t0 + 10_000))
        assertEquals("r1", more.runId)
        val paused = RestTimer.pause(s, t0 + 20_000)
        assertEquals(70_000L, RestTimer.remaining(RestTimer.extend(paused, t0 + 25_000, 30, "r2"), t0 + 99_000))
    }

    @Test fun `extending a finished timer starts a new run`() {
        val s = RestTimer.start("r1", "s1", t0, 60)
        val delivered = RestTimer.markDelivered(s)
        val again = RestTimer.extend(delivered, t0 + 70_000, 15, nextRunId = "r2")
        assertEquals("r2", again.runId)
        assertFalse(again.completionDelivered)
        assertEquals(15_000L, RestTimer.remaining(again, t0 + 70_000))
    }

    @Test fun `completion is due exactly when the end is reached and only once`() {
        val s = RestTimer.start("r1", "s1", t0, 60)
        assertFalse(RestTimer.completionDue(s, t0 + 59_999))
        assertTrue(RestTimer.completionDue(s, t0 + 60_000))
        assertFalse(RestTimer.completionDue(RestTimer.markDelivered(s), t0 + 70_000))
    }

    @Test fun `non positive duration is rejected`() {
        assertEquals(null, RestTimer.startOrNull("r1", "s1", t0, 0))
        assertEquals(null, RestTimer.startOrNull("r1", "s1", t0, null))
    }

    // ---- controller: persistence, process death and exactly-once completion ----

    private class MemoryStore(var saved: RestTimerState? = null) : RestTimerStore {
        override suspend fun read() = saved
        override suspend fun write(state: RestTimerState?) { saved = state }
    }

    private class RecordingAlarm : RestTimerAlarm {
        val scheduled = mutableListOf<Triple<String, String, Long>>()
        var cancels = 0
        override fun schedule(runId: String, sessionId: String, endsAtEpochMillis: Long) { scheduled += Triple(runId, sessionId, endsAtEpochMillis) }
        override fun cancel() { cancels++ }
    }

    private var now = t0
    private var ids = 0
    private fun controller(store: RestTimerStore, alarm: RestTimerAlarm) =
        RestTimerController(store, alarm, clock = { now }, newRunId = { "run-${++ids}" })

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `start persists endsAt and schedules the alarm`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        val c = controller(store, alarm)
        c.start("s1", 90)
        assertEquals(t0 + 90_000, store.saved?.endsAtEpochMillis)
        assertEquals(listOf(Triple("run-1", "s1", t0 + 90_000)), alarm.scheduled)
        assertEquals(store.saved, c.state.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `pause cancels the alarm and nothing is scheduled while paused`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        val c = controller(store, alarm)
        c.start("s1", 90)
        now += 30_000
        c.pause()
        assertEquals(1, alarm.cancels)
        assertEquals(1, alarm.scheduled.size)
        c.restore("s1") // a rebuilt screen over a paused timer must not re-arm it
        assertEquals(1, alarm.scheduled.size)
        now += 10_000
        c.resume()
        assertEquals(t0 + 100_000, alarm.scheduled.last().third)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `reset clears persisted state and cancels the alarm`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        val c = controller(store, alarm)
        c.start("s1", 90)
        c.reset()
        assertNull(store.saved)
        assertNull(c.state.value)
        assertTrue(alarm.cancels >= 1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `completion fires exactly once, also across process death`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        val first = controller(store, alarm)
        first.start("s1", 60)

        // Process death before the end: a new controller over the same store resumes the countdown.
        now += 20_000
        val second = controller(store, alarm)
        val seen = mutableListOf<RestTimerCompletion>()
        second.completions.onEach { seen += it }.launchIn(backgroundScope)
        second.restore("s1")
        assertEquals(40_000L, RestTimer.remaining(second.state.value!!, now))
        assertTrue(seen.isEmpty())

        now += 40_000
        second.tick(); second.tick(); second.tick()
        assertEquals(1, seen.size)
        assertEquals("run-1", seen.single().runId)
        assertTrue(store.saved!!.completionDelivered)

        // Another process death after delivery: no second event.
        val third = controller(store, alarm)
        val again = mutableListOf<RestTimerCompletion>()
        third.completions.onEach { again += it }.launchIn(backgroundScope)
        now += 5_000
        third.restore("s1"); third.tick()
        assertTrue(again.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a timer that ended while the process was dead completes once on restore with its lateness`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        controller(store, alarm).start("s1", 60)
        now += 100_000
        val c = controller(store, alarm)
        val seen = mutableListOf<RestTimerCompletion>()
        c.completions.onEach { seen += it }.launchIn(backgroundScope)
        c.restore("s1"); c.tick()
        assertEquals(1, seen.size)
        assertEquals(40_000L, seen.single().lateMillis)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `a timer of another workout is dropped on restore`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        controller(store, alarm).start("old", 60)
        val c = controller(store, alarm)
        c.restore("new")
        assertNull(c.state.value)
        assertNull(store.saved)
        assertTrue(alarm.cancels >= 1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `restore re-arms the alarm of a running timer`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        controller(store, alarm).start("s1", 60)
        val fresh = RecordingAlarm()
        controller(store, fresh).restore("s1")
        assertEquals(listOf(Triple("run-1", "s1", t0 + 60_000)), fresh.scheduled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `starting with no duration does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        val c = controller(store, alarm)
        c.start("s1", null)
        c.start("s1", 0)
        assertNull(store.saved)
        assertTrue(alarm.scheduled.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `restore with nothing saved cancels a stale alarm and detach drops memory but keeps storage`() = runTest(UnconfinedTestDispatcher()) {
        val alarm = RecordingAlarm()
        val empty = controller(MemoryStore(), alarm)
        empty.restore("s1")
        assertEquals(1, alarm.cancels)

        // Account A's run is never visible to account B.
        val storeA = MemoryStore(); val a = RecordingAlarm()
        val c = controller(storeA, a)
        c.start("s1", 60)
        c.detach()
        assertNull(c.state.value)
        assertTrue(a.cancels >= 1)
        assertTrue("A's own file keeps its run", storeA.saved != null)
        val storeB = MemoryStore()
        val b = RecordingAlarm()
        c.restore("s1") // rebinding to the (now B) store below
        val forB = controller(storeB, b)
        forB.restore("s1")
        assertNull(forB.state.value)
        assertEquals(1, b.cancels)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `restore does not arm an end already in the past and tick delivers it`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        controller(store, alarm).start("s1", 60)
        now += 90_000
        val fresh = RecordingAlarm()
        val c = controller(store, fresh)
        val seen = mutableListOf<RestTimerCompletion>()
        c.completions.onEach { seen += it }.launchIn(backgroundScope)
        c.restore("s1"); c.tick()
        assertTrue(fresh.scheduled.isEmpty())
        assertEquals(1, seen.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `extending an ended undelivered run cancels the old alarm and notification before the new one is armed`() = runTest(UnconfinedTestDispatcher()) {
        val store = MemoryStore(); val alarm = RecordingAlarm()
        val c = controller(store, alarm)
        val seen = mutableListOf<RestTimerCompletion>()
        c.completions.onEach { seen += it }.launchIn(backgroundScope)
        c.start("s1", 60)
        now += 70_000 // ended, tick never ran, the alarm may already have posted its notification
        val cancelsBefore = alarm.cancels
        c.extend(30)
        assertEquals(cancelsBefore + 1, alarm.cancels)
        assertEquals("run-2", c.state.value!!.runId)
        assertEquals(now + 30_000, alarm.scheduled.last().third)
        assertTrue("old completion is dropped, not delivered late", seen.isEmpty())
    }
}
