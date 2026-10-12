package com.chrisgoac.gymlog.exercises

import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseField
import com.chrisgoac.gymlog.domain.exercise.ExerciseFieldError
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseInput
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotEditableException
import com.chrisgoac.gymlog.domain.exercise.ExerciseNotFoundException
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.exercise.ExerciseType
import com.chrisgoac.gymlog.domain.exercise.ExerciseValidation
import com.chrisgoac.gymlog.domain.exercise.ExerciseValidationException
import com.chrisgoac.gymlog.domain.exercise.ExerciseValidator
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

/** In-memory repository enforcing the same contract as the Room one (catalog read-only, typed errors). */
private class FakeExerciseRepository(initial: List<Exercise>) : ExerciseRepository {
    val store = MutableStateFlow(initial)
    var failWith: Exception? = null
    var failReads = false
    var creates = 0
    val queries = mutableListOf<ExerciseFilter>()
    private var next = 0

    override fun observeExercises(filter: ExerciseFilter): Flow<List<Exercise>> = flow {
        queries += filter
        if (failReads) throw IllegalStateException("db")
        emitAll(store.map { all -> all.filter(filter::matches).sortedBy { it.name } })
    }

    override fun observeExercise(id: String): Flow<Exercise?> = store.map { all -> all.firstOrNull { it.id == id } }

    override suspend fun createCustom(input: ExerciseInput): Exercise {
        failWith?.let { throw it }
        val valid = (ExerciseValidator.validate(input) as? ExerciseValidation.Valid)?.value
            ?: throw ExerciseValidationException((ExerciseValidator.validate(input) as ExerciseValidation.Invalid).errors)
        creates++
        val created = Exercise("c${++next}", valid.name, valid.muscleGroup, valid.equipment, valid.type, isCustom = true)
        store.value = store.value + created
        return created
    }

    override suspend fun updateCustom(id: String, input: ExerciseInput): Exercise {
        failWith?.let { throw it }
        val existing = store.value.firstOrNull { it.id == id } ?: throw ExerciseNotFoundException(id)
        if (!existing.isCustom) throw ExerciseNotEditableException(id)
        val updated = existing.copy(name = input.name.trim(), muscleGroup = input.muscleGroup)
        store.value = store.value.map { if (it.id == id) updated else it }
        return updated
    }

