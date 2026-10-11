package com.chrisgoac.gymlog.body

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chrisgoac.gymlog.designsystem.component.HierroButton
import com.chrisgoac.gymlog.designsystem.component.HierroButtonVariant
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.body.BodyMeasurement
import com.chrisgoac.gymlog.domain.body.BodyMetricDef
import com.chrisgoac.gymlog.domain.body.BodyMetrics
import com.chrisgoac.gymlog.domain.body.CustomMetricProblem
import com.chrisgoac.gymlog.domain.math.WeightUnit
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.feature.body.R
import java.time.ZoneId
import kotlin.math.abs

/**
 * A single-choice pill. The role and selected state speak for themselves ("4 sem, botón de opción,
 * seleccionado"), so no hand-written "seleccionado" is added to the label.
 */
@Composable
internal fun BodyChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, role: Role = Role.RadioButton) {
    val colors = HierroTheme.colors
    Box(
        modifier
            .heightIn(min = HierroShape.minTouchTarget)
            .widthIn(min = HierroShape.minTouchTarget)
            .background(if (selected) colors.accent else colors.surface)
            .border(HierroShape.border, colors.border)
            .then(
                if (role == Role.RadioButton) Modifier.selectable(selected = selected, role = role, onClick = onClick)
                else Modifier.clickable(role = role, onClick = onClick),
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        HierroText(label, style = HierroTheme.typography.label, color = if (selected) colors.onAccent else colors.ink)
    }
}

@Composable
internal fun MetricRow(options: List<MetricOption>, selected: BodyMetricDef, onSelect: (String) -> Unit, onNew: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(stringResource(R.string.body_filter_metric).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(options, key = { it.def.key }) { option ->
                BodyChip(option.def.label, option.def.key == selected.key, onClick = { onSelect(option.def.key) })
            }
            item(key = "new") { BodyChip(stringResource(R.string.body_metric_new), selected = false, onClick = onNew, role = Role.Button) }
        }
    }
}

@Composable
internal fun PeriodRow(selected: Period, onSelect: (Period) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(stringResource(R.string.body_filter_period).uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(Period.entries) { period -> BodyChip(periodLabel(period), period == selected, onClick = { onSelect(period) }) }
        }
    }
}

@Composable
private fun periodLabel(period: Period): String = stringResource(
    when (period) {
        Period.FOUR_WEEKS -> R.string.body_period_4w
        Period.THREE_MONTHS -> R.string.body_period_3m
        Period.YEAR -> R.string.body_period_year
        Period.ALL -> R.string.body_period_all
    },
)

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    HierroText(text.uppercase(), modifier.semantics { heading() }, HierroTheme.typography.title)
}

/** Label left, value right, optional supporting line; one TalkBack stop "label: spoken value. supporting". */
@Composable
internal fun StatRow(label: String, value: String, spokenValue: String, modifier: Modifier = Modifier, supporting: String? = null) {
    HierroCard(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: $spokenValue" + (supporting?.let { ". $it" } ?: "")
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            HierroText(label, Modifier.weight(1f), HierroTheme.typography.label, HierroTheme.colors.textMuted)
            HierroText(value, style = HierroTheme.typography.title, textAlign = TextAlign.End)
        }
        if (supporting != null) HierroText(supporting, color = HierroTheme.colors.textMuted)
    }
}

/** A change between readings as (visible, spoken); direction is also in the sign and the words, never colour. */
@Composable
internal fun deltaText(delta: Double?, def: BodyMetricDef, unit: WeightUnit): Pair<String, String> {
    if (delta == null) return stringResource(R.string.body_delta_none) to stringResource(R.string.body_delta_none_spoken)
    val shown = BodyFormat.display(def, delta, unit).let { Math.round(it * 100) / 100.0 }
    if (shown == 0.0) return stringResource(R.string.body_delta_flat) to stringResource(R.string.body_delta_flat)
    val symbol = BodyFormat.unitSymbol(def, unit)
    val spokenUnit = BodyFormat.spokenUnit(symbol, LocalContext.current.resources)
    val magnitude = BodyFormat.number(abs(shown))
    return if (shown > 0) {
        stringResource(R.string.body_delta_up, magnitude, symbol).trim() to stringResource(R.string.body_delta_up_spoken, magnitude, spokenUnit).trim()
    } else {
        stringResource(R.string.body_delta_down, magnitude, symbol).trim() to stringResource(R.string.body_delta_down_spoken, magnitude, spokenUnit).trim()
    }
}

