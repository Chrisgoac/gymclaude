package com.chrisgoac.gymlog

import androidx.test.core.app.ApplicationProvider
import com.chrisgoac.gymlog.account.AccountDeletionStatus
import com.chrisgoac.gymlog.database.UserDatabaseManagerImpl
import com.chrisgoac.gymlog.network.AuthSessionProvider
import com.chrisgoac.gymlog.network.AuthState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Once a deletion has started, the product UI never comes back until the local wipe has finished. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RootStateMachineDeletionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val databases = UserDatabaseManagerImpl(context)
    private val auth = DeletionTestAuth()
    private val bootstrap = DeletionTestBootstrap()
    private val deletion = MutableStateFlow<AccountDeletionStatus>(AccountDeletionStatus.None)

    @After fun tearDown() {
        kotlinx.coroutines.runBlocking { databases.closeAndDelete(USER) }
    }

    @Test fun `a started deletion leaves the product and closes the account even while the session is alive`() = runTest(UnconfinedTestDispatcher()) {
        val machine = RootStateMachine(auth, databases, bootstrap, backgroundScope, true, deletion = deletion).also { it.start() }
        auth.state.value = AuthState.SignedIn(USER)
        assertEquals(RootState.Ready(USER), machine.state.value)

        deletion.value = AccountDeletionStatus.Running
        assertEquals(RootState.DeletingAccount(failed = false), machine.state.value)
        assertNull(databases.current.value)

        auth.state.value = AuthState.SessionExpired(USER)
        auth.state.value = AuthState.SignedIn(USER) // a token refresh while wiping must not reopen anything
        assertEquals(RootState.DeletingAccount(failed = false), machine.state.value)
        assertNull(databases.current.value)

        deletion.value = AccountDeletionStatus.Failed
        assertEquals(RootState.DeletingAccount(failed = true), machine.state.value)

        auth.state.value = AuthState.SignedOut
        deletion.value = AccountDeletionStatus.Done(null)
        assertEquals(RootState.AccountDeleted(null), machine.state.value)

        deletion.value = AccountDeletionStatus.None
        assertEquals(RootState.SignedOut, machine.state.value)
        assertEquals(1, bootstrap.runs.size)
    }

    @Test fun `a deletion pending at startup never opens the account`() = runTest(UnconfinedTestDispatcher()) {
        auth.state.value = AuthState.SignedIn(USER)
        val machine = RootStateMachine(
            auth, databases, bootstrap, backgroundScope, true,
            startupGate = { deletion.value = AccountDeletionStatus.Running },
            deletion = deletion,
        ).also { it.start() }

        assertEquals(RootState.DeletingAccount(failed = false), machine.state.value)
        assertNull(databases.current.value)
        assertTrue(bootstrap.runs.isEmpty())
    }

    @Test fun `a wiped account whose session comes back is never opened again`() = runTest(UnconfinedTestDispatcher()) {
        val machine = RootStateMachine(
            auth, databases, bootstrap, backgroundScope, true,
            deletion = deletion, isDeletedAccount = { it == USER },
        ).also { it.start() }
        auth.state.value = AuthState.SignedIn(USER)
        assertEquals(RootState.SignedOut, machine.state.value)
        assertNull(databases.current.value)
        assertTrue(bootstrap.runs.isEmpty())
    }

    private companion object {
        const val USER = "user_deleting"
    }
}

private class DeletionTestAuth : AuthSessionProvider {
    override val state = MutableStateFlow<AuthState>(AuthState.Loading)
    override suspend fun accessToken(expectedUserId: String, forceRefresh: Boolean): String? = null
}

private class DeletionTestBootstrap : BootstrapRunner {
    val runs = mutableListOf<String>()
    override suspend fun run(userId: String, database: com.chrisgoac.gymlog.database.GymLogDatabase, onProgress: (Float) -> Unit) {
        runs += userId
    }
}
