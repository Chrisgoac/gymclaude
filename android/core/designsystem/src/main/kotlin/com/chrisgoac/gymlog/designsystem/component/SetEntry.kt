package com.chrisgoac.gymlog.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme

/** Visible labels and TalkBack descriptions of one set row (callers localize them). */
data class SetRowLabels(
    val weight: String,
    val reps: String,
    val weightDescription: String,
    val repsDescription: String,
)

/**
 * One weight × reps set: a badge ([badge] = number, or a warm-up mark), two large numeric fields
 * with inline errors, an optional [trailing] control (e.g. "complete") and a row of [actions]
 * underneath. Shared by the active workout and the history editor so both log sets the same way.
 */
@Composable
fun HierroSetRow(
    badge: String,
    weightText: String,
    repsText: String,
    onWeight: (String) -> Unit,
    onReps: (String) -> Unit,
    labels: SetRowLabels,
    weightError: String?,
    repsError: String?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    trailing: @Composable () -> Unit = {},
    actions: @Composable FlowRowScope.() -> Unit = {},
) {
    val colors = HierroTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(if (highlighted) colors.surfaceMuted else colors.surface)
            .border(HierroShape.border, if (highlighted) colors.accent else colors.border)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Box(
                Modifier.widthIn(min = 32.dp).heightIn(min = 56.dp).border(HierroShape.border, colors.border).background(colors.surfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                HierroText(badge, style = HierroTheme.typography.title)
            }
            HierroNumberField(
                value = weightText,
                onValueChange = onWeight,
                label = labels.weight,
                description = labels.weightDescription,
                error = weightError,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            HierroNumberField(
                value = repsText,
                onValueChange = onReps,
                label = labels.reps,
                description = labels.repsDescription,
                error = repsError,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
            trailing()
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** Large, centered numeric field: one stop for TalkBack with label, value and error. */
@Composable
fun HierroNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    description: String,
    error: String?,
    keyboard: KeyboardOptions,
    modifier: Modifier = Modifier,
) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HierroText(label.uppercase(), Modifier.clearAndSetSemantics {}, HierroTheme.typography.label, colors.textMuted, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = keyboard,
            interactionSource = interaction,
            textStyle = HierroTheme.typography.title.copy(color = colors.ink, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(colors.surface)
                .border(if (focused) 4.dp else HierroShape.border, if (error != null) colors.error else colors.border)
                .padding(horizontal = 6.dp, vertical = 12.dp)
                .semantics {
                    contentDescription = description
                    if (error != null) error(error)
                },
        )
        if (error != null) {
            HierroText(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, HierroTheme.typography.label, colors.error)
        }
    }
}

/** Compact bordered action with a full 48dp target; [description] names the set/exercise it acts on. */
@Composable
fun HierroSmallAction(text: String, description: String, selected: Boolean = false, onClick: () -> Unit) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
            .heightIn(min = HierroShape.minTouchTarget)
            .widthIn(min = HierroShape.minTouchTarget)
            .background(if (selected) colors.accent else colors.surface)
            .border(if (focused) 4.dp else HierroShape.border, colors.border)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                this.selected = selected
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        HierroText(text, Modifier.clearAndSetSemantics {}, HierroTheme.typography.label, if (selected) colors.onAccent else colors.ink)
    }
}
