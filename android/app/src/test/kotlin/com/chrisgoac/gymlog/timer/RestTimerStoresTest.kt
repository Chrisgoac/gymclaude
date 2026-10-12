package com.chrisgoac.gymlog.timer

import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.domain.timer.RestTimerState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RestTimerStoresTest {
    private val filesDir: File = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir
    private var user: String? = "hashA"
    private val store = UserScopedRestTimerStore(File(filesDir, "timer-test-${System.nanoTime()}").also { it.mkdirs() }, currentUserHash = { user })
    private val run = RestTimerState("run", "s1", 90_000, 5_000, pausedRemainingMillis = null, completionDelivered = false)

    @Test fun `state round trips and survives a new store over the same files (process death)`() = runBlocking {
        val dir = File(filesDir, "timer-rt-${System.nanoTime()}").also { it.mkdirs() }
        val first = UserScopedRestTimerStore(dir) { "hashA" }
        first.write(run.copy(pausedRemainingMillis = 42_000, completionDelivered = true))
        first.forget("hashA")
        val second = UserScopedRestTimerStore(dir) { "hashA" }
        assertEquals(run.copy(pausedRemainingMillis = 42_000, completionDelivered = true), second.read())
    }

    @Test fun `another account never sees the timer and signed-out reads nothing`() = runBlocking {
        store.write(run)
        user = "hashB"
        assertNull(store.read())
        user = null
        assertNull(store.read())
        store.write(run) // dropped
        user = "hashA"
        assertEquals(run, store.read())
    }

    @Test fun `the file lives under users hash so the account cleaner deletes it`() = runBlocking {
        val dir = File(filesDir, "timer-dir-${System.nanoTime()}").also { it.mkdirs() }
        UserScopedRestTimerStore(dir) { "hashA" }.write(run)
        assertTrue(File(dir, "users/hashA/rest_timer.preferences_pb").exists())
        assertFalse(File(dir, "users/hashB").exists())
    }

    @Test fun `writing null clears the run`() = runBlocking {
        store.write(run)
        store.write(null)
        assertNull(store.read())
    }
}
