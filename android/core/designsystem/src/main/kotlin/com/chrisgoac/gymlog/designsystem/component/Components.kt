package com.chrisgoac.gymlog.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chrisgoac.gymlog.designsystem.theme.HierroShape
import com.chrisgoac.gymlog.designsystem.theme.HierroTheme

// ---- Primitives ----

@Composable
fun HierroText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HierroTheme.typography.body,
    color: Color = HierroTheme.colors.ink,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign ?: TextAlign.Unspecified),
        maxLines = maxLines,
    )
}

/** Blur-free offset shadow behind a bordered box; [offset] shrinks to 0 when pressed. */
private fun Modifier.hardBox(fill: Color, border: Color, shadow: Color?, shadowOffset: Dp, borderWidth: Dp): Modifier =
    this
        .drawBehind {
            if (shadow != null && shadowOffset > 0.dp) {
                val o = shadowOffset.toPx()
                drawRect(shadow, topLeft = Offset(o, o), size = size)
            }
        }
        .background(fill)
        .border(borderWidth, border)

// ---- Buttons ----

/** [Destructive]: an action that throws data away; reserved for explicit, irreversible choices. */
enum class HierroButtonVariant { Primary, Secondary, Destructive }

@Composable
fun HierroButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: HierroButtonVariant = HierroButtonVariant.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    loadingLabel: String = "Cargando",
    /** Spoken label when the visible [text] is ambiguous out of context ("Subir" -> "Subir la rutina Push"). */
    contentDescription: String? = null,
) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val active = enabled && !loading
    val fill = when {
        !enabled -> colors.surfaceMuted
        variant == HierroButtonVariant.Primary -> colors.accent
        variant == HierroButtonVariant.Destructive -> colors.error
        else -> colors.ink
    }
    val content = when {
        !enabled -> colors.textMuted
        variant == HierroButtonVariant.Primary -> colors.onAccent
        else -> colors.background
    }
    val shadowOffset = if (enabled && !pressed) HierroShape.shadowOffset else 0.dp
    val shift = if (enabled && pressed) HierroShape.shadowOffset else 0.dp
    // Outer padding reserves room for the shadow so layout is stable between states.
    // propagateMinConstraints: a fillMaxWidth()/weight() modifier stretches the visible button, not just the slot.
    Box(modifier.padding(end = HierroShape.shadowOffset, bottom = HierroShape.shadowOffset), propagateMinConstraints = true) {
        Box(
            Modifier
                .offset(shift, shift)
                .heightIn(min = HierroShape.minTouchTarget)
                .widthIn(min = HierroShape.minTouchTarget)
                .hardBox(fill, colors.border, colors.shadow, shadowOffset, if (focused) 4.dp else HierroShape.border)
                .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button, onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription ?: text
                    if (loading) stateDescription = loadingLabel
                    if (!enabled) disabled()
                }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                HierroLoadingBlocks(content, Modifier.clearAndSetSemantics {})
            } else {
                HierroText(text, style = HierroTheme.typography.label, color = content, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---- Cards ----

@Composable
fun HierroCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Single spoken label for a clickable card whose visible text is split over several lines. */
    contentDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shadowOffset = if (pressed) 0.dp else HierroShape.shadowOffset
    val shift = if (pressed) HierroShape.shadowOffset else 0.dp
    Box(modifier.padding(end = HierroShape.shadowOffset, bottom = HierroShape.shadowOffset)) {
        Column(
            Modifier
                .fillMaxWidth()
                .offset(shift, shift)
                .hardBox(colors.surface, colors.border, colors.shadow, if (onClick != null) shadowOffset else HierroShape.shadowOffset, if (focused) 4.dp else HierroShape.border)
                .then(
                    if (onClick != null) {
                        Modifier
                            .heightIn(min = HierroShape.minTouchTarget)
                            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
                            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
                    } else Modifier,
                )
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

// ---- Fields ----

@Composable
fun HierroTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    errorMessage: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = if (errorMessage != null) colors.error else colors.border
    // Merged: one focus stop that announces the visible label together with the typed value.
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HierroText(label.uppercase(), style = HierroTheme.typography.label, color = if (enabled) colors.ink else colors.textMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            textStyle = HierroTheme.typography.body.copy(color = if (enabled) colors.ink else colors.textMuted),
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = HierroShape.minTouchTarget)
                .background(if (enabled) colors.surface else colors.surfaceMuted)
                .border(if (focused) 4.dp else HierroShape.border, borderColor)
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .semantics { if (errorMessage != null) error(errorMessage) },
        )
        if (errorMessage != null) {
            HierroText(
                errorMessage,
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                HierroTheme.typography.body.copy(fontSize = 14.sp),
                colors.error,
            )
        }
    }
}

// ---- States ----

@Composable
fun HierroLoadingBlocks(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(450, delayMillis = index * 150, easing = LinearEasing), RepeatMode.Reverse),
                label = "block$index",
            )
            Box(Modifier.size(10.dp).alpha(alpha).background(color))
        }
    }
}

