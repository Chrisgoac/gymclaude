package com.chrisgoac.gymlog.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ponytail: system condensed face stands in for the web's Anton; bundling a font needs an OFL
// license file and a size budget, so it is deferred. No network font loading.
private val Condensed = FontFamily(Font(DeviceFontFamilyName("sans-serif-condensed"), FontWeight.Bold))

@Immutable
data class HierroTypography(
    val display: TextStyle,
    val title: TextStyle,
    val metric: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
)

val DefaultHierroTypography = HierroTypography(
    display = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.sp, letterSpacing = 0.4.sp),
    title = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = 0.3.sp),
    metric = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp),
    body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    label = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
)
