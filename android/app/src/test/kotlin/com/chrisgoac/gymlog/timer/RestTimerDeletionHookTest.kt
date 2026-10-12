package com.chrisgoac.gymlog.timer

import org.junit.Assert.assertEquals
import org.junit.Test

class RestTimerDeletionHookTest {
    @Test fun `deleting another account releases its store but leaves the current wake-up alone`() {
        val forgotten = mutableListOf<String>(); var cancels = 0
        val hook = RestTimerDeletionHook(forgotten::add, { cancels++ }, { "current" })
        hook.onDeleted("other")
        assertEquals(listOf("other"), forgotten)
        assertEquals(0, cancels)
        hook.onDeleted("current")
        assertEquals(listOf("other", "current"), forgotten)
        assertEquals(1, cancels)
    }

    @Test fun `with nobody signed in only the store is released`() {
        var cancels = 0
        RestTimerDeletionHook({}, { cancels++ }, { null }).onDeleted("x")
        assertEquals(0, cancels)
    }
}
