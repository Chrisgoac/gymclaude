package com.chrisgoac.gymlog.routines

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.exercise.Exercise
import com.chrisgoac.gymlog.domain.exercise.ExerciseFilter
import com.chrisgoac.gymlog.domain.exercise.ExerciseRepository
import com.chrisgoac.gymlog.domain.routine.RoutineError
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseConflictException
import com.chrisgoac.gymlog.domain.routine.RoutineExerciseInput
import com.chrisgoac.gymlog.domain.routine.RoutineField
import com.chrisgoac.gymlog.domain.routine.RoutineInput
import com.chrisgoac.gymlog.domain.routine.RoutineIssue
import com.chrisgoac.gymlog.domain.routine.RoutineNotFoundException
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.routine.RoutineValidation
import com.chrisgoac.gymlog.domain.routine.RoutineValidationException
import com.chrisgoac.gymlog.domain.routine.RoutineValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.UUID

private const val STOP_TIMEOUT_MILLIS = 5_000L

enum class LoadStatus { Loading, Ready, Failed }

// ---- List ----

data class RoutineListUiState(
    val status: LoadStatus = LoadStatus.Loading,
    val active: List<RoutineSummary> = emptyList(),
    val archived: List<RoutineSummary> = emptyList(),
    /** The routine the rotation suggests training next, if any. */
    val nextRoutineId: String? = null,
    val reorderFailed: Boolean = false,
)

class RoutineListViewModel(private val repository: RoutineRepository) : ViewModel() {
    private val attempt = MutableStateFlow(0)
    private val reorderFailed = MutableStateFlow(false)

    val state: StateFlow<RoutineListUiState> = attempt
        .flatMapLatest {
            combine(repository.observeRoutines(), repository.observeNextRoutineId(), reorderFailed) { routines, next, failed ->
                RoutineListUiState(
                    status = LoadStatus.Ready,
                    active = routines.filterNot { it.archived },
                    archived = routines.filter { it.archived },
                    nextRoutineId = next,
                    reorderFailed = failed,
                )
            }.onStart { emit(RoutineListUiState(LoadStatus.Loading)) }
                .catch { emit(RoutineListUiState(LoadStatus.Failed)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RoutineListUiState())

    fun retry() = attempt.update { it + 1 }

    fun moveUp(id: String) = move(id, -1)

    fun moveDown(id: String) = move(id, +1)

    /** Swaps [id] with its neighbour among the active routines; archived ones keep their relative place. */
    private fun move(id: String, delta: Int) {
        val current = state.value
        val index = current.active.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in current.active.indices) return
        val reordered = current.active.map { it.id }.toMutableList().apply { this[index] = this[target].also { this[target] = this[index] } }
        reorderFailed.value = false
        viewModelScope.launch {
            try {
                repository.reorder(reordered + current.archived.map { it.id })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reorderFailed.value = true
            }
        }
    }

    fun restore(id: String) {
        viewModelScope.launch {
            try {
                repository.setArchived(id, false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                reorderFailed.value = true
            }
        }
    }

    fun dismissError() {
        reorderFailed.value = false
    }
}

// ---- Editor ----

data class RoutineEditorUiState(
    /** Null while creating. */
    val routineId: String? = null,
    val input: RoutineInput = RoutineInput(),
    val archived: Boolean = false,
    val issues: List<RoutineIssue> = emptyList(),
    val loading: Boolean = false,
    /** Editing a routine that is gone. */
    val unavailable: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val confirmingDelete: Boolean = false,
    val pickerOpen: Boolean = false,
    val pickerQuery: String = "",
    /** The editor finished (saved, archived/restored or deleted): leave the screen. */
    val done: Boolean = false,
) {
    fun issue(exerciseKey: String?, field: RoutineField): RoutineError? =
        issues.firstOrNull { it.exerciseKey == exerciseKey && it.field == field }?.error
}

/**
 * Create (routineId == null) or edit a routine. The draft lives in [savedState] on every change,
 * so it survives process death and configuration changes; a restored draft is never overwritten
 * by the stored routine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditorViewModel(
    private val repository: RoutineRepository,
    exercises: ExerciseRepository,
    private val savedState: SavedStateHandle,
    private val routineId: String?,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(restoreDraft() ?: RoutineEditorUiState(routineId = routineId, loading = routineId != null))
    val state: StateFlow<RoutineEditorUiState> = _state

    /** Exercises matching the picker query; empty while the picker is closed. */
    val pickerResults: StateFlow<List<Exercise>> = _state
        .map { if (it.pickerOpen) it.pickerQuery else null }
        .flatMapLatest { query -> if (query == null) flowOf(emptyList()) else exercises.observeExercises(ExerciseFilter(query = query)) }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        if (_state.value.loading) loadExisting(checkNotNull(routineId))
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val routine = try {
                repository.observeRoutine(id).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            update {
                if (routine == null) it.copy(loading = false, unavailable = true)
                else it.copy(loading = false, input = RoutineInput.from(routine), archived = routine.archived)
            }
        }
    }

