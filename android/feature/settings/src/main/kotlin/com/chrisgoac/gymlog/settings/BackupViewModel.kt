package com.chrisgoac.gymlog.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chrisgoac.gymlog.domain.backup.BackupRepository
import com.chrisgoac.gymlog.domain.backup.ImportMode
import com.chrisgoac.gymlog.domain.backup.ImportResult
import com.chrisgoac.gymlog.domain.backup.PreparedImport
import com.chrisgoac.gymlog.model.backup.BackupException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId

/** Opens the documents the user picked through the Storage Access Framework (no storage permission). */
interface BackupDocuments {
    fun openForWrite(uri: String): OutputStream
    fun openForRead(uri: String): InputStream

    /** Removes a document this app created (a partial export); best effort, may throw. */
    fun delete(uri: String)
}

enum class BackupFailure { NotABackup, NewerVersion, TooLarge, Corrupt, InvalidRecords, FutureDates, NoAccount, AccountChanged, ActiveWorkout, ReadFailed, WriteFailed, ImportFailed }

/** What the file holds, shown before anything is written. */
data class BackupPreview(val totalRows: Int, val workouts: Int, val skippedCatalogExercises: Int, val exportedAt: Long)

sealed interface BackupPhase {
    data object Idle : BackupPhase
    data object Exporting : BackupPhase
    data class Exported(val rows: Int) : BackupPhase
    data object Reading : BackupPhase

    /** Validated; the user picks a mode. */
    data class Ready(val preview: BackupPreview, val mode: ImportMode) : BackupPhase

    /** Replace asks once more, saying what it deletes. */
    data class ConfirmReplace(val preview: BackupPreview) : BackupPhase
    data object Importing : BackupPhase
    data class Imported(val result: ImportResult, val mode: ImportMode) : BackupPhase
    data class Failed(val reason: BackupFailure) : BackupPhase
}

class BackupViewModel(
    private val backups: BackupRepository,
    private val documents: BackupDocuments,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _phase = MutableStateFlow<BackupPhase>(BackupPhase.Idle)
    val phase: StateFlow<BackupPhase> = _phase.asStateFlow()

    /** Held here, not in the state: the parsed file can be large. */
    private var prepared: PreparedImport? = null

    /** The PWA's file name: `gymlog-backup-YYYY-MM-DD.json` (local date). */
    fun suggestedFileName(): String = "gymlog-backup-${Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()}.json"

    private val busy: Boolean
        get() = _phase.value.let { it == BackupPhase.Exporting || it == BackupPhase.Reading || it == BackupPhase.Importing }

    /** [uri] null = the user cancelled the picker. */
    fun exportTo(uri: String?) {
        if (uri == null || busy) return
        prepared = null
        _phase.value = BackupPhase.Exporting
        viewModelScope.launch {
            _phase.value = try {
                val result = withContext(io) { documents.openForWrite(uri).use { backups.export(it) } }
                BackupPhase.Exported(result.rows)
            } catch (e: CancellationException) {
                discardPartial(uri)
                throw e
            } catch (e: BackupException) {
                discardPartial(uri)
                BackupPhase.Failed(e.toFailure())
            } catch (_: Exception) {
                discardPartial(uri)
                BackupPhase.Failed(BackupFailure.WriteFailed)
            }
        }
    }

    /** Reads and validates the picked file; nothing is written until the user confirms a mode. */
    fun importFrom(uri: String?) {
        if (uri == null || busy) return
        prepared = null
        _phase.value = BackupPhase.Reading
        viewModelScope.launch {
            _phase.value = try {
                val file = withContext(io) { documents.openForRead(uri).use { backups.prepare(it) } }
                prepared = file
                BackupPhase.Ready(file.toPreview(), ImportMode.Merge)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                BackupPhase.Failed(e.toFailure())
            } catch (_: Exception) {
                BackupPhase.Failed(BackupFailure.ReadFailed)
            }
        }
    }

    /** A failed export must not leave a truncated file that looks like a backup. */
    private suspend fun discardPartial(uri: String) {
        try {
            withContext(NonCancellable + io) { documents.delete(uri) }
        } catch (_: Exception) {
            // best effort: the user can delete it; importing it would fail validation anyway
        }
    }

    fun selectMode(mode: ImportMode) = _phase.update { if (it is BackupPhase.Ready) it.copy(mode = mode) else it }

    /** Merge applies now; replace first asks for confirmation. */
    fun startImport() {
        val ready = _phase.value as? BackupPhase.Ready ?: return
        if (ready.mode == ImportMode.ReplaceAfterConfirmation) {
            _phase.value = BackupPhase.ConfirmReplace(ready.preview)
        } else {
            apply(ImportMode.Merge)
        }
    }

    fun confirmReplace() {
        if (_phase.value !is BackupPhase.ConfirmReplace) return
        apply(ImportMode.ReplaceAfterConfirmation)
    }

    /** Leaves the confirmation (back to choosing) or drops the read file. */
    fun cancel() {
        when (val current = _phase.value) {
            is BackupPhase.ConfirmReplace -> _phase.value = BackupPhase.Ready(current.preview, ImportMode.ReplaceAfterConfirmation)
            is BackupPhase.Ready -> dismiss()
            else -> Unit
        }
    }

    fun dismiss() {
        if (busy) return
        prepared = null
        _phase.value = BackupPhase.Idle
    }

    private fun apply(mode: ImportMode) {
        val file = prepared ?: return
        _phase.value = BackupPhase.Importing
        viewModelScope.launch {
            _phase.value = try {
                BackupPhase.Imported(backups.import(file, mode), mode).also { prepared = null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                BackupPhase.Failed(e.toFailure())
            } catch (_: Exception) {
                BackupPhase.Failed(BackupFailure.ImportFailed)
            }
        }
    }

    private fun PreparedImport.toPreview() = BackupPreview(
        totalRows = totalRows,
        workouts = backup.entities.workoutSessions.count { it.deletedAt == null },
        skippedCatalogExercises = skippedCatalogExercises,
        exportedAt = backup.exportedAt,
    )

    private fun BackupException.toFailure(): BackupFailure = when (this) {
        is BackupException.NotABackup -> BackupFailure.NotABackup
        is BackupException.UnsupportedVersion -> BackupFailure.NewerVersion
        is BackupException.TooLarge -> BackupFailure.TooLarge
        is BackupException.Corrupt -> BackupFailure.Corrupt
        is BackupException.InvalidRecords -> BackupFailure.InvalidRecords
        is BackupException.FutureTimestamps -> BackupFailure.FutureDates
        is BackupException.NoAccount -> BackupFailure.NoAccount
        is BackupException.AccountChanged -> BackupFailure.AccountChanged
        is BackupException.ActiveWorkout -> BackupFailure.ActiveWorkout
    }
}
