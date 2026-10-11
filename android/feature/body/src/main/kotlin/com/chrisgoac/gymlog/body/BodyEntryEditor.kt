package com.chrisgoac.gymlog.body

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.body.BodyDateError
import com.chrisgoac.gymlog.domain.body.BodyValueError
import com.chrisgoac.gymlog.feature.body.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add / edit one reading of [BodyUiState.selected]: value (strict decimal, comma or dot, typed in
 * the display unit) and date (Material 3 date picker, no future days). Replaces the list while open;
 * system back cancels without writing.
 */
@Composable
internal fun BodyEntryEditor(
    state: BodyUiState,
    editor: BodyEditorState,
    today: LocalDate,
    onValue: (String) -> Unit,
    onDate: (LocalDate) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onCancel)
    var picking by rememberSaveable { mutableStateOf(false) }
    val def = state.selected
    val symbol = BodyFormat.unitSymbol(def, state.weightUnit)
    val dateText = BodyFormat.longDate(editor.date)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HierroText(
            stringResource(if (editor.entryId == null) R.string.body_editor_title_new else R.string.body_editor_title_edit, def.label).uppercase(),
            Modifier.semantics { heading() },
            HierroTheme.typography.title,
        )
        HierroTextField(
            value = editor.valueText,
            onValueChange = onValue,
            label = stringResource(R.string.body_editor_value, symbol.ifEmpty { def.label }),
            enabled = !editor.saving,
            errorMessage = (editor.problem as? EditorProblem.Value)?.let { valueError(it, symbol) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HierroText(stringResource(R.string.body_editor_date).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
            HierroButton(
                text = dateText,
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth(),
                variant = HierroButtonVariant.Secondary,
                enabled = !editor.saving,
                contentDescription = stringResource(R.string.body_editor_date_change, dateText),
            )
            (editor.problem as? EditorProblem.Date)?.let {
                HierroText(
                    stringResource(if (it.error == BodyDateError.IN_FUTURE) R.string.body_error_date_future else R.string.body_error_date_old),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = HierroTheme.colors.error,
                )
            }
        }
        when (editor.problem) {
            EditorProblem.Missing -> Notice(stringResource(R.string.body_error_missing))
            EditorProblem.SaveFailed -> Notice(stringResource(R.string.body_error_save))
            else -> Unit
        }
        HierroButton(
            stringResource(R.string.body_editor_save), onSave, Modifier.fillMaxWidth(),
            loading = editor.saving, loadingLabel = stringResource(R.string.body_editor_saving),
        )
        HierroButton(stringResource(R.string.body_editor_cancel), onCancel, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary, enabled = !editor.saving)
    }
    if (picking) {
        BodyDatePicker(editor.date, today, onPick = { onDate(it); picking = false }, onDismiss = { picking = false })
    }
}

@Composable
private fun Notice(text: String) {
    HierroText(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = HierroTheme.colors.error)
}

@Composable
private fun valueError(problem: EditorProblem.Value, symbol: String): String = when (problem.error) {
    BodyValueError.REQUIRED -> stringResource(R.string.body_error_required)
    BodyValueError.NOT_A_NUMBER -> stringResource(R.string.body_error_not_number)
    BodyValueError.NEGATIVE -> stringResource(R.string.body_error_negative)
    BodyValueError.TOO_SMALL -> stringResource(R.string.body_error_too_small, BodyFormat.number(problem.min ?: 0.0), symbol).trim()
    BodyValueError.TOO_LARGE -> stringResource(R.string.body_error_too_large, BodyFormat.number(problem.max ?: 0.0), symbol).trim()
}

/** Material 3 picker works in UTC midnights; days after [today] are not selectable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BodyDatePicker(initial: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val todayUtc = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val colors = HierroTheme.colors
    val scheme = (if (colors.isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = colors.accent, onPrimary = colors.onAccent,
        surface = colors.surface, onSurface = colors.ink, surfaceVariant = colors.surfaceMuted, onSurfaceVariant = colors.textMuted,
        background = colors.background, onBackground = colors.ink, surfaceContainerHigh = colors.surface, error = colors.error,
        outline = colors.border, secondaryContainer = colors.accent, onSecondaryContainer = colors.onAccent,
    )
    MaterialTheme(colorScheme = scheme) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
                    },
                ) { Text(stringResource(R.string.body_date_ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.body_date_cancel)) } },
        ) {
            DatePicker(state = pickerState, title = { Text(stringResource(R.string.body_date_title), Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }
}