    // ---- Draft edits ----

    fun onName(value: String) = editInput { it.copy(name = value) }

    fun onDescription(value: String) = editInput { it.copy(description = value) }

    fun onExercise(key: String, transform: (RoutineExerciseInput) -> RoutineExerciseInput) =
        editInput { input -> input.copy(exercises = input.exercises.map { if (it.key == key) transform(it) else it }) }

    fun onSets(key: String, value: String) = onExercise(key) { it.copy(sets = value) }
    fun onRepsMin(key: String, value: String) = onExercise(key) { it.copy(repsMin = value) }
    fun onRepsMax(key: String, value: String) = onExercise(key) { it.copy(repsMax = value) }
    fun onRest(key: String, value: String) = onExercise(key) { it.copy(restSeconds = value) }
    fun onNotes(key: String, value: String) = onExercise(key) { it.copy(notes = value) }

    fun moveUp(key: String) = move(key, -1)

    fun moveDown(key: String) = move(key, +1)

    private fun move(key: String, delta: Int) = editInput { input ->
        val index = input.exercises.indexOfFirst { it.key == key }
        val target = index + delta
        if (index < 0 || target !in input.exercises.indices) {
            input
        } else {
            input.copy(exercises = input.exercises.toMutableList().apply { this[index] = this[target].also { this[target] = this[index] } })
        }
    }

    fun remove(key: String) = editInput { input -> input.copy(exercises = input.exercises.filterNot { it.key == key }) }

    /** Adds [exercise] at the end with a fresh stable key; the picker stays open for more. */
    fun addExercise(exercise: Exercise) = editInput { input ->
        input.copy(exercises = input.exercises + RoutineExerciseInput(key = newId(), exerciseId = exercise.id, exerciseName = exercise.name))
    }

    fun openPicker() = update { it.copy(pickerOpen = true, pickerQuery = "") }

    fun closePicker() = update { it.copy(pickerOpen = false, pickerQuery = "") }

    fun onPickerQuery(value: String) = update { it.copy(pickerQuery = value) }

    private fun editInput(transform: (RoutineInput) -> RoutineInput) {
        update { current ->
            val next = transform(current.input)
            // Editing clears the issues of what changed; the others stay until the next save.
            val stillValid = current.issues.filter { issue -> unchanged(current.input, next, issue) }
            current.copy(input = next, issues = stillValid, saveFailed = false)
        }
    }

    private fun unchanged(before: RoutineInput, after: RoutineInput, issue: RoutineIssue): Boolean {
        if (issue.exerciseKey == null) {
            return when (issue.field) {
                RoutineField.NAME -> before.name == after.name
                RoutineField.DESCRIPTION -> before.description == after.description
                else -> true
            }
        }
        val a = before.exercises.firstOrNull { it.key == issue.exerciseKey }
        val b = after.exercises.firstOrNull { it.key == issue.exerciseKey }
        return a == b
    }

    // ---- Actions ----

    fun save() {
        val current = _state.value
        if (current.saving || current.loading || current.unavailable || current.done) return
        val validation = RoutineValidator.validate(current.input)
        if (validation is RoutineValidation.Invalid) {
            update { it.copy(issues = validation.issues) }
            return
        }
        launchAction {
            if (routineId == null) repository.create(current.input) else repository.update(routineId, current.input)
        }
    }

