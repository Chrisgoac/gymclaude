package com.chrisgoac.gymlog.data.billing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionGateTest {
    private val gate = ConnectionGate(timeoutMillis = 10_000)
    private val starts = mutableListOf<ConnectionGate.Callbacks>()

    @Test
    fun concurrentConnectsShareOneStartConnection() = runTest {
        val first = async { gate.connect { starts += it } }
        val second = async { gate.connect { starts += it } }
        runCurrent()
        assertEquals(1, starts.size)

        starts.single().onSetupFinished(BillingCode.OK)

        assertEquals(BillingCode.OK, first.await())
        assertEquals(BillingCode.OK, second.await())
    }

    @Test
    fun disconnectionDuringSetupResumesWaitersWithATransientFailure() = runTest {
        val waiting = async { gate.connect { starts += it } }
        runCurrent()

        starts.single().onDisconnected()

        val code = waiting.await()
        assertEquals(BillingCode.SERVICE_DISCONNECTED, code)
        assertEquals(true, code.isTransient)
        // A later disconnect/setup callback of that finished attempt is ignored; the next connect starts anew.
        starts.single().onSetupFinished(BillingCode.OK)
        val next = async { gate.connect { starts += it } }
        runCurrent()
        assertEquals(2, starts.size)
        starts.last().onSetupFinished(BillingCode.OK)
        assertEquals(BillingCode.OK, next.await())
    }

    @Test
    fun noAnswerTimesOutAndTheNextCallStartsAFreshAttempt() = runTest {
        val waiting = async { gate.connect { starts += it } }
        advanceTimeBy(10_001)
        assertEquals(BillingCode.SERVICE_TIMEOUT, waiting.await())

        val next = async { gate.connect { starts += it } }
        runCurrent()
        assertEquals(2, starts.size)
        starts.last().onSetupFinished(BillingCode.BILLING_UNAVAILABLE)
        assertEquals(BillingCode.BILLING_UNAVAILABLE, next.await())
    }
}
