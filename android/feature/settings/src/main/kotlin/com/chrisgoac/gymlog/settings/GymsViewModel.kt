package com.chrisgoac.gymlog.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.settings.DeviceSettingsRepository
import com.chrisgoac.gymlog.domain.settings.GymItem
import com.chrisgoac.gymlog.domain.settings.GymNameProblem
import com.chrisgoac.gymlog.domain.settings.GymRepository
import com.chrisgoac.gymlog.domain.settings.InvalidGymNameException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GymsLoadStatus { Loading, Ready, Failed }

/** The name dialog-less editor: [id] null = creating a gym. */
data class GymEditor(val id: String?, val name: String, val error: GymNameProblem? = null)

/** A delete waiting for the user's yes; [sessionCount] > 0 = workouts recorded there keep their history. */
data class PendingGymDelete(val id: String, val name: String, val sessionCount: Int)

data class GymsUiState(
    val status: GymsLoadStatus = GymsLoadStatus.Loading,
    val gyms: List<GymItem> = emptyList(),
    /** Only ever a gym that exists and is not archived. */
    val defaultGymId: String? = null,
    val editor: GymEditor? = null,
    val pendingDelete: PendingGymDelete? = null,
    val writeFailed: Boolean = false,
)

class GymsViewModel(
    private val gyms: GymRepository,
    private val device: DeviceSettingsRepository,
) : ViewModel() {
    private val editor = MutableStateFlow<GymEditor?>(null)
    private val pendingDelete = MutableStateFlow<PendingGymDelete?>(null)
    private val writeFailed = MutableStateFlow(false)

    val state: StateFlow<GymsUiState> = combine(
        gyms.observeGyms().map<List<GymItem>, GymsLoadStatusAndGyms> { GymsLoadStatusAndGyms(GymsLoadStatus.Ready, it) }
            .catch { emit(GymsLoadStatusAndGyms(GymsLoadStatus.Failed, emptyList())) },
        device.observe().map { it.defaultGymId },
        editor,
        pendingDelete,
        writeFailed,
    ) { loaded, defaultId, edit, delete, failed ->
        GymsUiState(
            status = loaded.status,
            gyms = loaded.gyms,
            defaultGymId = defaultId?.takeIf { id -> loaded.gyms.any { it.id == id && !it.archived } },
            editor = edit,
            pendingDelete = delete,
            writeFailed = failed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GymsUiState())

    private class GymsLoadStatusAndGyms(val status: GymsLoadStatus, val gyms: List<GymItem>)

    fun startCreate() { editor.value = GymEditor(null, "") }

    fun startRename(id: String) {
        val gym = state.value.gyms.firstOrNull { it.id == id } ?: return
        editor.value = GymEditor(id, gym.name)
    }

    fun onName(text: String) = editor.update { it?.copy(name = text, error = null) }

    fun cancelEdit() { editor.value = null }

    fun save() {
        val current = editor.value ?: return
        viewModelScope.launch {
            try {
                if (current.id == null) gyms.create(current.name) else gyms.rename(current.id, current.name)
                editor.value = null
            } catch (e: InvalidGymNameException) {
                editor.update { it?.copy(error = e.problem) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                writeFailed.value = true
            }
        }
    }

    fun setArchived(id: String, archived: Boolean) = write {
        gyms.setArchived(id, archived)
        // An archived gym is no longer offered to start workouts, so it cannot stay the default.
        if (archived) clearDefaultIf(id)
    }

    /** Moves [id] one place up (-1) or down (+1) in the manual order. */
    fun move(id: String, delta: Int) {
        val ids = state.value.gyms.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        write { gyms.reorder(ids) }
    }

    fun setDefault(id: String?) = write { device.setDefaultGym(id) }

    fun requestDelete(id: String) {
        val gym = state.value.gyms.firstOrNull { it.id == id } ?: return
        pendingDelete.value = PendingGymDelete(gym.id, gym.name, gym.sessionCount)
    }

    fun cancelDelete() { pendingDelete.value = null }

    fun confirmDelete() {
        val pending = pendingDelete.value ?: return
        pendingDelete.value = null
        write {
            gyms.delete(pending.id)
            clearDefaultIf(pending.id)
        }
    }

    /**
     * Compares the RAW stored default, not [state]'s (which hides a default naming an archived or
     * missing gym, and may not be collected at all): otherwise un-archiving would bring it back.
     */
    private suspend fun clearDefaultIf(id: String) {
        if (device.observe().first().defaultGymId == id) device.setDefaultGym(null)
    }

    fun dismissWriteFailed() { writeFailed.value = false }

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
