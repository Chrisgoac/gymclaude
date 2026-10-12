package com.chrisgoac.gymlog.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MILLIS = 5_000L

// ---- List ----

enum class LoadStatus { Loading, Ready, Failed }

data class ExerciseListUiState(
    val filter: ExerciseFilter = ExerciseFilter(),
    val status: LoadStatus = LoadStatus.Loading,
    val exercises: List<Exercise> = emptyList(),
)

/**
 * The exercise list. Typing is debounced ([QUERY_DEBOUNCE_MILLIS]; group/equipment chips apply at
 * once) and a new query keeps showing the previous results until its own arrive: there is no
 * Loading flash per keystroke. Loading shows only before the first result and after [retry].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ExerciseListViewModel(repository: ExerciseRepository) : ViewModel() {
    private val filter = MutableStateFlow(ExerciseFilter())
    private val attempt = MutableStateFlow(0)

    private class Results(val status: LoadStatus, val exercises: List<Exercise>)

    /** The query last handed to the repository: only a changed, non-blank query waits (clearing applies at once). */
    private var appliedQuery = filter.value.query

    private val results: Flow<Results> = filter
        .debounce { f -> if (f.query == appliedQuery || f.query.isBlank()) 0L else QUERY_DEBOUNCE_MILLIS }
        .onEach { appliedQuery = it.query }
        .flatMapLatest { f ->
            repository.observeExercises(f)
                .map { Results(LoadStatus.Ready, it) }
                .catch { emit(Results(LoadStatus.Failed, emptyList())) }
        }

    val state: StateFlow<ExerciseListUiState> = attempt
        .flatMapLatest { results.onStart { emit(Results(LoadStatus.Loading, emptyList())) } }
        // The field shows what was typed at once; the list keeps the last results until new ones arrive.
        .combine(filter) { r, f -> ExerciseListUiState(f, r.status, r.exercises) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ExerciseListUiState())

    fun onQueryChange(query: String) = filter.update { it.copy(query = query) }

    /** Selecting the active group again clears it. */
    fun onMuscleGroupSelected(group: MuscleGroup?) = filter.update { it.copy(muscleGroup = if (it.muscleGroup == group) null else group) }

    fun onEquipmentSelected(equipment: Equipment?) = filter.update { it.copy(equipment = if (it.equipment == equipment) null else equipment) }

    fun clearFilters() = filter.update { ExerciseFilter() }

    fun retry() = attempt.update { it + 1 }

    companion object {
        const val QUERY_DEBOUNCE_MILLIS = 250L
    }
}

// ---- Detail ----

sealed interface ExerciseDetailUiState {
    data object Loading : ExerciseDetailUiState
    data object NotFound : ExerciseDetailUiState
    data object Failed : ExerciseDetailUiState
    data class Found(val exercise: Exercise, val deleting: Boolean = false, val deleteFailed: Boolean = false) : ExerciseDetailUiState
}

class ExerciseDetailViewModel(private val repository: ExerciseRepository, private val id: String) : ViewModel() {
    private val deleting = MutableStateFlow(false)
    private val deleteFailed = MutableStateFlow(false)
    private val attempt = MutableStateFlow(0)
    private val deletedFlag = MutableStateFlow(false)

