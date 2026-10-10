package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.domain.BackgroundSyncScheduler
import com.chrisgoac.gymlog.domain.LocalAccountCleaner
import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SignOutUseCase
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncStatus
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.SyncUseCases
import com.chrisgoac.gymlog.domain.auth.AuthGateway
import com.chrisgoac.gymlog.domain.auth.AuthSessionState
import com.chrisgoac.gymlog.domain.auth.AuthStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val status = MutableStateFlow(SyncStatus(false, 0, 0, true, null))
    private val diagnostics = MutableStateFlow<List<OutboxDiagnostic>>(emptyList())
    private val events = mutableListOf<String>()
    private var unsynced = 0

    private val sync = object : SyncUseCases {
        override fun observeStatus() = status
        override fun observeDiagnostics() = diagnostics
        override suspend fun syncNow(): SyncResult { events += "syncNow"; return SyncResult.Success(0, 0, 0) }
    }
    private val signOut = SignOutUseCase(
        currentUserId = { "u" },
        scheduler = object : BackgroundSyncScheduler {
            override suspend fun ensurePeriodic(userId: String) { events += "periodic" }
            override suspend fun requestSync(userId: String) { events += "request" }
            override suspend fun cancel(userId: String) { events += "cancel" }
        },
        cleaner = object : LocalAccountCleaner {
            override suspend fun unsyncedChangeCount(userId: String) = unsynced
            override suspend fun deleteLocalData(userId: String) { events += "delete" }
        },
        auth = object : AuthGateway {
            override val state: StateFlow<AuthSessionState> = MutableStateFlow(AuthSessionState.SignedIn("u"))
            override val serviceUnavailable: StateFlow<Boolean> = MutableStateFlow(false)
            override suspend fun signOut(): AuthStep { events += "signOut"; return AuthStep.Completed }
            override suspend fun startEmailCodeSignIn(email: String) = error("unused")
            override suspend fun verifyEmailCodeSignIn(code: String) = error("unused")
            override suspend fun signInWithPassword(email: String, password: String) = error("unused")
            override suspend fun startSignUp(email: String, password: String?) = error("unused")
            override suspend fun verifySignUp(code: String) = error("unused")
            override suspend fun resendSignUpCode() = error("unused")
            override suspend fun signInWithGoogle() = error("unused")
        },
    )

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel(scope: kotlinx.coroutines.CoroutineScope): SettingsViewModel =
        SettingsViewModel(sync, signOut).also { vm -> scope.launch { vm.state.collect { } } }

    @Test fun exposesTheStatusAsAUiState() = runTest(dispatcher) {
        val vm = newViewModel(backgroundScope)
        assertEquals(SyncUiState.Synced, vm.state.value.sync)

        status.value = SyncStatus(false, 3, 0, true, SyncResult.Retryable(null, 0))
        assertEquals(SyncUiState.Offline(3), vm.state.value.sync)
    }

    @Test fun signOutWithNothingUnsyncedFinishesWithoutAsking() = runTest(dispatcher) {
        val vm = newViewModel(backgroundScope)
        vm.signOut()
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(listOf("cancel", "signOut", "delete"), events)
    }

    @Test fun signOutWithUnsyncedChangesAsksFirstAndDeletesOnlyAfterConfirmation() = runTest(dispatcher) {
        unsynced = 3
        val vm = newViewModel(backgroundScope)
        vm.signOut()
        assertEquals(SignOutPhase.Confirm(3), vm.state.value.signOut)
        assertEquals(listOf("cancel"), events)

        vm.confirmDiscard()
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(listOf("cancel", "signOut", "delete"), events)
    }

    @Test fun decliningTheConfirmationKeepsDataAndResumesSync() = runTest(dispatcher) {
        unsynced = 3
        val vm = newViewModel(backgroundScope)
        vm.signOut()
        vm.cancelSignOut()
        assertEquals(SignOutPhase.Idle, vm.state.value.signOut)
        assertEquals(listOf("cancel", "periodic", "request"), events)
    }
}
