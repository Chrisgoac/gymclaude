package com.chrisgoac.gymlog.routines

import androidx.lifecycle.SavedStateHandle
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.routine.Routine
import com.chrisgoac.gymlog.domain.routine.RoutineError
import com.chrisgoac.gymlog.domain.routine.RoutineExercise
import com.chrisgoac.gymlog.domain.routine.RoutineField
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineNotFoundException
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.routine.RoutineValidation
import com.chrisgoac.gymlog.domain.routine.RoutineValidationException
import com.chrisgoac.gymlog.domain.routine.RoutineValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class FakeRoutines(initial: List<Routine> = emptyList()) : RoutineRepository {
    val routines = MutableStateFlow(initial)
    val next = MutableStateFlow<String?>(null)
    var created: RoutineInput? = null
    var updated: Pair<String, RoutineInput>? = null
    var reordered: List<String>? = null
    val archivedCalls = mutableListOf<Pair<String, Boolean>>()
    val deleted = mutableListOf<String>()
    var failWith: Exception? = null
    var observeRoutineCalls = 0

    override fun observeRoutines(): Flow<List<RoutineSummary>> = routines.map { all ->
        all.map { RoutineSummary(it.id, it.name, it.description, it.archived, it.exercises.size, emptyList()) }
    }

    override fun observeRoutine(id: String): Flow<Routine?> {
        observeRoutineCalls++
        return routines.map { all -> all.firstOrNull { it.id == id } }
    }

    override fun observeNextRoutineId(): Flow<String?> = next

    override suspend fun create(input: RoutineInput): Routine {
        failWith?.let { throw it }
        validate(input)
        created = input
        return Routine("new", input.name, null, false, emptyList())
    }

    override suspend fun update(id: String, input: RoutineInput): Routine {
        failWith?.let { throw it }
        validate(input)
        updated = id to input
        return routines.value.first { it.id == id }
    }

    override suspend fun reorder(idsInOrder: List<String>) {
        failWith?.let { throw it }
        reordered = idsInOrder
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        failWith?.let { throw it }
        archivedCalls += id to archived
    }

    override suspend fun delete(id: String) {
        failWith?.let { throw it }
        deleted += id
    }

    private fun validate(input: RoutineInput) {
        (RoutineValidator.validate(input) as? RoutineValidation.Invalid)?.let { throw RoutineValidationException(it.issues) }
    }
}

private class FakeExercises(val all: List<Exercise>) : ExerciseRepository {
    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = MutableStateFlow(all.filter(filter::matches))
    override fun observeExercise(id: String): Flow<Exercise?> = MutableStateFlow(all.firstOrNull { it.id == id })
    override suspend fun createCustom(input: ExerciseInput): Exercise = error("unused")
    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise = error("unused")
    override suspend fun deleteCustom(id: String) = error("unused")
}

private fun exercise(id: String, name: String) = Exercise(id, name, MuscleGroup.PECHO, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)

private fun routine(id: String, name: String, archived: Boolean = false, vararg exercises: RoutineExercise) =
    Routine(id, name, null, archived, exercises.toList())