/** One history row: value + date as a single stop, with Edit / Delete actions that name the reading. */
@Composable
internal fun HistoryRow(
    measurement: BodyMeasurement,
    def: BodyMetricDef,
    unit: WeightUnit,
    zone: ZoneId,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val date = BodyFormat.longDate(measurement.at, zone)
    val visible = BodyFormat.valueWithUnit(def, measurement.value, unit)
    val spoken = BodyFormat.spokenValue(def, measurement.value, unit, resources)
    HierroCard {
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = resources.getString(R.string.body_history_row, spoken, date) },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HierroText(date, Modifier.weight(1f), HierroTheme.typography.label, HierroTheme.colors.textMuted)
            HierroText(visible, style = HierroTheme.typography.title, textAlign = TextAlign.End)
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HierroButton(
                stringResource(R.string.body_history_edit), onEdit, variant = HierroButtonVariant.Secondary,
                contentDescription = stringResource(R.string.body_history_edit_description, date, spoken),
            )
            HierroButton(
                stringResource(R.string.body_history_delete), onDelete, variant = HierroButtonVariant.Secondary,
                contentDescription = stringResource(R.string.body_history_delete_description, date, spoken),
            )
        }
    }
}

@Composable
internal fun DeleteDialog(measurement: BodyMeasurement, def: BodyMetricDef, unit: WeightUnit, zone: ZoneId, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val title = stringResource(R.string.body_delete_title)
    val resources = LocalContext.current.resources
    Dialog(onDismissRequest = onCancel) {
        HierroCard(Modifier.padding(16.dp).semantics { paneTitle = title }) {
            HierroText(title, Modifier.semantics { heading() }, HierroTheme.typography.title)
            HierroText(
                stringResource(
                    R.string.body_delete_message,
                    BodyFormat.longDate(measurement.at, zone),
                    BodyFormat.spokenValue(def, measurement.value, unit, resources),
                ),
            )
            HierroButton(stringResource(R.string.body_delete_confirm), onConfirm, Modifier.fillMaxWidth())
            HierroButton(stringResource(R.string.body_delete_cancel), onCancel, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
}

@Composable
internal fun CustomMetricDialog(
    state: CustomMetricDialogState,
    onName: (String) -> Unit,
    onUnit: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val title = stringResource(R.string.body_custom_title)
    Dialog(onDismissRequest = onCancel) {
        HierroCard(Modifier.padding(16.dp).semantics { paneTitle = title }) {
            HierroText(title, Modifier.semantics { heading() }, HierroTheme.typography.title)
            HierroTextField(
                value = state.name,
                onValueChange = onName,
                label = stringResource(R.string.body_custom_name),
                errorMessage = nameError(state),
            )
            HierroTextField(
                value = state.unit,
                onValueChange = onUnit,
                label = stringResource(R.string.body_custom_unit),
                errorMessage = if (state.problem == CustomMetricProblem.UNIT_TOO_LONG) stringResource(R.string.body_custom_error_unit_long, BodyMetrics.UNIT_MAX) else null,
                keyboardOptions = KeyboardOptions.Default,
            )
            HierroButton(stringResource(R.string.body_custom_save), onSave, Modifier.fillMaxWidth(), loading = state.saving)
            HierroButton(stringResource(R.string.body_custom_cancel), onCancel, Modifier.fillMaxWidth(), variant = HierroButtonVariant.Secondary)
        }
    }
}

@Composable
private fun nameError(state: CustomMetricDialogState): String? = when {
    state.failed -> stringResource(R.string.body_custom_error_failed)
    state.problem == CustomMetricProblem.NAME_EMPTY -> stringResource(R.string.body_custom_error_empty)
    state.problem == CustomMetricProblem.NAME_TOO_LONG -> stringResource(R.string.body_custom_error_name_long, BodyMetrics.NAME_MAX)
    state.problem == CustomMetricProblem.RESERVED -> stringResource(R.string.body_custom_error_reserved)
    state.problem == CustomMetricProblem.LIMIT -> stringResource(R.string.body_custom_error_limit, BodyMetrics.CUSTOM_MAX)
    else -> null
}
