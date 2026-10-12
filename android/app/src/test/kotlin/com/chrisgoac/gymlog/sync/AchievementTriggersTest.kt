package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementTriggersTest {
    @Test fun `reconciling is allowed only with a Ready account and no workout being recorded`() = runTest(UnconfinedTestDispatcher()) {
        val root = MutableStateFlow<RootState>(RootState.Bootstrapping("u1", 0.4f))
        val active = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch { AchievementTriggers.canRun(root, active).collect { seen += it } }

        assertEquals("a partially bootstrapped database is never judged", listOf(false), seen)
        root.value = RootState.Ready("u1")
        active.value = true
        active.value = false
        root.value = RootState.ReauthenticationRequired("u1")
        assertEquals(listOf(false, true, false, true, false), seen)
    }

    @Test fun `one ready signal per account becoming ready`() = runTest(UnconfinedTestDispatcher()) {
        val root = MutableStateFlow<RootState>(RootState.Loading)
        var signals = 0
        backgroundScope.launch { AchievementTriggers.readyAccounts(root).collect { signals++ } }
        root.value = RootState.Bootstrapping("u1", 0.1f)
        assertEquals(0, signals)
        root.value = RootState.Ready("u1")
        root.value = RootState.Ready("u1")
        assertEquals(1, signals)
        root.value = RootState.Ready("u2")
        assertEquals(2, signals)
    }
}
