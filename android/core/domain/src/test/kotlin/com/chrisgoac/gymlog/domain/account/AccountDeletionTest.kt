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

    @Test fun `a mismatching confirmation never reaches the server`() = runTest {
        val deletion = AccountDeletion({ "u" }, { _, _ -> error("not called") }, { _, _ -> error("not called") })
        assertEquals(AccountDeletionOutcome.ConfirmationMismatch, deletion.request("Eliminar"))
        assertEquals(AccountDeletionOutcome.ConfirmationMismatch, deletion.request(""))
    }

    @Test fun `no signed-in account is a session problem`() = runTest {
        val deletion = AccountDeletion({ null }, { _, _ -> error("not called") }, { _, _ -> error("not called") })
        assertEquals(AccountDeletionOutcome.SessionEnded, deletion.request("ELIMINAR"))
    }

    @Test fun `only an accepted request starts the local wipe, for that exact account`() = runTest {
        val answers = ArrayDeque(listOf<ServerDeletionOutcome>(ServerDeletionOutcome.ReauthRequired, ServerDeletionOutcome.Accepted(false, null)))
        val deletion = AccountDeletion(
            { "user_a" },
            { userId, fresh -> calls += "server:$userId:$fresh"; answers.removeFirst() },
            { userId, _ -> calls += "local:$userId" },
        )
        assertEquals(AccountDeletionOutcome.ReauthRequired, deletion.request(" ELIMINAR "))
        assertEquals(AccountDeletionOutcome.Started, deletion.request("ELIMINAR", afterReauth = true))
        assertEquals(listOf("server:user_a:false", "server:user_a:true", "local:user_a"), calls)
    }

    @Test fun `the local wipe runs to the end even if the caller is cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var finished = false
        val deletion = AccountDeletion(
            { "u" },
            { _, _ -> ServerDeletionOutcome.Accepted(true, null) },
            { _, _ -> gate.await(); finished = true },
        )
        val request = async { deletion.request("ELIMINAR") }
        yield()
        request.cancel()
        gate.complete(Unit)
        runCatching { request.await() }
        assertTrue(finished)
    }
}