    override suspend fun deleteCustom(id: String) {
        failWith?.let { throw it }
        val existing = store.value.firstOrNull { it.id == id } ?: throw ExerciseNotFoundException(id)
        if (!existing.isCustom) throw ExerciseNotEditableException(id)
        store.value = store.value - existing
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseViewModelTest {
    private val catalog = Exercise("seed-press", "Press de banca", MuscleGroup.PECHO, Equipment.BARRA, ExerciseType.COMPUESTO, isCustom = false)
    private val custom = Exercise("c0", "Zancada búlgara", MuscleGroup.CUADRICEPS, Equipment.MANCUERNA, ExerciseType.COMPUESTO, isCustom = true)
    private lateinit var repository: FakeExerciseRepository

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = FakeExerciseRepository(listOf(catalog, custom))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.collect(vm: ExerciseListViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }

    @Test fun `list loads and filters by accent-insensitive query, group and equipment`() = runTest {
        val vm = ExerciseListViewModel(repository)
        collect(vm)
        assertEquals(LoadStatus.Ready, vm.state.value.status)
        assertEquals(listOf("Press de banca", "Zancada búlgara"), vm.state.value.exercises.map { it.name })

        vm.onQueryChange("BULGARA")
        settle()
        assertEquals(listOf("Zancada búlgara"), vm.state.value.exercises.map { it.name })

        vm.clearFilters()
        vm.onMuscleGroupSelected(MuscleGroup.PECHO)
        assertEquals(listOf("Press de banca"), vm.state.value.exercises.map { it.name })
        vm.onMuscleGroupSelected(MuscleGroup.PECHO) // toggles off
        assertNull(vm.state.value.filter.muscleGroup)

        vm.onEquipmentSelected(Equipment.MANCUERNA)
        assertEquals(listOf("Zancada búlgara"), vm.state.value.exercises.map { it.name })
        vm.onQueryChange("press") // combined: no overlap
        settle()
        assertTrue(vm.state.value.exercises.isEmpty())
        assertTrue(vm.state.value.filter.isActive)
    }

    private fun kotlinx.coroutines.test.TestScope.settle() {
        advanceTimeBy(ExerciseListViewModel.QUERY_DEBOUNCE_MILLIS)
        runCurrent()
    }

    @Test fun `typing keeps the previous results (no Loading flash) and queries once per pause`() = runTest {
        val vm = ExerciseListViewModel(repository)
        val states = mutableListOf<ExerciseListUiState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { states += it } }
        val before = repository.queries.size
        states.clear()

        for (typed in listOf("b", "bu", "bul", "bulg")) {
            vm.onQueryChange(typed)
            advanceTimeBy(100)
        }
        // The field echoes every keystroke; the list still shows the old results and never goes back to Loading.
        assertEquals("bulg", vm.state.value.filter.query)
        assertEquals(2, vm.state.value.exercises.size)
        assertTrue(states.none { it.status == LoadStatus.Loading })
        assertEquals(before, repository.queries.size)

        settle()
        assertEquals(listOf("Zancada búlgara"), vm.state.value.exercises.map { it.name })
        assertEquals(listOf("bulg"), repository.queries.drop(before).map { it.query })
        assertTrue(states.none { it.status == LoadStatus.Loading })

        // Chips are not debounced.
        vm.onMuscleGroupSelected(MuscleGroup.PECHO)
        runCurrent()
        assertTrue(vm.state.value.exercises.isEmpty())
        assertEquals(MuscleGroup.PECHO, repository.queries.last().muscleGroup)
    }

    @Test fun `list surfaces a read failure and retry recovers`() = runTest {
        repository.failReads = true
        val vm = ExerciseListViewModel(repository)
        collect(vm)
        assertEquals(LoadStatus.Failed, vm.state.value.status)
        repository.failReads = false
        vm.retry()
        assertEquals(LoadStatus.Ready, vm.state.value.status)
        assertEquals(2, vm.state.value.exercises.size)
    }

    @Test fun `detail shows custom and catalog exercises and reports missing ones`() = runTest {
        val found = ExerciseDetailViewModel(repository, "seed-press")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { found.state.collect {} }
        assertEquals(catalog, (found.state.value as ExerciseDetailUiState.Found).exercise)
        val missing = ExerciseDetailViewModel(repository, "nope")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { missing.state.collect {} }
        assertEquals(ExerciseDetailUiState.NotFound, missing.state.value)
    }

    @Test fun `detail delete removes a custom exercise and signals navigation back`() = runTest {
        val vm = ExerciseDetailViewModel(repository, "c0")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.delete()
        assertTrue(vm.deleted.value)
        assertFalse(repository.store.value.any { it.id == "c0" })
    }

    @Test fun `detail delete failure keeps the exercise and reports it`() = runTest {
        repository.failWith = ExerciseNotEditableException("c0")
        val vm = ExerciseDetailViewModel(repository, "c0")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.delete()
        assertFalse(vm.deleted.value)
        assertTrue((vm.state.value as ExerciseDetailUiState.Found).deleteFailed)
    }

    @Test fun `editor shows field errors and does not call the repository`() = runTest {
        val vm = ExerciseEditorViewModel(repository, null)
        vm.onIncrementKg("0")
        vm.save()
        assertEquals(ExerciseFieldError.NAME_REQUIRED, vm.state.value.errors[ExerciseField.NAME])
        assertEquals(ExerciseFieldError.INCREMENT_NOT_POSITIVE, vm.state.value.errors[ExerciseField.INCREMENT_KG])
        assertEquals(0, repository.creates)
        vm.onName("Remo") // editing a field clears only its own error
        assertNull(vm.state.value.errors[ExerciseField.NAME])
        assertNotNull(vm.state.value.errors[ExerciseField.INCREMENT_KG])
    }

    @Test fun `editor creates a valid exercise exactly once`() = runTest {
        val vm = ExerciseEditorViewModel(repository, null)
        vm.onName("Remo Pendlay")
        vm.onMuscleGroup(MuscleGroup.ESPALDA)
        vm.save()
        vm.save()
        assertEquals(1, repository.creates)
        assertNotNull(vm.state.value.savedId)
        assertTrue(repository.store.value.any { it.name == "Remo Pendlay" && it.isCustom })
    }

    @Test fun `editor loads a custom exercise and saves changes`() = runTest {
        val vm = ExerciseEditorViewModel(repository, "c0")
        assertEquals("Zancada búlgara", vm.state.value.input.name)
        vm.onName("Zancada")
        vm.save()
        assertEquals("c0", vm.state.value.savedId)
        assertEquals("Zancada", repository.store.value.first { it.id == "c0" }.name)
    }

    @Test fun `editor refuses catalog and missing exercises`() = runTest {
        assertTrue(ExerciseEditorViewModel(repository, "seed-press").state.value.unavailable)
        assertTrue(ExerciseEditorViewModel(repository, "ghost").state.value.unavailable)
    }

    @Test fun `editor reports an unexpected save failure and can retry`() = runTest {
        val vm = ExerciseEditorViewModel(repository, null)
        vm.onName("Remo")
        repository.failWith = IllegalStateException("disk")
        vm.save()
        assertTrue(vm.state.value.saveFailed)
        assertNull(vm.state.value.savedId)
        repository.failWith = null
        vm.save()
        assertNotNull(vm.state.value.savedId)
    }
}
