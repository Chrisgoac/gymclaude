package com.chrisgoac.gymlog.sync

import com.chrisgoac.gymlog.RootState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BootstrapAutoRetryTest {
    private val root = MutableStateFlow<RootState>(RootState.Fatal("bootstrap", canRetry = true))
    private val online = MutableStateFlow(false)
    private var retries = 0

    private fun kotlinx.coroutines.test.TestScope.start() {
        backgroundScope.launch { BootstrapAutoRetry(root, online) { retries++ }.run() }
        runCurrent()
    }

    @Test fun retriesOnceWhenConnectivityReturns() = runTest {
        start()
        assertEquals(0, retries)

        online.value = true
        runCurrent()
        assertEquals(1, retries)
    }

    @Test fun doesNotRetryAgainWhileConnectivityStaysUp() = runTest {
        start()
        online.value = true
        runCurrent()
        root.value = RootState.Bootstrapping("u", 0f)
        runCurrent()
        root.value = RootState.Fatal("bootstrap", canRetry = true)
        runCurrent()
        assertEquals(1, retries)
    }

    @Test fun retriesAgainAfterAnotherOutage() = runTest {
        start()
        online.value = true
        runCurrent()
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(2, retries)
    }

    @Test fun neverRetriesAPermanentFailureOrAHealthyApp() = runTest {
        root.value = RootState.Fatal("bootstrap", canRetry = false)
        start()
        online.value = true
        runCurrent()
        online.value = false
        root.value = RootState.Ready("u")
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(0, retries)
    }
}
