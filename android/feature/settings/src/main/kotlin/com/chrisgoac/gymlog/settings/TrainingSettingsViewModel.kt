package com.chrisgoac.gymlog.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.exercise.Equipment
import com.chrisgoac.gymlog.domain.exercise.MuscleGroup
import com.chrisgoac.gymlog.domain.routine.RoutineRepository
import com.chrisgoac.gymlog.domain.settings.DeviceSettings
import com.chrisgoac.gymlog.domain.settings.DeviceSettingsRepository
import com.chrisgoac.gymlog.domain.settings.InvalidSettingException
import com.chrisgoac.gymlog.domain.settings.SettingsRepository
import com.chrisgoac.gymlog.domain.settings.ThemeMode
import com.chrisgoac.gymlog.domain.settings.TrainingSettings
import com.chrisgoac.gymlog.domain.workout.ProgressionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which numeric field rejected the last committed text. */
sealed interface SettingField {
    data object WeeklyTarget : SettingField
    data class Increment(val equipment: Equipment) : SettingField
    data class VolumeGoal(val group: MuscleGroup) : SettingField
}

data class RoutineOrderItem(val id: String, val name: String)

data class TrainingSettingsUiState(
    val loaded: Boolean = false,
    val training: TrainingSettings = TrainingSettings(),
    val device: DeviceSettings = DeviceSettings(),
    val routines: List<RoutineOrderItem> = emptyList(),
    val invalid: SettingField? = null,
    val writeFailed: Boolean = false,
)

class TrainingSettingsViewModel(
    private val settings: SettingsRepository,
    private val device: DeviceSettingsRepository,
    private val routines: RoutineRepository,
) : ViewModel() {
    private val invalid = MutableStateFlow<SettingField?>(null)
    private val writeFailed = MutableStateFlow(false)

    val state: StateFlow<TrainingSettingsUiState> = combine(
        settings.observeTraining(),
        device.observe(),
        routines.observeRoutines().map { list -> list.filter { !it.archived }.map { RoutineOrderItem(it.id, it.name) } },
        invalid,
        writeFailed,
    ) { training, deviceSettings, order, bad, failed ->
        TrainingSettingsUiState(true, training, deviceSettings, order, bad, failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), TrainingSettingsUiState())

    /** Commits typed text; unparsable or out-of-range input is refused and flagged on [field], nothing stored. */
    fun commitWeeklyTarget(text: String) = commit(SettingField.WeeklyTarget) {
        val n = text.trim().toIntOrNull() ?: throw InvalidInput
        settings.setWeeklyTarget(n)
    }

    fun commitIncrement(equipment: Equipment, text: String) = commit(SettingField.Increment(equipment)) {
        settings.setIncrement(equipment, number(text) ?: throw InvalidInput)
    }

    /** Blank text removes the goal. */
    fun commitVolumeGoal(group: MuscleGroup, text: String) = commit(SettingField.VolumeGoal(group)) {
        settings.setVolumeGoal(group, if (text.isBlank()) null else number(text) ?: throw InvalidInput)
    }

    fun setProgressionMode(mode: ProgressionMode) = write { settings.setProgressionMode(mode) }

    fun setTheme(theme: ThemeMode) = write { device.setTheme(theme) }


    /** Moves routine [id] one place up (-1) or down (+1); this is the order the rotation follows. */
    fun moveRoutine(id: String, delta: Int) {
        val ids = state.value.routines.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        write { routines.reorder(ids) }
    }

    fun dismissWriteFailed() { writeFailed.value = false }

    private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private object InvalidInput : Exception() {
        private fun readResolve(): Any = InvalidInput
    }

    private fun commit(field: SettingField, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                if (invalid.value == field) invalid.value = null
            } catch (_: InvalidInput) {
                invalid.value = field
            } catch (_: InvalidSettingException) {
                invalid.value = field
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                writeFailed.value = true
            }
        }
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                writeFailed.value = true
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
