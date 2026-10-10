package com.chrisgoac.gymlog

import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.database.entity.SyncCursorEntity
import com.chrisgoac.gymlog.model.ApiErrorCode
import com.chrisgoac.gymlog.network.SyncApiException
import java.io.IOException
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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

    @Test fun transientFailureOnAFirstBootstrapOffersARetry() = runTest(UnconfinedTestDispatcher()) {
        bootstrap.failure = SyncApiException.Transport(IOException("offline"))
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)
        // Classifying the failure reads the database on Room's executor.
        assertEquals(RootState.Fatal("bootstrap", canRetry = true), machine.settled())

        bootstrap.failure = null
        machine.retryBootstrap()

        assertEquals(RootState.Ready(USER_A), machine.state.first { it is RootState.Ready })
        assertEquals(listOf(USER_A, USER_A), bootstrap.runs)
    }

    @Test fun transientFailureOnAPreviouslyRestoredDatabaseKeepsTheAppUsable() = runTest(UnconfinedTestDispatcher()) {
        val runner = BootstrapRunner { _, database, _ ->
            // Restored on an earlier run; this replacement download fails offline.
            database.syncDao().saveCursor(SyncCursorEntity(cursor = "c1", updatedAt = 1L))
            throw SyncApiException.Api(503, ApiErrorCode.RETRY_LATER, "RETRY_LATER", true, null, null, null, "busy")
        }
        val machine = RootStateMachine(auth, databases, runner, backgroundScope, true).also { it.start() }
        auth.state.value = AuthState.SignedIn(USER_A)
        assertEquals(RootState.Ready(USER_A), machine.settled())
    }

    @Test fun nonRetryableBootstrapFailureCannotBeRetried() = runTest(UnconfinedTestDispatcher()) {
        bootstrap.failure = SyncApiException.Api(400, ApiErrorCode.INVALID_REQUEST, "INVALID_REQUEST", false, null, null, null, "bad")
        val machine = machine()
        auth.state.value = AuthState.SignedIn(USER_A)
        assertEquals(RootState.Fatal("bootstrap", canRetry = false), machine.state.value)

        bootstrap.failure = null
        machine.retryBootstrap()

        assertEquals(RootState.Fatal("bootstrap", canRetry = false), machine.state.value)
        assertEquals(listOf(USER_A), bootstrap.runs)
    }

    @Test fun cancelledBootstrapThatFailsAfterwardsCannotOverwriteSignedOut() = runTest(StandardTestDispatcher()) {
        val runner = BootstrapRunner { _, database, _ ->
            database.openHelper.writableDatabase
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                // e.g. a write hitting the database that sign-out is closing.
                throw IllegalStateException("database closed")
            }
        }
        val machine = RootStateMachine(auth, databases, runner, backgroundScope, true).also { it.start() }
        auth.state.value = AuthState.SignedIn(USER_A)
        runCurrent()
        assertEquals(RootState.Bootstrapping(USER_A, 0f), machine.state.value)

        auth.state.value = AuthState.SignedOut
        runCurrent() // advanceUntilIdle() would skip backgroundScope work.

        assertEquals(RootState.SignedOut, machine.state.value)
        assertNull(databases.current.value)
    }

    @Test fun supersededBootstrapThatIgnoresCancellationCannotOverwriteTheNewAccount() = runTest(StandardTestDispatcher()) {
        val runner = BootstrapRunner { userId, _, onProgress ->
            if (userId == USER_A) {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    onProgress(0.9f) // Misbehaving runner: keeps reporting and then "completes".
                }
            } else {
                awaitCancellation() // B is still downloading.
            }
        }
        val machine = RootStateMachine(auth, databases, runner, backgroundScope, true).also { it.start() }
        auth.state.value = AuthState.SignedIn(USER_A)
        runCurrent()

        auth.state.value = AuthState.SignedIn(USER_B)
        runCurrent() // advanceUntilIdle() would skip backgroundScope work.

        assertEquals(RootState.Bootstrapping(USER_B, 0f), machine.state.value)
        assertEquals(USER_B, databases.current.value?.userId)
    }

    private suspend fun RootStateMachine.settled(): RootState = state.first { it !is RootState.Bootstrapping && it !is RootState.Loading }

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
