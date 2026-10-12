package com.chrisgoac.gymlog.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroCard
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme
import com.chrisgoac.gymlog.domain.stats.GymFilter
import com.chrisgoac.gymlog.domain.time.Period
import com.chrisgoac.gymlog.domain.workout.Gym
import com.chrisgoac.gymlog.feature.progress.R

/** A single-choice pill with selected semantics ("4 sem, seleccionado"). */
@Composable
internal fun ProgressChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HierroTheme.colors
    val selectedText = stringResource(R.string.progress_selected)
    Box(
        modifier
            .heightIn(min = HierroShape.minTouchTarget)
            .widthIn(min = HierroShape.minTouchTarget)
            .background(if (selected) colors.accent else colors.surface)
            .border(HierroShape.border, colors.border)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = if (selected) "$label, $selectedText" else label }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        HierroText(label, style = HierroTheme.typography.label, color = if (selected) colors.onAccent else colors.ink)
    }
}

@Composable
internal fun <T> ProgressChoiceRow(
    caption: String,
    options: List<T>,
    selected: T,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(caption.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(options) { option -> ProgressChip(labelOf(option), option == selected, onClick = { onSelect(option) }) }
        }
    }
}

@Composable
internal fun PeriodRow(selected: Period, onSelect: (Period) -> Unit) = ProgressChoiceRow(
    caption = stringResource(R.string.progress_filter_period),
    options = Period.entries,
    selected = selected,
    labelOf = { periodLabel(it) },
    onSelect = onSelect,
)

@Composable
internal fun GymRow(gyms: List<Gym>, selected: GymFilter, onSelect: (GymFilter) -> Unit) {
    if (gyms.isEmpty()) return
    ProgressChoiceRow(
        caption = stringResource(R.string.progress_filter_gym),
        options = listOf<GymFilter>(GymFilter.All) + gyms.map { GymFilter.Gym(it.id) },
        selected = selected,
        labelOf = { option ->
            when (option) {
                GymFilter.All -> stringResource(R.string.progress_gym_all)
                is GymFilter.Gym -> gyms.firstOrNull { it.id == option.id }?.name.orEmpty()
            }
        },
        onSelect = onSelect,
    )
}

@Composable
internal fun periodLabel(period: Period): String = stringResource(
    when (period) {
        Period.FOUR_WEEKS -> R.string.progress_period_4w
        Period.THREE_MONTHS -> R.string.progress_period_3m
        Period.YEAR -> R.string.progress_period_year
        Period.ALL -> R.string.progress_period_all
    },
)

@Composable
internal fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    HierroText(text.uppercase(), modifier.semantics { heading() }, HierroTheme.typography.title)
}

/**
 * One metric as a full-width row (label left, value right, optional supporting line) so it never
 * truncates at 200% font. Merged into a single TalkBack stop: "label: value, supporting".
 */
@Composable
internal fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    spokenValue: String = value,
) {
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
