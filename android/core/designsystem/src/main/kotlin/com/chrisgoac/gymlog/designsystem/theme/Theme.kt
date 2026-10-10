package com.chrisgoac.gymlog.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val LocalHierroColors = staticCompositionLocalOf { HierroLightColors }
private val LocalHierroTypography = staticCompositionLocalOf { DefaultHierroTypography }

/** Square corners, hard 2dp borders, offset (blur-free) shadows: the Hierro geometry. */
object HierroShape {
    val border: Dp = 2.dp
    val shadowOffset: Dp = 4.dp
    val minTouchTarget: Dp = 48.dp
}

object HierroTheme {
    val colors: HierroColors
        @Composable @ReadOnlyComposable get() = LocalHierroColors.current
    val typography: HierroTypography
        @Composable @ReadOnlyComposable get() = LocalHierroTypography.current
}

@Composable
fun GymLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) HierroDarkColors else HierroLightColors
    CompositionLocalProvider(
        LocalHierroColors provides colors,
        LocalHierroTypography provides DefaultHierroTypography,
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.4f)),
        content = content,
    )
}
