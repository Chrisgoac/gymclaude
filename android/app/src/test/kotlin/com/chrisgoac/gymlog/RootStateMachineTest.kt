package com.chrisgoac.gymlog

import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootStateMachineTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databases = UserDatabaseManagerImpl(context)
    private val auth = FakeAuthSessionProvider()
    private val bootstrap = FakeBootstrapRunner()

    @After fun tearDown() {
        kotlinx.coroutines.runBlocking {
            databases.closeAndDelete(USER_A)
            databases.closeAndDelete(USER_B)
        }
    }

    private fun TestScope.machine(configured: Boolean = true) =
        RootStateMachine(auth, databases, bootstrap, backgroundScope, configured).also { it.start() }

    @Test fun missingConfigurationIsFatalAndNeverTouchesAuth() = runTest(UnconfinedTestDispatcher()) {
        val machine = machine(configured = false)
        auth.state.value = AuthState.SignedIn(USER_A)
        assertEquals(RootState.Fatal("config"), machine.state.value)
        assertTrue(bootstrap.runs.isEmpty())
        assertNull(databases.current.value)
    }

    @Test fun loadingAndSignedOutNeverOpenADatabase() = runTest(UnconfinedTestDispatcher()) {
        val machine = machine()
        assertEquals(RootState.Loading, machine.state.value)
        auth.state.value = AuthState.SignedOut
        assertEquals(RootState.SignedOut, machine.state.value)
        assertNull(databases.current.value)
        assertTrue(bootstrap.runs.isEmpty())
    }

    @Test fun activeSessionBootstrapsOnceWithProgressThenIsReady() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        bootstrap.gate = gate
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)

        assertEquals(RootState.Bootstrapping(USER_A, 0.5f), machine.state.value)
        assertEquals(USER_A, databases.current.value?.userId)
        gate.complete(Unit)
        assertEquals(RootState.Ready(USER_A), machine.state.value)

        // Re-authenticating the same account does not repeat a completed bootstrap.
        auth.state.value = AuthState.SessionExpired(USER_A)
        auth.state.value = AuthState.SignedIn(USER_A)
        assertEquals(RootState.Ready(USER_A), machine.state.value)
        assertEquals(listOf(USER_A), bootstrap.runs)
    }

    @Test fun expiredSessionKeepsTheDatabaseAndAsksToReauthenticate() = runTest(UnconfinedTestDispatcher()) {
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)
        val database = databases.current.value!!.database

        auth.state.value = AuthState.SessionExpired(USER_A)

        assertEquals(RootState.ReauthenticationRequired(USER_A), machine.state.value)
        assertEquals(USER_A, databases.current.value?.userId)
        assertTrue(database.isOpen)
        assertTrue(databases.databaseFiles(USER_A).first().exists())
    }

    @Test fun accountSwitchClosesTheFirstDatabase() = runTest(UnconfinedTestDispatcher()) {
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)
        val first = databases.current.value!!.database

        auth.state.value = AuthState.SignedIn(USER_B)

        assertFalse(first.isOpen)
        assertEquals(USER_B, databases.current.value?.userId)
        assertEquals(RootState.Ready(USER_B), machine.state.value)
        assertEquals(listOf(USER_A, USER_B), bootstrap.runs)
        // The first account's data is closed, not deleted.
        assertTrue(databases.databaseFiles(USER_A).first().exists())
    }

    @Test fun signOutClosesWithoutDeleting() = runTest(UnconfinedTestDispatcher()) {
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)
        val database = databases.current.value!!.database

        auth.state.value = AuthState.SignedOut

        assertEquals(RootState.SignedOut, machine.state.value)
        assertFalse(database.isOpen)
        assertNull(databases.current.value)
        assertTrue(databases.databaseFiles(USER_A).first().exists())
    }

    @Test fun expiryDuringBootstrapCancelsItAndRetriesAfterReauthentication() = runTest(UnconfinedTestDispatcher()) {
        bootstrap.gate = CompletableDeferred()
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)
        auth.state.value = AuthState.SessionExpired(USER_A)
        assertEquals(RootState.ReauthenticationRequired(USER_A), machine.state.value)

        bootstrap.gate = null
        auth.state.value = AuthState.SignedIn(USER_A)
        assertEquals(RootState.Ready(USER_A), machine.state.value)
        assertEquals(listOf(USER_A, USER_A), bootstrap.runs)
    }

    @Test fun bootstrapFailureIsFatal() = runTest(UnconfinedTestDispatcher()) {
        bootstrap.failure = IllegalStateException("boom")
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)
        assertEquals(RootState.Fatal("bootstrap"), machine.state.value)
    }

    private companion object {
        const val USER_A = "user_root_a"
        const val USER_B = "user_root_b"
    }
}

private class FakeAuthSessionProvider : AuthSessionProvider {
    override val state = MutableStateFlow<AuthState>(AuthState.Loading)
    override suspend fun accessToken(): String? = null
}

private class FakeBootstrapRunner : BootstrapRunner {
    val runs = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null

    override suspend fun run(userId: String, database: com.chrisgoac.gymlog.database.GymLogDatabase, onProgress: (Float) -> Unit) {
        runs += userId
        failure?.let { throw it }
        database.openHelper.writableDatabase // Like a real bootstrap: materializes the user's file.
        onProgress(0.5f)
        gate?.await()
    }
}
