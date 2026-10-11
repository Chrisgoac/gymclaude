package com.chrisgoac.gymlog.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chrisgoac.gymlog.designsystem.component.HierroSmallAction
import com.chrisgoac.gymlog.designsystem.component.HierroText
import com.chrisgoac.gymlog.designsystem.component.HierroTextField
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme

/** One-of-N choice as wrapping bordered actions; the selected one is announced as selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(caption: String, options: List<T>, selected: T, labelOf: @Composable (T) -> String, onSelect: (T) -> Unit) {
    HierroText(caption.uppercase(), style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val label = labelOf(option)
            HierroSmallAction(label, label, selected = option == selected) { onSelect(option) }
        }
    }
}

/**
 * Numeric field committed on IME Done or when focus leaves it (the PWA commits on blur); the text
 * follows [stored] whenever the stored value changes (e.g. a sync). [error] is shown while the last
 * commit of this field was refused.
 */
@Composable
internal fun CommitField(label: String, stored: String, error: String?, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember(stored) { mutableStateOf(stored) }
    HierroTextField(
        value = text,
        onValueChange = { text = it },
        label = label,
        errorMessage = error,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (text != stored) onCommit(text) }),
        modifier = modifier.onFocusChanged { if (!it.hasFocus && text != stored) onCommit(text) },
    )
}

/** Up/down controls with spoken names that include the row they move. */
@Composable
internal fun MoveActions(name: String, canUp: Boolean, canDown: Boolean, up: String, down: String, onUp: () -> Unit, onDown: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canUp) HierroSmallAction("↑", "$up $name", onClick = onUp)
        if (canDown) HierroSmallAction("↓", "$down $name", onClick = onDown)
    }
}