    /** True once the exercise was deleted by this screen: navigate back. */
    val deleted: StateFlow<Boolean> = deletedFlag

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ExerciseDetailUiState> = attempt
        .flatMapLatest {
            combine(repository.observeExercise(id), deleting, deleteFailed) { exercise, busy, failed ->
                if (exercise == null) ExerciseDetailUiState.NotFound else ExerciseDetailUiState.Found(exercise, busy, failed)
            }.onStart<ExerciseDetailUiState> { emit(ExerciseDetailUiState.Loading) }
                .catch { emit(ExerciseDetailUiState.Failed) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ExerciseDetailUiState.Loading)

    fun retry() = attempt.update { it + 1 }

    fun delete() {
        if (deleting.value) return
        deleting.value = true
        deleteFailed.value = false
        viewModelScope.launch {
            try {
                repository.deleteCustom(id)
                deletedFlag.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: ExerciseNotFoundException) {
                deletedFlag.value = true // already gone: same outcome
            } catch (_: Exception) {
                deleteFailed.value = true
            } finally {
                deleting.value = false
            }
        }
    }
}

// ---- Editor ----

data class ExerciseEditorUiState(
    /** Null while creating; set when editing. */
    val exerciseId: String? = null,
    val input: ExerciseInput = ExerciseInput(),
    val errors: Map<ExerciseField, ExerciseFieldError> = emptyMap(),
    val loading: Boolean = false,
    /** Editing an id that is missing or belongs to the read-only catalog. */
    val unavailable: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val savedId: String? = null,
)

class ExerciseEditorViewModel(private val repository: ExerciseRepository, private val exerciseId: String?) : ViewModel() {
    private val _state = MutableStateFlow(ExerciseEditorUiState(exerciseId = exerciseId, loading = exerciseId != null))
    val state: StateFlow<ExerciseEditorUiState> = _state

    init {
        if (exerciseId != null) loadExisting(exerciseId)
    }

    private fun loadExisting(id: String) {
        viewModelScope.launch {
            val exercise = try {
                repository.observeExercise(id).firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update {
                if (exercise == null || !exercise.isCustom) {
                    it.copy(loading = false, unavailable = true)
                } else {
                    it.copy(loading = false, input = ExerciseInput.from(exercise))
                }
            }
        }
    }

    fun onChange(transform: (ExerciseInput) -> ExerciseInput) {
        _state.update { current ->
            val next = transform(current.input)
            // Editing a field clears only that field's error; the rest stay until the next save.
            val cleared = current.errors.filterKeys { field -> fieldValue(current.input, field) == fieldValue(next, field) }
            current.copy(input = next, errors = cleared, saveFailed = false)
        }
    }

    fun onName(value: String) = onChange { it.copy(name = value) }
    fun onMuscleGroup(value: MuscleGroup) = onChange { it.copy(muscleGroup = value) }
    fun onEquipment(value: Equipment) = onChange { it.copy(equipment = value) }
    fun onType(value: ExerciseType) = onChange { it.copy(type = value) }
    fun onVideoUrl(value: String) = onChange { it.copy(videoUrl = value) }
    fun onNotes(value: String) = onChange { it.copy(notes = value) }
    fun onDescription(value: String) = onChange { it.copy(description = value) }
    fun onExecution(value: String) = onChange { it.copy(execution = value) }
    fun onIncrementKg(value: String) = onChange { it.copy(incrementKg = value) }

    fun save() {
        val current = _state.value
        if (current.saving || current.loading || current.unavailable || current.savedId != null) return
        val validation = ExerciseValidator.validate(current.input)
        if (validation is ExerciseValidation.Invalid) {
            _state.update { it.copy(errors = validation.errors) }
            return
        }
        _state.update { it.copy(saving = true, saveFailed = false, errors = emptyMap()) }
        viewModelScope.launch {
            try {
                val saved = if (exerciseId == null) {
                    repository.createCustom(current.input)
                } else {
                    repository.updateCustom(exerciseId, current.input)
                }
                _state.update { it.copy(saving = false, savedId = saved.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExerciseValidationException) {
                _state.update { it.copy(saving = false, errors = e.errors) }
            } catch (_: ExerciseNotEditableException) {
                _state.update { it.copy(saving = false, unavailable = true) }
            } catch (_: ExerciseNotFoundException) {
                _state.update { it.copy(saving = false, unavailable = true) }
            } catch (_: Exception) {
                _state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    private fun fieldValue(input: ExerciseInput, field: ExerciseField): String = when (field) {
        ExerciseField.NAME -> input.name
        ExerciseField.VIDEO_URL -> input.videoUrl
        ExerciseField.NOTES -> input.notes
        ExerciseField.DESCRIPTION -> input.description
        ExerciseField.EXECUTION -> input.execution
        ExerciseField.INCREMENT_KG -> input.incrementKg
    }
}
