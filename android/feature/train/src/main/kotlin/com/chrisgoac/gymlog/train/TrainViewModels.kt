package com.chrisgoac.gymlog.train

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.routine.RoutineSummary
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.domain.workout.TrainingSummary
import com.chrisgoac.gymlog.domain.workout.WorkoutAggregate
import com.chrisgoac.gymlog.domain.workout.WorkoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TrainLoadStatus { Loading, Ready, Failed }

enum class StartError { GymUnavailable, RoutineUnavailable, RoutineArchived, Failed }

data class TrainHomeUiState(
    val status: TrainLoadStatus = TrainLoadStatus.Loading,
    val activeWorkout: WorkoutAggregate? = null,
    val nextRoutine: RoutineSummary? = null,
    val gyms: List<Gym> = emptyList(),
    /** Null = "Sin gimnasio". Always an id present in [gyms] (a vanished gym falls back to null). */
    val selectedGymId: String? = null,
    val summary: TrainingSummary? = null,
    val starting: Boolean = false,
    val startError: StartError? = null,
)

sealed interface TrainEvent {
    /** Route to the active workout [sessionId] (new or resumed). */
    data class OpenWorkout(val sessionId: String) : TrainEvent
    /** A start failed; the error is also in the state so the Train tab can show it. */
    data class StartFailed(val error: StartError) : TrainEvent
}

/**
 * Train tab state and start actions. One instance per account shell: the Routines tab's "Empezar"
 * goes through [startRoutine] too, so both entry points share the gym selection, the in-flight
 * guard and the one-active-workout rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainHomeViewModel(
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val start: StartWorkoutUseCase,
) : ViewModel() {
    private val selectedGym = MutableStateFlow<String?>(null)
    private val starting = MutableStateFlow(false)
    private val startError = MutableStateFlow<StartError?>(null)
    private val attempt = MutableStateFlow(0)
    private val events = Channel<TrainEvent>(Channel.BUFFERED)

    val eventFlow: Flow<TrainEvent> = events.receiveAsFlow()

    val state: StateFlow<TrainHomeUiState> = attempt
        .flatMapLatest { content() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TrainHomeUiState())

    private fun content(): Flow<TrainHomeUiState> {
        val data = combine(
            workouts.observeActiveWorkout(),
            routines.observeRoutines(),
            routines.observeNextRoutineId(),
            workouts.observeGyms(),
            workouts.observeTrainingSummary(),
        ) { active, summaries, nextId, gyms, summary ->
            Data(active, summaries.firstOrNull { it.id == nextId && !it.archived }, gyms, summary)
        }
        return combine(data, selectedGym, starting, startError) { d, gymId, isStarting, error ->
            TrainHomeUiState(
                status = TrainLoadStatus.Ready,
                activeWorkout = d.active,
                nextRoutine = d.next,
                gyms = d.gyms,
                selectedGymId = gymId?.takeIf { id -> d.gyms.any { it.id == id } },
                summary = d.summary,
                starting = isStarting,
                startError = error,
            )
        }.onStart { emit(TrainHomeUiState(TrainLoadStatus.Loading)) }
            .catch { emit(TrainHomeUiState(TrainLoadStatus.Failed)) }
    }

    private class Data(val active: WorkoutAggregate?, val next: RoutineSummary?, val gyms: List<Gym>, val summary: TrainingSummary)

    fun retry() = attempt.update { it + 1 }

    fun selectGym(id: String?) {
        selectedGym.value = id
    }

    fun dismissError() {
        startError.value = null
    }

    fun startFree() = launchStart { start.startFree(currentGym()) }

    fun startRoutine(routineId: String) = launchStart { start.startRoutine(routineId, currentGym()) }

    /** The selected gym; if it vanished meanwhile the repository rejects it and the selection resets. */
    private fun currentGym(): String? = selectedGym.value

    private fun launchStart(block: suspend () -> StartWorkoutResult) {
        // A tap while another start is in flight is dropped (the use case would resume it anyway).
        if (!starting.compareAndSet(expect = false, update = true)) return
        startError.value = null
        viewModelScope.launch {
            try {
                when (val result = block()) {
                    is StartWorkoutResult.Started -> events.send(TrainEvent.OpenWorkout(result.sessionId))
                    StartWorkoutResult.GymUnavailable -> {
                        selectedGym.value = null
                        fail(StartError.GymUnavailable)
                    }
                    StartWorkoutResult.RoutineUnavailable -> fail(StartError.RoutineUnavailable)
                    StartWorkoutResult.RoutineArchived -> fail(StartError.RoutineArchived)
                    StartWorkoutResult.Failed -> fail(StartError.Failed)
                }
            } finally {
                starting.value = false
            }
        }
    }

    private suspend fun fail(error: StartError) {
        startError.value = error
        events.send(TrainEvent.StartFailed(error))
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
