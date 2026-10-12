package com.chrisgoac.gymlog.progress

import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.stats.BalanceRow
import com.chrisgoac.gymlog.domain.stats.ExerciseRecords
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.stats.PeriodSummary
import com.chrisgoac.gymlog.domain.stats.ProgressPoint
import com.chrisgoac.gymlog.domain.stats.RecordKind
import com.chrisgoac.gymlog.domain.stats.Stall
import com.chrisgoac.gymlog.domain.stats.StatsFilter
import com.chrisgoac.gymlog.domain.stats.VolumeOverview
import com.chrisgoac.gymlog.domain.stats.WeekRecord
import com.chrisgoac.gymlog.domain.stats.WeeklyInsight
import com.chrisgoac.gymlog.domain.stats.WeeklySummary
import com.chrisgoac.gymlog.domain.stats.WeeklyVolumeDelta
import com.chrisgoac.gymlog.domain.time.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** SavedStateHandle is Bundle-backed on Android, so these run on Robolectric. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProgressViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val stats = FakeStats()
    private val pro = FakeEntitlements()
    private val exercises = FakeExercises(listOf(exercise("e1", "Press banca"), exercise("e2", "Press militar", MuscleGroup.HOMBROS)))
    private val clock = ProgressClock(1_800_000_000_000L, ZoneId.of("Europe/Madrid"))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        ticks: Flow<Unit> = flowOf(Unit),
        gyms: GymsOnly = GymsOnly(),
    ): ProgressViewModel {
        val vm = ProgressViewModel(stats, gyms, exercises, pro, handle, ticks, { clock })
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun trained() {
        stats.volume.value = VolumeOverview(PeriodSummary(4, 12_000.0), listOf(WeeklyVolumeDelta(1, 6_000.0, null), WeeklyVolumeDelta(2, 6_000.0, 0)))
        stats.weekly.value = WeeklyInsight(WeeklySummary(2, 6_000.0, 5_000.0, 20), listOf(WeekRecord("e1", "Press banca", RecordKind.WEIGHT)))
    }

    @Test
    fun `starts loading then shows data with the defaults`() = runTest(dispatcher) {
        trained()
        val vm = viewModel()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals(ProgressStatus.Ready, state.status)
        assertEquals(Period.FOUR_WEEKS, state.period)
        assertEquals(GymFilter.All, state.gym)
        assertEquals(4, state.data!!.volume.summary.sessions)
        assertFalse(state.data!!.isEmpty)
        assertEquals(clock, state.clock)
        assertEquals(listOf("Casa", "Fitness"), state.gyms.map { it.name })
    }

    @Test
    fun `changing the period and gym re-queries with the new filter and remembers it`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        advanceUntilIdle()

        vm.onPeriod(Period.YEAR)
        advanceUntilIdle()
        vm.onGym(GymFilter.Gym("g2"))
        advanceUntilIdle()

        assertEquals(StatsFilter(Period.YEAR, GymFilter.Gym("g2")), stats.volumeFilters.last())
        assertEquals(GymFilter.Gym("g2"), stats.weeklyGyms.last())
        assertEquals(Period.YEAR, vm.state.value.period)
        assertEquals(GymFilter.Gym("g2"), vm.state.value.gym)

        // A new ViewModel on the same handle (process death / config restore) starts on the saved filter.
        val restored = viewModel(handle)
        advanceUntilIdle()
        assertEquals(Period.YEAR, restored.state.value.period)
        assertEquals(GymFilter.Gym("g2"), restored.state.value.gym)
        assertEquals(StatsFilter(Period.YEAR, GymFilter.Gym("g2")), stats.volumeFilters.last())
    }

    @Test
    fun `a saved gym that no longer exists falls back to every gym`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("gym" to "gone"))
        val vm = viewModel(handle)
        advanceUntilIdle()
        assertEquals(GymFilter.All, vm.state.value.gym)
        assertEquals(GymFilter.All, stats.weeklyGyms.last())
        assertEquals(GymFilter.ALL_WIRE, handle.get<String>("gym"))
    }

    @Test
    fun `the selected gym deleted while the screen is open falls back to every gym`() = runTest(dispatcher) {
        val gyms = GymsOnly()
        val vm = viewModel(gyms = gyms)
        vm.onGym(GymFilter.Gym("g2"))
        advanceUntilIdle()
        assertEquals(GymFilter.Gym("g2"), vm.state.value.gym)

        gyms.gyms.value = gyms.gyms.value.filterNot { it.id == "g2" }
        advanceUntilIdle()
        assertEquals(GymFilter.All, vm.state.value.gym)
        assertEquals(GymFilter.All, stats.weeklyGyms.last())
    }

    @Test
    fun `selecting every gym again clears the gym filter`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onGym(GymFilter.Gym("g1"))
        advanceUntilIdle()
        vm.onGym(GymFilter.All)
        advanceUntilIdle()
        assertEquals(GymFilter.All, vm.state.value.gym)
        assertEquals(GymFilter.All, stats.weeklyGyms.last())
    }

    @Test
    fun `no history at all is the empty state`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(ProgressStatus.Ready, vm.state.value.status)
        assertTrue(vm.state.value.data!!.isEmpty)
    }

    @Test
    fun `partial data keeps the screen alive`() = runTest(dispatcher) {
        // Nothing in the selected period, but a session this week and a stall: not empty.
        stats.weekly.value = WeeklyInsight(WeeklySummary(1, 500.0, 0.0, null), emptyList())
        stats.stalls.value = listOf(Stall("e1", "Press banca", 4, null))
        val vm = viewModel()
        advanceUntilIdle()
        val data = vm.state.value.data!!
        assertFalse(data.isEmpty)
        assertEquals(0, data.volume.summary.sessions)
        assertEquals(1, data.stalls.size)
    }

    @Test
    fun `balance rows alone are not empty`() = runTest(dispatcher) {
        stats.balance.value = listOf(BalanceRow(MuscleGroup.PECHO, 0.0, 12, false, null))
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.data!!.isEmpty)
    }

    @Test
    fun `a deleted workout or a historical edit refreshes the numbers once`() = runTest(dispatcher) {
        trained()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(4, vm.state.value.data!!.volume.summary.sessions)

        // Delete one workout, then edit another: the repository re-emits.
        stats.volume.value = VolumeOverview(PeriodSummary(3, 9_000.0), emptyList())
        advanceUntilIdle()
        assertEquals(3, vm.state.value.data!!.volume.summary.sessions)

        stats.volume.value = VolumeOverview(PeriodSummary(3, 9_500.0), emptyList())
        advanceUntilIdle()
        assertEquals(9_500.0, vm.state.value.data!!.volume.summary.volume, 0.0)
        assertEquals("a refresh must not re-subscribe", 1, stats.volumeSubscriptions)
    }

    @Test
    fun `a burst of writes is recomputed once per interval with the latest data`() = runTest(dispatcher) {
        val seen = mutableListOf<Int>()
        val vm = ProgressViewModel(stats, GymsOnly(), exercises, pro, SavedStateHandle(), flowOf(Unit), { clock })
        backgroundScope.launch(dispatcher) { vm.state.collect { s -> s.data?.let { seen += it.volume.summary.sessions } } }
        advanceUntilIdle()
        seen.clear()

        (1..20).forEach { stats.volume.value = VolumeOverview(PeriodSummary(it, it * 100.0), emptyList()) }
        runCurrent()
        advanceTimeBy(ProgressViewModel.RECOMPUTE_INTERVAL_MILLIS + 1)
        runCurrent()
        advanceUntilIdle()

        assertEquals(20, seen.last())
        assertTrue("20 writes must collapse, saw $seen", seen.size <= 3)
    }

    @Test
    fun `the local day rolling over recomputes without any write`() = runTest(dispatcher) {
        val ticks = MutableSharedFlow<Unit>(replay = 1).also { it.tryEmit(Unit) }
        val vm = viewModel(ticks = ticks)
        advanceUntilIdle()
        assertEquals(1, stats.volumeSubscriptions)

        ticks.emit(Unit)
        advanceUntilIdle()
        assertEquals(2, stats.volumeSubscriptions)
        assertEquals(ProgressStatus.Ready, vm.state.value.status)
    }

    @Test
    fun `a failing read shows the error and retry recovers`() = runTest(dispatcher) {
        stats.failVolume = IllegalStateException("boom")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(ProgressStatus.Failed, vm.state.value.status)
        assertNull(vm.state.value.data)

        stats.failVolume = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(ProgressStatus.Ready, vm.state.value.status)
    }

    @Test
    fun `the intelligent report follows the Pro entitlement`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertFalse(vm.state.value.isPro)

        pro.pro.value = true
        advanceUntilIdle()
        assertTrue(vm.state.value.isPro)

        pro.pro.value = false
        advanceUntilIdle()
        assertFalse(vm.state.value.isPro)
    }

    @Test
    fun `exercise search is debounced and capped`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.onExerciseQuery("pre")
        vm.onExerciseQuery("press")
        runCurrent()
        assertTrue(vm.state.value.exerciseResults.isEmpty())
        advanceTimeBy(ProgressViewModel.QUERY_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(listOf("Press banca", "Press militar"), vm.state.value.exerciseResults.map { it.name })
        assertEquals("only the settled query is searched", 1, exercises.filters.count { it.query.isNotBlank() })

        vm.onExerciseQuery("")
        advanceUntilIdle()
        assertTrue(vm.state.value.exerciseResults.isEmpty())
    }

    // ---- Exercise drill-down ----

    private fun TestScope.exerciseViewModel(handle: SavedStateHandle = SavedStateHandle()): ExerciseProgressViewModel {
        val vm = ExerciseProgressViewModel("e1", stats, exercises, GymsOnly(), handle, flowOf(Unit), { clock }, StatsFilter(Period.THREE_MONTHS, GymFilter.Gym("g1")))
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        runCurrent()
        return vm
    }

    @Test
    fun `drill-down starts from the carried filter and shows series and records`() = runTest(dispatcher) {
        stats.series.value = listOf(ProgressPoint(1, 80.0, 90.0, 800.0), ProgressPoint(2, 85.0, 95.0, 850.0))
        stats.records.value = ExerciseRecords(85.0, 95.0)
        stats.oneRepMax.value = 96.0
        val vm = exerciseViewModel()
        advanceUntilIdle()
        val state = vm.state.value
        assertEquals("Press banca", state.exerciseName)
        assertEquals(Period.THREE_MONTHS, state.period)
        assertEquals(GymFilter.Gym("g1"), state.gym)
        assertEquals(2, state.series.size)
        assertEquals(96.0, state.estimatedOneRepMax!!, 0.0)
        assertEquals(StatsFilter(Period.THREE_MONTHS, GymFilter.Gym("g1")), stats.seriesFilters.last())
    }

    @Test
    fun `drill-down period, gym and metric changes re-query and are remembered`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val vm = exerciseViewModel(handle)
        advanceUntilIdle()
        vm.onPeriod(Period.ALL)
        vm.onGym(GymFilter.All)
        vm.onMetric(ProgressMetric.VOLUME)
        advanceUntilIdle()
        assertEquals(StatsFilter(Period.ALL, GymFilter.All), stats.seriesFilters.last())
        assertEquals(ProgressMetric.VOLUME, vm.state.value.metric)

        val restored = exerciseViewModel(handle)
        advanceUntilIdle()
        assertEquals(Period.ALL, restored.state.value.period)
        assertEquals(GymFilter.All, restored.state.value.gym)
        assertEquals(ProgressMetric.VOLUME, restored.state.value.metric)
    }

    @Test
    fun `an exercise without history has no records`() = runTest(dispatcher) {
        val vm = exerciseViewModel()
        advanceUntilIdle()
        assertEquals(ProgressStatus.Ready, vm.state.value.status)
        assertNull(vm.state.value.records)
        assertTrue(vm.state.value.series.isEmpty())
        assertNotNull(vm.state.value.exerciseName)
    }
}
