package com.chrisgoac.gymlog.history

import com.chrisgoac.gymlog.domain.SyncUiState
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.HistoryDetail
import com.chrisgoac.gymlog.domain.workout.HistoryFilter
import com.chrisgoac.gymlog.domain.workout.HistoryItem
import com.chrisgoac.gymlog.domain.workout.HistoryPage
import com.chrisgoac.gymlog.domain.workout.HistoryPeriod
import com.chrisgoac.gymlog.domain.workout.HistoryRepository
import com.chrisgoac.gymlog.domain.workout.StartedWorkout
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutExercise
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import com.chrisgoac.gymlog.domain.workout.WorkoutSession
import com.chrisgoac.gymlog.domain.workout.WorkoutSet
import com.chrisgoac.gymlog.domain.workout.summary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun item(id: String, at: Long, gym: String? = null) =
    HistoryItem(id, at, 1800, gym, gym?.let { "Gym $it" }, null, null, 1, 3, 1200.0)

private class FakeHistory : HistoryRepository {
    val all = MutableStateFlow((1..70).map { item("s$it", 1_000_000L - it) })
    val requests = mutableListOf<Pair<HistoryFilter, Int>>()
    val detail = MutableStateFlow<HistoryDetail?>(null)
    val calls = mutableListOf<String>()
    var failList = 0
    var failDelete = false
    var failGym = false
    var deleteGate: CompletableDeferred<Unit>? = null

    override fun observeHistory(filter: HistoryFilter, limit: Int): Flow<HistoryPage> {
        requests += filter to limit
        if (failList > 0) {
            failList--
            return flow { throw IllegalStateException("disk") }
        }
        return all.map { rows -> HistoryPage(rows.filter { filter.gymId == null || it.gymId == filter.gymId }.take(limit), rows.size > limit) }
    }

    override fun observeDetail(sessionId: String): Flow<HistoryDetail?> = detail

    override suspend fun changeGym(sessionId: String, gymId: String?) {
        if (failGym) throw IllegalStateException("disk")
        calls += "gym:$gymId"
    }

    override suspend fun deleteSession(sessionId: String) {
        deleteGate?.await()
        if (failDelete) throw IllegalStateException("disk")
        calls += "delete:$sessionId"
    }
}

