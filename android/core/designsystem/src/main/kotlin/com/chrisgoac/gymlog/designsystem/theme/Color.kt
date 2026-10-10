package com.chrisgoac.gymlog.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Hierro nativo palette, converted from the web's oklch tokens (app/globals.css).
 * Safety orange is a fill only (2.2:1 on concrete): text on it uses [onAccent], never the reverse.
 */
@Immutable
data class HierroColors(
    val background: Color,
    val ink: Color,
    val surface: Color,
    val surfaceMuted: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val error: Color,
    val border: Color,
    val shadow: Color,
    val isDark: Boolean,
)

val HierroLightColors = HierroColors(
    background = Color(0xFFD4D1CD),
    ink = Color(0xFF1B1612),
    surface = Color(0xFFF0EFEB),
    surfaceMuted = Color(0xFFBDBAB6),
    textMuted = Color(0xFF504943),
    accent = Color(0xFFF65A02),
    onAccent = Color(0xFF16100D),
    error = Color(0xFFB80010), // 4.5:1+ on concrete and chalk
    border = Color(0xFF1B1612),
    shadow = Color(0xFF1B1612),
    isDark = false,
)

val HierroDarkColors = HierroColors(
    background = Color(0xFF120F0C),
    ink = Color(0xFFEAE8E3),
    surface = Color(0xFF211C18),
    surfaceMuted = Color(0xFF322D29),
    textMuted = Color(0xFFA39D98),
    accent = Color(0xFFFF6404),
    onAccent = Color(0xFF110C08),
    error = Color(0xFFFF6B67), // 4.5:1+ on dark surface
    border = Color(0xFFEAE8E3),
    shadow = Color(0xFF000000),
    isDark = true,
)
