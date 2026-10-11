package com.chrisgoac.gymlog.domain.achievement

import com.chrisgoac.gymlog.domain.history.InvalidationScope
import com.chrisgoac.gymlog.domain.stats.StatsInvalidation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementReconcileSchedulerTest {
    private val invalidation = StatsInvalidation()
    private val ready = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    private val canRun = MutableStateFlow(true)
    private val allowed = mutableListOf<Boolean>()
    private var runs = 0
    private val errors = mutableListOf<Throwable>()
    private var failNext = false

    private fun scheduler() = AchievementReconcileScheduler(
        invalidation, ready, canRun,
        reconcile = { allowTombstone ->
            allowed += allowTombstone
            if (failNext) {
                failNext = false
                error("boom")
            }
            runs++
        },
        debounceMillis = 1_000,
        onError = { errors += it },
    )

    private fun achievements() = setOf(InvalidationScope.ACHIEVEMENTS)

    @Test fun `a burst of triggers is one run after the quiet period`() = runTest(StandardTestDispatcher()) {
        backgroundScope.launch { scheduler().run() }
        runCurrent()
        repeat(5) {
            invalidation.publish(achievements())
            advanceTimeBy(400)
        }
        assertEquals("still inside the burst", 0, runs)
        advanceTimeBy(1_100)
        assertEquals(1, runs)
    }

    @Test fun `only achievement scopes and account-ready signals trigger`() = runTest(StandardTestDispatcher()) {
        backgroundScope.launch { scheduler().run() }
        runCurrent()
        invalidation.publish(setOf(InvalidationScope.VOLUME, InvalidationScope.CHARTS))
        advanceTimeBy(2_000)
        assertEquals(0, runs)
        ready.tryEmit(Unit)
        advanceTimeBy(1_100)
        assertEquals(1, runs)
    }

    @Test fun `it waits while it may not run (no ready account or a workout) and runs once when it can`() = runTest(StandardTestDispatcher()) {
        canRun.value = false
        backgroundScope.launch { scheduler().run() }
        runCurrent()
        invalidation.publish(achievements())
        advanceTimeBy(30_000)
        assertEquals("no run during the workout", 0, runs)
        canRun.value = true
        runCurrent()
        assertEquals(1, runs)
    }

    @Test fun `a failing run is reported and the next trigger still runs`() = runTest(StandardTestDispatcher()) {
        backgroundScope.launch { scheduler().run() }
        runCurrent()
        failNext = true
        invalidation.publish(achievements())
        advanceTimeBy(1_100)
        assertEquals(1, errors.size)
        invalidation.publish(achievements())
        advanceTimeBy(1_100)
        assertEquals(1, runs)
    }

    @Test fun `only a local edit in the window allows tombstoning`() = runTest(StandardTestDispatcher()) {
        backgroundScope.launch { scheduler().run() }
        runCurrent()
        invalidation.publish(InvalidationScope.entries.toSet()) // a pull
        advanceTimeBy(1_100)
        ready.tryEmit(Unit)
        advanceTimeBy(1_100)
        invalidation.publish(achievements()) // a finished workout
        advanceTimeBy(1_100)
        assertEquals(listOf(false, false, false), allowed)

        invalidation.publish(achievements(), localEdit = true)
        invalidation.publish(InvalidationScope.entries.toSet()) // a pull in the same window does not cancel the edit
        advanceTimeBy(1_100)
        invalidation.publish(InvalidationScope.entries.toSet())
        advanceTimeBy(1_100)
        assertEquals(listOf(false, false, false, true, false), allowed)
    }
}
