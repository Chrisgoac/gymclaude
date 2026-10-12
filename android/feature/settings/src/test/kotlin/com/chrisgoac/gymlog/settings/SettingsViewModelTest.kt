package com.chrisgoac.gymlog.settings

import com.chrisgoac.gymlog.domain.OutboxDiagnostic
import com.chrisgoac.gymlog.domain.SyncResult
import com.chrisgoac.gymlog.domain.SyncStatus
import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.SyncUseCases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Sign-out moved to [AccountViewModel] (see AccountViewModelTest). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val status = MutableStateFlow(SyncStatus(false, 0, 0, true, null))
    private val diagnostics = MutableStateFlow<List<OutboxDiagnostic>>(emptyList())
    private val events = mutableListOf<String>()

    private val sync = object : SyncUseCases {
        override fun observeStatus() = status
        override fun observeDiagnostics() = diagnostics
        override suspend fun requestSync() { events += "requestSync" }
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun newViewModel(scope: kotlinx.coroutines.CoroutineScope): SettingsViewModel =
        SettingsViewModel(sync).also { vm -> scope.launch { vm.state.collect { } } }

    @Test fun exposesTheStatusAsAUiState() = runTest(dispatcher) {
        val vm = newViewModel(backgroundScope)
        assertEquals(SyncUiState.Synced, vm.state.value.sync)

        status.value = SyncStatus(false, 3, 0, true, SyncResult.Retryable(null, 0))
        assertEquals(SyncUiState.Offline(3), vm.state.value.sync)
    }

    @Test fun manualSyncIsARequestNotADirectRun() = runTest(dispatcher) {
        newViewModel(backgroundScope).syncNow()
        assertEquals(listOf("requestSync"), events)
    }
}
