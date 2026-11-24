package com.danieldk.splatevpn.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import androidx.compose.ui.text.font.Font
import com.danieldk.splatevpn.R

// OnePlus / OxygenOS One Sans Font Family
val OneSans = FontFamily(
    Font(R.font.onesans_regular, FontWeight.Normal),
    Font(R.font.onesans_medium, FontWeight.Medium),
    Font(R.font.onesans_semibold, FontWeight.SemiBold),
    Font(R.font.onesans_bold, FontWeight.Bold),
    Font(R.font.onesans_bold, FontWeight.Black)
)

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Black,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleMedium = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.25.sp
    ),
    labelLarge = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp
    ),
    labelSmall = TextStyle(
        fontFamily = OneSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.1.sp
    )
)

val CyberDataLarge = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    letterSpacing = 0.5.sp
)

val CyberDataMedium = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    letterSpacing = 0.6.sp
)

val CyberLabelSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 9.sp,
    letterSpacing = 1.2.sp
)

// Use one shaping family for scripts absent from the bundled font instead of mixing baselines.
private val SystemScriptTypography = Typography.copy(
    displayLarge = Typography.displayLarge.copy(fontFamily = FontFamily.Default),
    displayMedium = Typography.displayMedium.copy(fontFamily = FontFamily.Default),
    displaySmall = Typography.displaySmall.copy(fontFamily = FontFamily.Default),
    headlineLarge = Typography.headlineLarge.copy(fontFamily = FontFamily.Default),
    headlineMedium = Typography.headlineMedium.copy(fontFamily = FontFamily.Default),
    headlineSmall = Typography.headlineSmall.copy(fontFamily = FontFamily.Default),
    titleLarge = Typography.titleLarge.copy(fontFamily = FontFamily.Default),
    titleMedium = Typography.titleMedium.copy(fontFamily = FontFamily.Default),
    titleSmall = Typography.titleSmall.copy(fontFamily = FontFamily.Default),
    bodyLarge = Typography.bodyLarge.copy(fontFamily = FontFamily.Default),
    bodyMedium = Typography.bodyMedium.copy(fontFamily = FontFamily.Default),
    bodySmall = Typography.bodySmall.copy(fontFamily = FontFamily.Default),
    labelLarge = Typography.labelLarge.copy(fontFamily = FontFamily.Default),
    labelMedium = Typography.labelMedium.copy(fontFamily = FontFamily.Default),
    labelSmall = Typography.labelSmall.copy(fontFamily = FontFamily.Default)
)

internal fun typographyFor(language: String): Typography =
    if (language in setOf("fa", "zh")) SystemScriptTypography else Typography
