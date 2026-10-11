package com.chrisgoac.gymlog.domain.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionTest {
    private val calls = mutableListOf<String>()

    private inner class RecordingLocal(private val onBegin: suspend () -> Unit = {}) : LocalAccountDeletion {
        override suspend fun intend(userId: String) { calls += "intend:$userId" }
        override suspend fun withdraw(userId: String) { calls += "withdraw:$userId" }
        override suspend fun begin(userId: String, subscription: LiveSubscription?) { onBegin(); calls += "begin:$userId" }
    }

    private fun deletion(vararg answers: ServerDeletionOutcome, local: LocalAccountDeletion = RecordingLocal()): AccountDeletion {
        val queue = ArrayDeque(answers.toList())
        return AccountDeletion({ "user_a" }, { userId, fresh -> calls += "server:$userId:$fresh"; queue.removeFirst() }, local)
    }

    @Test fun `a mismatching confirmation never reaches the server nor records an intent`() = runTest {
        val deletion = deletion()
        assertEquals(AccountDeletionOutcome.ConfirmationMismatch, deletion.request("Eliminar"))
        assertEquals(AccountDeletionOutcome.ConfirmationMismatch, deletion.request(""))
        assertTrue(calls.isEmpty())
    }

    @Test fun `no signed-in account is a session problem`() = runTest {
        val deletion = AccountDeletion({ null }, { _, _ -> error("not called") }, RecordingLocal())
        assertEquals(AccountDeletionOutcome.SessionEnded, deletion.request("ELIMINAR"))
        assertTrue(calls.isEmpty())
    }

    @Test fun `the intent is written before sending and promoted by acceptance`() = runTest {
        assertEquals(AccountDeletionOutcome.Started, deletion(ServerDeletionOutcome.Accepted(false, null)).request(" ELIMINAR "))
        assertEquals(listOf("intend:user_a", "server:user_a:false", "begin:user_a"), calls)
    }

    @Test fun `reauthentication required clears the intent`() = runTest {
        assertEquals(AccountDeletionOutcome.ReauthRequired, deletion(ServerDeletionOutcome.ReauthRequired).request("ELIMINAR"))
        assertEquals(listOf("intend:user_a", "server:user_a:false", "withdraw:user_a"), calls)
    }

    @Test fun `a definitive failure or an ended session clears the intent`() = runTest {
        deletion(ServerDeletionOutcome.Failed(retryable = false), ServerDeletionOutcome.SessionEnded).apply {
            assertEquals(AccountDeletionOutcome.Failed(false), request("ELIMINAR"))
            assertEquals(AccountDeletionOutcome.SessionEnded, request("ELIMINAR"))
        }
        assertEquals(2, calls.count { it == "withdraw:user_a" })
    }

    @Test fun `an ambiguous answer keeps the intent`() = runTest {
        deletion(ServerDeletionOutcome.Offline, ServerDeletionOutcome.Failed(retryable = true)).apply {
            assertEquals(AccountDeletionOutcome.Offline, request("ELIMINAR"))
            assertEquals(AccountDeletionOutcome.Failed(true), request("ELIMINAR", afterReauth = true))
        }
        assertEquals(listOf("intend:user_a", "server:user_a:false", "intend:user_a", "server:user_a:true"), calls)
    }

    @Test fun `if the intent cannot be recorded nothing is sent`() = runTest {
        val failing = object : LocalAccountDeletion {
            override suspend fun intend(userId: String) = error("disk")
            override suspend fun withdraw(userId: String) = Unit
            override suspend fun begin(userId: String, subscription: LiveSubscription?) = Unit
        }
        assertEquals(AccountDeletionOutcome.Failed(true), deletion(local = failing).request("ELIMINAR"))
        assertTrue(calls.isEmpty())
    }

    @Test fun `the local wipe runs to the end even if the caller is cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val deletion = deletion(ServerDeletionOutcome.Accepted(true, null), local = RecordingLocal { gate.await(); finished = true })
        val request = async { deletion.request("ELIMINAR") }
        yield()
        request.cancel()
        gate.complete(Unit)
        runCatching { request.await() }
        assertTrue(finished)
    }
}
