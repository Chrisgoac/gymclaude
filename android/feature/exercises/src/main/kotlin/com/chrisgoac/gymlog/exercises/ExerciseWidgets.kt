package com.chrisgoac.gymlog.exercises

import com.chrisgoac.gymlog.feature.exercises.R
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.Role
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme

/** A single-choice pill with selected semantics for TalkBack ("Pecho, seleccionado"). */
@Composable
internal fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HierroTheme.colors
    val selectedText = stringResource(R.string.exercise_selected)
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

/** Horizontally scrolling single-choice row with a caption; [selected] == null means "no choice". */
@Composable
internal fun <T> ChoiceRow(
    caption: String,
    options: List<T>,
    selected: T?,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(caption.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(options) { option -> ChoiceChip(labelOf(option), option == selected, onClick = { onSelect(option) }) }
        }
    }
}