private fun child(id: String, position: Int, name: String = "Press") =
    RoutineExercise(id, "seed-$id", name, position, sets = 3, repsMin = 8, repsMax = 12, restSeconds = 90, notes = "n")

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoutineViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var ids = 0
    private val newId = { "new-${++ids}" }
    private val catalog = FakeExercises(listOf(exercise("seed-press", "Press de banca"), exercise("seed-curl", "Curl con barra")))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun editor(repo: FakeRoutines, id: String? = null, saved: SavedStateHandle = SavedStateHandle()) =
        RoutineEditorViewModel(repo, catalog, saved, id, newId)

    // ---- Editor: create ----

    @Test fun `creating a routine adds exercises with fresh stable keys and saves one input`() = runTest {
        val repo = FakeRoutines()
        val vm = editor(repo)
        vm.onName("  Push ")
        vm.openPicker()
        vm.onPickerQuery("press")
        assertEquals(listOf("seed-press"), vm.pickerResults.value.map { it.id })
        vm.addExercise(vm.pickerResults.value.single())
        vm.closePicker()
        val key = vm.state.value.input.exercises.single().key
        assertEquals("new-1", key)
        vm.onSets(key, "4")
        vm.onRepsMin(key, "6")
        vm.onRepsMax(key, "10")
        vm.onRest(key, "120")

        vm.save()

        assertTrue(vm.state.value.done)
        val saved = repo.created!!
        assertEquals("  Push ", saved.name) // normalization is the repository's job
        assertEquals(listOf("new-1"), saved.exercises.map { it.key })
        assertEquals(listOf("4", "6", "10", "120"), saved.exercises.single().let { listOf(it.sets, it.repsMin, it.repsMax, it.restSeconds) })
    }

    @Test fun `picker is empty while closed`() = runTest {
        val vm = editor(FakeRoutines())
        assertTrue(vm.pickerResults.value.isEmpty())
        vm.openPicker()
        assertEquals(2, vm.pickerResults.value.size)
        vm.closePicker()
        assertTrue(vm.pickerResults.value.isEmpty())
    }

    @Test fun `inverted repetition range blocks the save and the fix clears only that issue`() = runTest {
        val repo = FakeRoutines()
        val vm = editor(repo)
        vm.onName("Push")
        vm.addExercise(catalog.all.first())
        val key = vm.state.value.input.exercises.single().key
        vm.onRepsMin(key, "12")
        vm.onRepsMax(key, "8")

        vm.save()

        assertNull(repo.created)
        assertFalse(vm.state.value.done)
        assertEquals(RoutineError.RANGE_INVERTED, vm.state.value.issue(key, RoutineField.REPS_MIN))
        vm.onRepsMax(key, "14")
        assertNull(vm.state.value.issue(key, RoutineField.REPS_MIN))
        vm.save()
        assertTrue(vm.state.value.done)
    }

    @Test fun `name is required`() = runTest {
        val repo = FakeRoutines()
        val vm = editor(repo)
        vm.save()
        assertEquals(RoutineError.REQUIRED, vm.state.value.issue(null, RoutineField.NAME))
        assertNull(repo.created)
    }

    @Test fun `a failing save keeps the draft and reports it`() = runTest {
        val repo = FakeRoutines().apply { failWith = IllegalStateException("db") }
        val vm = editor(repo)
        vm.onName("Push")
        vm.save()
        assertTrue(vm.state.value.saveFailed)
        assertFalse(vm.state.value.saving)
        assertEquals("Push", vm.state.value.input.name)
        repo.failWith = null
        vm.save()
        assertTrue(vm.state.value.done)
    }

    // ---- Editor: edit ----

    private val existing = routine("r1", "Pull", false, child("c1", 0, "Dominadas"), child("c2", 1, "Remo"), child("c3", 2, "Curl"))

    @Test fun `editing loads the routine and reorder remove keep stable keys`() = runTest {
        val repo = FakeRoutines(listOf(existing))
        val vm = editor(repo, "r1")
        assertEquals(listOf("c1", "c2", "c3"), vm.state.value.input.exercises.map { it.key })

        vm.moveDown("c1")
        assertEquals(listOf("c2", "c1", "c3"), vm.state.value.input.exercises.map { it.key })
        vm.moveUp("c3")
        assertEquals(listOf("c2", "c3", "c1"), vm.state.value.input.exercises.map { it.key })
        vm.moveUp("c2") // already first: no-op
        vm.moveDown("c1") // already last: no-op
        assertEquals(listOf("c2", "c3", "c1"), vm.state.value.input.exercises.map { it.key })
        vm.remove("c3")
        vm.save()

        val (id, input) = repo.updated!!
        assertEquals("r1", id)
        assertEquals(listOf("c2", "c1"), input.exercises.map { it.key })
        assertTrue(vm.state.value.done)
    }

    @Test fun `editing a missing routine is unavailable`() = runTest {
        val vm = editor(FakeRoutines(), "ghost")
        assertTrue(vm.state.value.unavailable)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `archive toggles through the repository and finishes`() = runTest {
        val repo = FakeRoutines(listOf(existing))
        val vm = editor(repo, "r1")
        vm.archiveToggle()
        assertEquals(listOf("r1" to true), repo.archivedCalls)
        assertTrue(vm.state.value.done)

        val archivedRepo = FakeRoutines(listOf(existing.copy(archived = true)))
        val restore = editor(archivedRepo, "r1")
        assertTrue(restore.state.value.archived)
        restore.archiveToggle()
        assertEquals(listOf("r1" to false), archivedRepo.archivedCalls)
    }

    @Test fun `delete needs confirmation and can be cancelled`() = runTest {
        val repo = FakeRoutines(listOf(existing))
        val vm = editor(repo, "r1")
        vm.askDelete()
        assertTrue(vm.state.value.confirmingDelete)
        vm.cancelDelete()
        assertTrue(repo.deleted.isEmpty())
        vm.askDelete()
        vm.confirmDelete()
        assertEquals(listOf("r1"), repo.deleted)
        assertTrue(vm.state.value.done)
    }

    @Test fun `deleting a routine that vanished marks it unavailable`() = runTest {
        val repo = FakeRoutines(listOf(existing)).apply { failWith = RoutineNotFoundException("r1") }
        val vm = editor(repo, "r1")
        vm.askDelete()
        vm.confirmDelete()
        assertTrue(vm.state.value.unavailable)
        assertFalse(vm.state.value.done)
    }

    // ---- Editor: process recreation ----

    /** What the platform would hand back after process death: the saved values re-fed into a fresh handle. */
    private fun recreate(handle: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

    @Test fun `an unsaved create draft survives recreating the view model from saved state`() = runTest {
        val handle = SavedStateHandle()
        val first = editor(FakeRoutines(), saved = handle)
        first.onName("Pierna")
        first.onDescription("Día pesado")
        first.addExercise(catalog.all.first())
        val key = first.state.value.input.exercises.single().key
        first.onSets(key, "5")
        first.onNotes(key, "bajar lento, 3 seg")
        first.openPicker()
        first.onPickerQuery("cu")

        val restored = editor(FakeRoutines(), saved = recreate(handle))

        assertEquals(first.state.value.input, restored.state.value.input)
        assertEquals("Pierna", restored.state.value.input.name)
        assertEquals("bajar lento, 3 seg", restored.state.value.input.exercises.single().notes)
        assertTrue(restored.state.value.pickerOpen)
        assertEquals("cu", restored.state.value.pickerQuery)
        assertFalse(restored.state.value.loading)
    }

    @Test fun `a restored edit draft is not overwritten by the stored routine`() = runTest {
        val handle = SavedStateHandle()
        val first = editor(FakeRoutines(listOf(existing)), "r1", handle)
        first.onName("Pull editado")
        first.moveDown("c1")

        val repo = FakeRoutines(listOf(existing))
        val restored = editor(repo, "r1", recreate(handle))

        assertEquals("Pull editado", restored.state.value.input.name)
        assertEquals(listOf("c2", "c1", "c3"), restored.state.value.input.exercises.map { it.key })
        assertEquals(0, repo.observeRoutineCalls) // no reload
        restored.save()
        assertEquals(listOf("c2", "c1", "c3"), repo.updated!!.second.exercises.map { it.key })
    }

    @Test fun `transient flags are not restored`() = runTest {
        val handle = SavedStateHandle()
        val repo = FakeRoutines().apply { failWith = IllegalStateException("db") }
        val first = editor(repo, saved = handle)
        first.onName("X")
        first.save()
        assertTrue(first.state.value.saveFailed)
        val restored = editor(FakeRoutines(), saved = recreate(handle))
        assertFalse(restored.state.value.saveFailed)
        assertFalse(restored.state.value.done)
    }

    // ---- List ----

    @Test fun `list splits archived and moves routines through reorder keeping archived last`() = runTest {
        val repo = FakeRoutines(listOf(routine("a", "A"), routine("z", "Z", archived = true), routine("b", "B"), routine("c", "C")))
        repo.next.value = "b"
        val vm = RoutineListViewModel(repo)
        val job = launch(dispatcher) { vm.state.collect { } }

        assertEquals(listOf("a", "b", "c"), vm.state.value.active.map { it.id })
        assertEquals(listOf("z"), vm.state.value.archived.map { it.id })
        assertEquals("b", vm.state.value.nextRoutineId)

        vm.moveDown("a")
        assertEquals(listOf("b", "a", "c", "z"), repo.reordered)
        vm.moveUp("c")
        assertEquals(listOf("a", "c", "b", "z"), repo.reordered)
        repo.reordered = null
        vm.moveUp("a") // first: no-op
        vm.moveDown("c") // last: no-op
        assertNull(repo.reordered)
        job.cancel()
    }

    @Test fun `restoring an archived routine goes through the repository and failures surface`() = runTest {
        val repo = FakeRoutines(listOf(routine("z", "Z", archived = true)))
        val vm = RoutineListViewModel(repo)
        val job = launch(dispatcher) { vm.state.collect { } }
        vm.restore("z")
        assertEquals(listOf("z" to false), repo.archivedCalls)
        repo.failWith = IllegalStateException("db")
        vm.restore("z")
        assertTrue(vm.state.value.reorderFailed)
        vm.dismissError()
        assertFalse(vm.state.value.reorderFailed)
        job.cancel()
    }
}
