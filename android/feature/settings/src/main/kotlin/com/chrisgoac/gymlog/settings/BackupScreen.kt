package com.chrisgoac.gymlog.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroHeaderAction
import com.chrisgoac.gymlog.designsystem.component.HierroScreenHeader
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.backup.ImportMode
import com.chrisgoac.gymlog.feature.settings.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Backup export/import through the Storage Access Framework (the user picks the document; no storage
 * permission). Import reads and validates the whole file first, then offers Merge or Replace; Replace
 * asks for an explicit confirmation before deleting anything.
 */
@Composable
fun BackupScreen(viewModel: BackupViewModel, onBack: () -> Unit, zone: () -> ZoneId, modifier: Modifier = Modifier) {
    val phase by viewModel.phase.collectAsState()
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_MIME)) { uri ->
        viewModel.exportTo(uri?.toString())
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importFrom(uri?.toString())
    }
    val busy = phase == BackupPhase.Exporting || phase == BackupPhase.Reading || phase == BackupPhase.Importing

    Column(modifier.fillMaxSize()) {
        HierroScreenHeader(stringResource(R.string.settings_backup_title)) { HierroHeaderAction(stringResource(R.string.settings_back), onBack) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HierroCard {
                SectionTitle(stringResource(R.string.settings_backup_export_title))
                HierroText(stringResource(R.string.settings_backup_export_hint), color = HierroTheme.colors.textMuted)
                HierroButton(
                    stringResource(R.string.settings_backup_export),
                    { createDocument.launch(viewModel.suggestedFileName()) },
                    enabled = !busy,
                    loading = phase == BackupPhase.Exporting,
                    loadingLabel = stringResource(R.string.settings_backup_exporting),
                )
            }
            HierroCard {
                SectionTitle(stringResource(R.string.settings_backup_import_title))
                HierroText(stringResource(R.string.settings_backup_import_hint), color = HierroTheme.colors.textMuted)
                HierroButton(
                    stringResource(R.string.settings_backup_import_pick),
                    { openDocument.launch(IMPORT_MIMES) },
                    variant = HierroButtonVariant.Secondary,
                    enabled = !busy,
                    loading = phase == BackupPhase.Reading,
                    loadingLabel = stringResource(R.string.settings_backup_reading),
                )
            }
            PhaseCard(phase, viewModel, zone)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    HierroText(text.uppercase(), Modifier.semantics { heading() }, style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
}

/** Status text is a polite live region so TalkBack announces progress and results. */
@Composable
private fun Status(text: String, error: Boolean = false) {
    HierroText(
        text,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error) HierroTheme.colors.error else HierroTheme.colors.ink,
    )
}

@Composable
private fun PhaseCard(phase: BackupPhase, vm: BackupViewModel, zone: () -> ZoneId) {
    when (phase) {
        BackupPhase.Idle -> Unit
        BackupPhase.Exporting -> HierroCard { Status(stringResource(R.string.settings_backup_exporting)) }
        BackupPhase.Reading -> HierroCard { Status(stringResource(R.string.settings_backup_reading)) }
        BackupPhase.Importing -> HierroCard { Status(stringResource(R.string.settings_backup_importing)) }
        is BackupPhase.Exported -> HierroCard {
            Status(pluralStringResource(R.plurals.settings_backup_exported, phase.rows, phase.rows))
            HierroButton(stringResource(R.string.settings_backup_done), vm::dismiss, variant = HierroButtonVariant.Secondary)
        }
        is BackupPhase.Ready -> HierroCard {
            Preview(phase.preview, zone)
            SectionTitle(stringResource(R.string.settings_backup_mode_title))
            ModeOption(ImportMode.Merge, phase.mode, R.string.settings_backup_mode_merge, R.string.settings_backup_mode_merge_hint, vm)
            ModeOption(ImportMode.ReplaceAfterConfirmation, phase.mode, R.string.settings_backup_mode_replace, R.string.settings_backup_mode_replace_hint, vm)
            HierroButton(stringResource(R.string.settings_backup_import_apply), vm::startImport)
            HierroButton(stringResource(R.string.settings_backup_cancel), vm::cancel, variant = HierroButtonVariant.Secondary)
        }
        is BackupPhase.ConfirmReplace -> HierroCard {
            HierroText(stringResource(R.string.settings_backup_replace_confirm_title).uppercase(), Modifier.semantics { heading() }, style = HierroTheme.typography.title, color = HierroTheme.colors.error)
            Status(stringResource(R.string.settings_backup_replace_confirm_message), error = true)
            HierroButton(stringResource(R.string.settings_backup_replace_keep), vm::cancel)
            HierroButton(stringResource(R.string.settings_backup_replace_confirm), vm::confirmReplace, variant = HierroButtonVariant.Secondary)
        }
        is BackupPhase.Imported -> HierroCard {
            Status(stringResource(R.string.settings_backup_imported))
            val result = phase.result
            HierroText(pluralStringResource(R.plurals.settings_backup_result_written, result.written, result.written))
            if (phase.mode == ImportMode.ReplaceAfterConfirmation) {
                HierroText(pluralStringResource(R.plurals.settings_backup_result_deleted, result.deleted, result.deleted))
            }
            HierroText(pluralStringResource(R.plurals.settings_backup_result_unchanged, result.unchanged, result.unchanged))
            HierroButton(stringResource(R.string.settings_backup_done), vm::dismiss, variant = HierroButtonVariant.Secondary)
        }
        is BackupPhase.Failed -> HierroCard {
            Status(stringResource(phase.reason.message()), error = true)
            HierroButton(stringResource(R.string.settings_backup_done), vm::dismiss, variant = HierroButtonVariant.Secondary)
        }
    }
}

@Composable
private fun Preview(preview: BackupPreview, zone: () -> ZoneId) {
    val date = Instant.ofEpochMilli(preview.exportedAt).atZone(zone()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    Status(stringResource(R.string.settings_backup_preview_title, date))
    HierroText(pluralStringResource(R.plurals.settings_backup_preview_rows, preview.totalRows, preview.totalRows))
    HierroText(pluralStringResource(R.plurals.settings_backup_preview_workouts, preview.workouts, preview.workouts))
    if (preview.skippedCatalogExercises > 0) {
        HierroText(stringResource(R.string.settings_backup_preview_catalog), color = HierroTheme.colors.textMuted)
    }
}

@Composable
private fun ModeOption(mode: ImportMode, selected: ImportMode, label: Int, hint: Int, vm: BackupViewModel) {
    val isSelected = mode == selected
    val text = stringResource(label)
    HierroButton(
        if (isSelected) stringResource(R.string.settings_backup_mode_selected, text) else text,
        { vm.selectMode(mode) },
        variant = if (isSelected) HierroButtonVariant.Primary else HierroButtonVariant.Secondary,
        contentDescription = if (isSelected) stringResource(R.string.settings_backup_mode_selected_spoken, text) else null,
    )
    HierroText(stringResource(hint), color = HierroTheme.colors.textMuted)
}

private fun BackupFailure.message(): Int = when (this) {
    BackupFailure.NotABackup -> R.string.settings_backup_error_not_backup
    BackupFailure.NewerVersion -> R.string.settings_backup_error_newer
    BackupFailure.TooLarge -> R.string.settings_backup_error_too_large
    BackupFailure.Corrupt -> R.string.settings_backup_error_corrupt
    BackupFailure.InvalidRecords -> R.string.settings_backup_error_invalid
    BackupFailure.FutureDates -> R.string.settings_backup_error_future_dates
    BackupFailure.NoAccount -> R.string.settings_backup_error_no_account
    BackupFailure.AccountChanged -> R.string.settings_backup_error_account_changed
    BackupFailure.ActiveWorkout -> R.string.settings_backup_error_active_workout
    BackupFailure.ReadFailed -> R.string.settings_backup_error_read
    BackupFailure.WriteFailed -> R.string.settings_backup_error_write
    BackupFailure.ImportFailed -> R.string.settings_backup_error_import
}

private const val JSON_MIME = "application/json"

/** Some file managers label .json files as plain text or generic binary; the content is validated anyway. */
private val IMPORT_MIMES = arrayOf("application/json", "text/plain", "application/octet-stream")