    fun archiveToggle() {
        val id = routineId ?: return
        val current = _state.value
        if (current.saving || current.unavailable || current.done) return
        launchAction { repository.setArchived(id, !current.archived) }
    }

    fun askDelete() = update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = update { it.copy(confirmingDelete = false) }

    fun confirmDelete() {
        val id = routineId ?: return
        val current = _state.value
        if (current.saving || current.unavailable || current.done) return
        launchAction { repository.delete(id) }
    }

    private fun launchAction(action: suspend () -> Unit) {
        update { it.copy(saving = true, saveFailed = false, issues = emptyList()) }
        viewModelScope.launch {
            try {
                action()
                update { it.copy(saving = false, done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutineValidationException) {
                update { it.copy(saving = false, issues = e.issues) }
            } catch (_: RoutineNotFoundException) {
                update { it.copy(saving = false, unavailable = true, confirmingDelete = false) }
            } catch (_: RoutineExerciseConflictException) {
                update { it.copy(saving = false, saveFailed = true) }
            } catch (_: Exception) {
                update { it.copy(saving = false, saveFailed = true, confirmingDelete = false) }
            }
        }
    }

    // ---- Draft persistence ----

    private fun update(transform: (RoutineEditorUiState) -> RoutineEditorUiState) {
        val next = _state.updateAndGet(transform)
        persist(next)
    }

    /** Only draft-relevant values are saved: never the transient flags (saving, done, errors). */
    private fun persist(state: RoutineEditorUiState) {
        if (state.loading || state.unavailable) return
        savedState[KEY_NAME] = state.input.name
        savedState[KEY_DESCRIPTION] = state.input.description
        savedState[KEY_EXERCISES] = ArrayList(state.input.exercises.flatMap { it.toFields() })
        savedState[KEY_ARCHIVED] = state.archived
        savedState[KEY_PICKER_OPEN] = state.pickerOpen
        savedState[KEY_PICKER_QUERY] = state.pickerQuery
        savedState[KEY_READY] = true
    }

    private fun restoreDraft(): RoutineEditorUiState? {
        if (savedState.get<Boolean>(KEY_READY) != true) return null
        val fields = savedState.get<ArrayList<String>>(KEY_EXERCISES).orEmpty()
        return RoutineEditorUiState(
            routineId = routineId,
            input = RoutineInput(
                name = savedState.get<String>(KEY_NAME).orEmpty(),
                description = savedState.get<String>(KEY_DESCRIPTION).orEmpty(),
                exercises = fields.chunked(EXERCISE_STRIDE).filter { it.size == EXERCISE_STRIDE }.map { it.toExerciseInput() },
            ),
            archived = savedState.get<Boolean>(KEY_ARCHIVED) ?: false,
            pickerOpen = savedState.get<Boolean>(KEY_PICKER_OPEN) ?: false,
            pickerQuery = savedState.get<String>(KEY_PICKER_QUERY).orEmpty(),
        )
    }

    private companion object {
        const val KEY_NAME = "draft.name"
        const val KEY_DESCRIPTION = "draft.description"
        const val KEY_EXERCISES = "draft.exercises"
        const val KEY_ARCHIVED = "draft.archived"
        const val KEY_PICKER_OPEN = "draft.pickerOpen"
        const val KEY_PICKER_QUERY = "draft.pickerQuery"
        const val KEY_READY = "draft.ready"

        // Exercise rows are flattened into a list of strings (always Bundle-safe); null names are "".
        const val EXERCISE_STRIDE = 8
    }
}

private fun RoutineExerciseInput.toFields(): List<String> =
    listOf(key, exerciseId, exerciseName.orEmpty(), sets, repsMin, repsMax, restSeconds, notes)

private fun List<String>.toExerciseInput() = RoutineExerciseInput(
    key = this[0],
    exerciseId = this[1],
    exerciseName = this[2].ifEmpty { null },
    sets = this[3],
    repsMin = this[4],
    repsMax = this[5],
    restSeconds = this[6],
    notes = this[7],
)