@Composable
fun HierroLoadingState(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp).semantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HierroLoadingBlocks(HierroTheme.colors.accent, Modifier.clearAndSetSemantics {})
        HierroText(label, style = HierroTheme.typography.label, color = HierroTheme.colors.textMuted, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
fun HierroEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    HierroCard(modifier) {
        HierroText(title.uppercase(), Modifier.semantics { heading() }, HierroTheme.typography.title)
        HierroText(message, color = HierroTheme.colors.textMuted)
        if (actionLabel != null && onAction != null) HierroButton(actionLabel, onAction)
    }
}

@Composable
fun HierroErrorState(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Algo falló",
) {
    HierroCard(modifier) {
        HierroText(
            title.uppercase(),
            Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
            HierroTheme.typography.title,
            HierroTheme.colors.error,
        )
        HierroText(message)
        HierroButton(retryLabel, onRetry, variant = HierroButtonVariant.Secondary)
    }
}

// ---- Header & bottom navigation ----

@Composable
fun HierroScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = HierroTheme.colors
    Row(
        modifier.fillMaxWidth().background(colors.background).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HierroText(
            title.uppercase(),
            Modifier.weight(1f).semantics { heading() },
            HierroTheme.typography.display,
        )
        actions()
    }
}

/** Square-cornered icon-less action for headers (kept text-only: no icon set is shipped yet). */
@Composable
fun HierroHeaderAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .heightIn(min = HierroShape.minTouchTarget)
            .widthIn(min = HierroShape.minTouchTarget)
            .border(if (focused) 4.dp else HierroShape.border, colors.border)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        HierroText(text.uppercase(), style = HierroTheme.typography.label)
    }
}

@Composable
fun HierroTabBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val colors = HierroTheme.colors
    // Five single-word labels share ~72dp each, so the bar alone caps font scaling at 1.3x
    // (same trade-off as Material's navigation bar); all screen content still scales fully.
    val density = LocalDensity.current
    val capped = Density(density.density, density.fontScale.coerceAtMost(MAX_TAB_FONT_SCALE))
    CompositionLocalProvider(LocalDensity provides capped) {
        Row(
            modifier
                .fillMaxWidth()
                .selectableGroup()
                .background(colors.surface)
                .drawBehind { drawRect(colors.border, size = size.copy(height = HierroShape.border.toPx())) },
            content = content,
        )
    }
}

private const val MAX_TAB_FONT_SCALE = 1.3f

@Composable
fun RowScope.HierroTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HierroTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .background(if (selected) colors.accent else colors.surface)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .then(if (focused) Modifier.border(4.dp, colors.border) else Modifier)
            .padding(horizontal = 2.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        HierroText(
            label,
            style = HierroTheme.typography.title.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
            color = if (selected) colors.onAccent else colors.ink,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