private class GymsOnly(gyms: List<Gym>) : WorkoutRepository {
    private val flow = flowOf(gyms)
    override fun observeActiveWorkout(): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeWorkout(sessionId: String): Flow<WorkoutAggregate?> = flowOf(null)
    override fun observeGyms(): Flow<List<Gym>> = flow
    override fun observeTrainingSummary(): Flow<TrainingSummary> = flowOf(TrainingSummary(0, 3, 0))
    override suspend fun startFreeWorkout(gymId: String?) = StartedWorkout("x", false)
    override suspend fun startRoutineWorkout(routineId: String, gymId: String?) = StartedWorkout("x", false)
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeHistory()
    private val gyms = GymsOnly(listOf(Gym("g1", "Casa"), Gym("g2", "Fitness")))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.listViewModel(sync: Flow<SyncUiState> = flowOf(SyncUiState.Synced)): HistoryListViewModel {
        val vm = HistoryListViewModel(repo, gyms, sync, pageSize = 30)
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun aggregate() = WorkoutAggregate(
        WorkoutSession("s1", null, "g1", 1_000L, 1800, "nota"),
        listOf(WorkoutExercise("le1", "ex", "Press", 0, listOf(WorkoutSet("a", 0, 100.0, 5, false)))),
    )

    private fun TestScope.detailViewModel(): HistoryDetailViewModel {
        repo.detail.value = HistoryDetail(aggregate(), "Casa", null, aggregate().summary(1800))
        val vm = HistoryDetailViewModel(repo, gyms, "s1")
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    // ---- List ----

    @Test fun `the first page is bounded and load more widens the window`() = runTest(dispatcher) {
        val vm = listViewModel()
        assertEquals(30, vm.state.value.items.size)
        assertTrue(vm.state.value.hasMore)
        assertEquals(listOf("g1", "g2"), vm.state.value.gyms.map { it.id })

        vm.loadMore()
        runCurrent()

        assertEquals(60, vm.state.value.items.size)
        assertEquals(60, repo.requests.last().second)
    }

    @Test fun `any filter change returns to the first page and reaches the repository`() = runTest(dispatcher) {
        val vm = listViewModel()
        vm.loadMore()
        runCurrent()

        vm.onPeriod(HistoryPeriod.MONTHS_3)
        vm.onQuery("press")
        vm.onGym("g1")
        runCurrent()

        val (filter, limit) = repo.requests.last()
        assertEquals(HistoryFilter(HistoryPeriod.MONTHS_3, "g1", "press"), filter)
        assertEquals(30, limit)
        assertTrue(vm.state.value.filterActive)
    }

    @Test fun `selecting the active gym again clears it and clear filters resets everything`() = runTest(dispatcher) {
        val vm = listViewModel()
        vm.onGym("g2")
        runCurrent()
        assertEquals("g2", vm.state.value.filter.gymId)
        vm.onGym("g2")
        runCurrent()
        assertNull(vm.state.value.filter.gymId)

        vm.onQuery("x")
        vm.onPeriod(HistoryPeriod.YEAR)
        vm.clearFilters()
        runCurrent()
        assertEquals(HistoryFilter(), vm.state.value.filter)
        assertFalse(vm.state.value.filterActive)
    }

    @Test fun `the sync indicator follows the sync state`() = runTest(dispatcher) {
        val sync = MutableStateFlow<SyncUiState>(SyncUiState.Pending(2))
        val vm = listViewModel(sync)
        assertEquals(SyncUiState.Pending(2), vm.state.value.sync)
        sync.value = SyncUiState.Offline(3)
        runCurrent()
        assertEquals(SyncUiState.Offline(3), vm.state.value.sync)
    }

    @Test fun `a failed load can be retried`() = runTest(dispatcher) {
        repo.failList = 1
        val vm = listViewModel()
        assertEquals(HistoryLoadStatus.Failed, vm.state.value.status)
        vm.retry()
        runCurrent()
        assertEquals(HistoryLoadStatus.Ready, vm.state.value.status)
    }

    // ---- Detail ----

    @Test fun `delete needs a confirmation and cancelling deletes nothing`() = runTest(dispatcher) {
        val vm = detailViewModel()
        vm.confirmDelete()
        advanceUntilIdle()
        assertTrue(repo.calls.isEmpty())

        vm.requestDelete()
        runCurrent()
        assertTrue((vm.state.value as HistoryDetailUiState.Found).confirmingDelete)
        vm.cancelDelete()
        vm.confirmDelete()
        advanceUntilIdle()

        assertTrue(repo.calls.isEmpty())
        assertFalse(vm.deleted.value)
    }

    @Test fun `confirmed delete runs once and reports deleted`() = runTest(dispatcher) {
        val vm = detailViewModel()
        repo.deleteGate = CompletableDeferred()
        vm.requestDelete()
        vm.confirmDelete()
        vm.confirmDelete()
        runCurrent()
        assertTrue((vm.state.value as HistoryDetailUiState.Found).deleting)

        repo.deleteGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("delete:s1"), repo.calls)
        assertTrue(vm.deleted.value)
    }

    @Test fun `a failed delete is shown and can be retried`() = runTest(dispatcher) {
        val vm = detailViewModel()
        repo.failDelete = true
        vm.requestDelete()
        vm.confirmDelete()
        advanceUntilIdle()
        val failed = vm.state.value as HistoryDetailUiState.Found
        assertTrue(failed.deleteFailed)
        assertFalse(failed.confirmingDelete)
        assertFalse(vm.deleted.value)

        repo.failDelete = false
        vm.requestDelete()
        vm.confirmDelete()
        advanceUntilIdle()
        assertTrue(vm.deleted.value)
    }

    @Test fun `changing gym is one repository call and closes the picker`() = runTest(dispatcher) {
        val vm = detailViewModel()
        vm.openGymPicker()
        runCurrent()
        assertTrue((vm.state.value as HistoryDetailUiState.Found).choosingGym)

        vm.changeGym("g2")
        advanceUntilIdle()

        assertEquals(listOf("gym:g2"), repo.calls)
        assertFalse((vm.state.value as HistoryDetailUiState.Found).choosingGym)
    }

    @Test fun `a failed gym change keeps the picker and shows the error`() = runTest(dispatcher) {
        val vm = detailViewModel()
        repo.failGym = true
        vm.openGymPicker()
        vm.changeGym("g2")
        advanceUntilIdle()
        val state = vm.state.value as HistoryDetailUiState.Found
        assertTrue(state.choosingGym)
        assertTrue(state.gymFailed)
    }

    @Test fun `a deleted session shows not found`() = runTest(dispatcher) {
        val vm = detailViewModel()
        repo.detail.value = null
        runCurrent()
        assertEquals(HistoryDetailUiState.NotFound, vm.state.value)
    }
}
