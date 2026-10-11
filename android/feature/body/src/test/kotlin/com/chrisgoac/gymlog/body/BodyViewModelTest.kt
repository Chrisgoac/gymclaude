package com.chrisgoac.gymlog.body

import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.domain.body.BodyDateError
import com.chrisgoac.gymlog.domain.body.BodyValueError
import com.chrisgoac.gymlog.domain.body.CustomMetricProblem
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.domain.time.Period
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
import java.time.LocalDate
import java.time.ZoneId

private val MADRID = ZoneId.of("Europe/Madrid")
private val TODAY = LocalDate.of(2026, 8, 12)
private val NOW = TODAY.atTime(10, 0).atZone(MADRID).toInstant().toEpochMilli()
private fun day(offset: Long, hour: Int = 0) = TODAY.minusDays(offset).atTime(hour, 0).atZone(MADRID).toInstant().toEpochMilli()

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BodyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeBodyRepository()
    private val unit = MutableStateFlow(WeightUnit.KG)
    private var saved = SavedStateHandle()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = BodyViewModel(repo, saved, { BodyClock(NOW, MADRID) }, unit)

    private fun TestScope.started(vm: BodyViewModel = viewModel()): BodyViewModel {
        backgroundScope.launch(dispatcher) { vm.state.collect {} }
        advanceUntilIdle()
        return vm
    }

    @Test fun `empty account shows the weight metric ready with no history`() = runTest(dispatcher) {
        val state = started().state.value
        assertEquals(BodyStatus.Ready, state.status)
        assertEquals("peso", state.selected.key)
        assertTrue(state.history.isEmpty())
        assertNull(state.summary)
        assertEquals(10, state.options.size)
        assertFalse(state.options.any { it.hasData })
    }

    @Test fun `summary has latest, change against previous and change since period start`() = runTest(dispatcher) {
        repo.seed("peso", 84.0, day(100)) // outside the 3 month default
        repo.seed("peso", 82.0, day(60))
        repo.seed("peso", 81.0, day(20))
        repo.seed("peso", 80.5, day(1))
        val state = started().state.value
        val summary = state.summary!!
        assertEquals(80.5, summary.latest.value, 0.0)
        assertEquals(-0.5, summary.previousDelta!!, 1e-9)
        assertEquals(82.0, summary.periodFirst!!, 0.0)
        assertEquals(-1.5, summary.periodDelta!!, 1e-9)
        assertEquals(listOf(82.0, 81.0, 80.5), state.series.map { it.value })
        assertEquals(listOf(80.5, 81.0, 82.0, 84.0), state.history.map { it.value })
    }

    @Test fun `period change recomputes in memory without a loading flash`() = runTest(dispatcher) {
        repo.seed("peso", 84.0, day(100))
        repo.seed("peso", 80.0, day(1))
        val vm = started()
        assertNull(vm.state.value.summary!!.periodDelta) // one reading in 3 months
        val seen = mutableListOf<BodyStatus>()
        backgroundScope.launch(dispatcher) { vm.state.collect { seen += it.status } }
        vm.onPeriod(Period.ALL)
        advanceUntilIdle()
        assertEquals(-4.0, vm.state.value.summary!!.periodDelta!!, 1e-9)
        assertEquals(setOf(BodyStatus.Ready), seen.toSet())
        assertEquals("todo", saved.get<String>("period"))
    }

    @Test fun `same day readings stay separate and ordered by write order`() = runTest(dispatcher) {
        repo.seed("peso", 81.0, day(1, 8))
        repo.seed("peso", 80.6, day(1, 8))
        val vm = started()
        assertEquals(listOf(81.0, 80.6), vm.state.value.series.map { it.value })
        assertEquals(-0.4, vm.state.value.summary!!.previousDelta!!, 1e-9)
        assertEquals(2, vm.state.value.history.size)
    }

    @Test fun `selecting a metric loads its own series and is restored after process death`() = runTest(dispatcher) {
        repo.seed("peso", 80.0, day(1))
        repo.seed("cintura", 90.0, day(2))
        repo.seed("cintura", 89.0, day(1))
        val vm = started()
        vm.onMetric("cintura")
        advanceUntilIdle()
        assertEquals("cintura", vm.state.value.selected.key)
        assertEquals(listOf(89.0, 90.0), vm.state.value.history.map { it.value })
        assertEquals(setOf("peso", "cintura"), vm.state.value.options.filter { it.hasData }.map { it.def.key }.toSet())

        val restored = started(BodyViewModel(repo, SavedStateHandle(mapOf("metric" to "cintura", "period" to "ano")), { BodyClock(NOW, MADRID) }, unit))
        assertEquals("cintura", restored.state.value.selected.key)
        assertEquals(Period.YEAR, restored.state.value.period)
    }

    @Test fun `a vanished metric falls back to weight`() = runTest(dispatcher) {
        saved = SavedStateHandle(mapOf("metric" to "ya-no-existe"))
        assertEquals("peso", started().state.value.selected.key)
    }

    @Test fun `unknown synced metrics with data are listed with their key as label`() = runTest(dispatcher) {
        repo.seed("grasa", 18.0, day(1))
        val state = started().state.value
        val option = state.options.last()
        assertEquals("grasa", option.def.label)
        assertTrue(option.hasData)
    }

    @Test fun `adding a reading validates strictly then writes once and closes the editor`() = runTest(dispatcher) {
        val vm = started()
        vm.onAdd()
        advanceUntilIdle()
        val editor = vm.state.value.editor!!
        assertEquals(TODAY, editor.date)
        assertNull(editor.entryId)

        for (bad in listOf("", "Infinity", "0x50", "1e2", "-3")) {
            vm.onEditorValue(bad)
            vm.onSave()
            advanceUntilIdle()
            assertNotNull(bad, (vm.state.value.editor!!.problem as? EditorProblem.Value))
        }
        vm.onEditorValue("500")
        vm.onSave()
        advanceUntilIdle()
        assertEquals(BodyValueError.TOO_LARGE, (vm.state.value.editor!!.problem as EditorProblem.Value).error)
        assertTrue(repo.writes.isEmpty())

        vm.onEditorValue("80,5")
        vm.onSave()
        advanceUntilIdle()
        assertNull(vm.state.value.editor)
        assertEquals(listOf("add:peso:80.5:$NOW"), repo.writes) // today keeps the real time
        assertEquals(80.5, vm.state.value.summary!!.latest.value, 0.0)
    }

    @Test fun `a past day is stored at local midnight and a future day is refused`() = runTest(dispatcher) {
        val vm = started()
        vm.onAdd()
        vm.onEditorValue("80")
        vm.onEditorDate(TODAY.minusDays(3))
        vm.onSave()
        advanceUntilIdle()
        assertEquals(listOf("add:peso:80.0:${day(3)}"), repo.writes)

        vm.onAdd()
        vm.onEditorValue("80")
        vm.onEditorDate(TODAY.plusDays(1))
        vm.onSave()
        advanceUntilIdle()
        assertEquals(BodyDateError.IN_FUTURE, (vm.state.value.editor!!.problem as EditorProblem.Date).error)
        assertEquals(1, repo.writes.size)
    }

    @Test fun `editing keeps the original instant when the day is unchanged and cancelling writes nothing`() = runTest(dispatcher) {
        val original = day(2, 7)
        val id = repo.seed("peso", 80.0, original)
        val vm = started()
        vm.onEdit(vm.state.value.history.single())
        advanceUntilIdle()
        assertEquals("80", vm.state.value.editor!!.valueText)
        assertEquals(TODAY.minusDays(2), vm.state.value.editor!!.date)
        vm.onEditorValue("79,5")
        vm.onEditorCancel()
        advanceUntilIdle()
        assertTrue(repo.writes.isEmpty())
        assertNull(vm.state.value.editor)

        vm.onEdit(vm.state.value.history.single())
        vm.onEditorValue("79,5")
        vm.onSave()
        advanceUntilIdle()
        assertEquals(listOf("update:$id:79.5:$original"), repo.writes)
        assertNull(vm.state.value.editor)
    }

    @Test fun `editing a reading deleted elsewhere keeps the editor open with a message`() = runTest(dispatcher) {
        repo.seed("peso", 80.0, day(2))
        val vm = started()
        vm.onEdit(vm.state.value.history.single())
        repo.rows.value = emptyList()
        advanceUntilIdle()
        vm.onSave()
        advanceUntilIdle()
        assertEquals(EditorProblem.Missing, vm.state.value.editor!!.problem)
    }

    @Test fun `a failing save keeps the draft and can be retried`() = runTest(dispatcher) {
        val vm = started()
        vm.onAdd()
        vm.onEditorValue("80")
        repo.failWrites = true
        vm.onSave()
        advanceUntilIdle()
        val editor = vm.state.value.editor!!
        assertEquals(EditorProblem.SaveFailed, editor.problem)
        assertEquals("80", editor.valueText)
        assertFalse(editor.saving)
        repo.failWrites = false
        vm.onSave()
        advanceUntilIdle()
        assertNull(vm.state.value.editor)
        assertEquals(1, repo.writes.size)
    }

    @Test fun `delete asks first, then tombstones, and failures are reported`() = runTest(dispatcher) {
        val id = repo.seed("peso", 80.0, day(1))
        val vm = started()
        vm.onDelete(vm.state.value.history.single())
        advanceUntilIdle()
        assertNotNull(vm.state.value.pendingDelete)
        vm.onCancelDelete()
        advanceUntilIdle()
        assertNull(vm.state.value.pendingDelete)
        assertTrue(repo.writes.isEmpty())

        repo.failWrites = true
        vm.onDelete(vm.state.value.history.single())
        vm.onConfirmDelete()
        advanceUntilIdle()
        assertTrue(vm.state.value.actionFailed)
        vm.onDismissFailure()
        advanceUntilIdle()
        assertFalse(vm.state.value.actionFailed)

        repo.failWrites = false
        vm.onDelete(vm.state.value.history.single())
        vm.onConfirmDelete()
        advanceUntilIdle()
        assertEquals(listOf("delete:$id"), repo.writes)
        assertTrue(vm.state.value.history.isEmpty())
    }

    @Test fun `pounds are shown converted and typed pounds are stored as kilograms`() = runTest(dispatcher) {
        repo.seed("peso", 80.0, day(1))
        unit.value = WeightUnit.LB
        val vm = started()
        assertEquals(WeightUnit.LB, vm.state.value.weightUnit)
        vm.onEdit(vm.state.value.history.single())
        advanceUntilIdle()
        assertEquals("176,37", vm.state.value.editor!!.valueText) // 80 kg = 176.37 lb, shown to 2 decimals
        vm.onEditorValue("176,37")
        vm.onSave()
        advanceUntilIdle()
        assertEquals(80.0, repo.rows.value.single().value, 0.01)
        // The stored value is always kilograms: 1 lb typed is below the 20 kg floor.
        vm.onAdd()
        vm.onEditorValue("1")
        vm.onSave()
        advanceUntilIdle()
        assertEquals(BodyValueError.TOO_SMALL, (vm.state.value.editor!!.problem as EditorProblem.Value).error)
    }

    @Test fun `custom metrics are validated, created once and selected`() = runTest(dispatcher) {
        val vm = started()
        vm.onNewMetric()
        vm.onCustomName("peso")
        vm.onCustomSave()
        advanceUntilIdle()
        assertEquals(CustomMetricProblem.RESERVED, vm.state.value.customDialog!!.problem)

        vm.onCustomName("Grasa corporal")
        vm.onCustomUnit("%")
        vm.onCustomSave()
        advanceUntilIdle()
        assertNull(vm.state.value.customDialog)
        assertEquals("grasa-corporal", vm.state.value.selected.key)
        assertEquals("%", vm.state.value.selected.unit)
        assertEquals(listOf("custom:grasa-corporal"), repo.writes)

        vm.onAdd()
        vm.onEditorValue("0")
        vm.onSave()
        advanceUntilIdle()
        assertEquals(BodyValueError.TOO_SMALL, (vm.state.value.editor!!.problem as EditorProblem.Value).error)
        vm.onEditorValue("18,5")
        vm.onSave()
        advanceUntilIdle()
        assertEquals("add:grasa-corporal:18.5:$NOW", repo.writes.last())
    }

    @Test fun `a failing read reports failure and retry recovers`() = runTest(dispatcher) {
        repo.failReads = IllegalStateException("boom")
        val vm = started()
        assertEquals(BodyStatus.Failed, vm.state.value.status)
        repo.failReads = null
        vm.retry()
        advanceUntilIdle()
        assertEquals(BodyStatus.Ready, vm.state.value.status)
    }
}
