package com.danieldk.splatevpn.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Default / Dark Theme Color Tokens
val BackgroundDark = Color(0xFF070B14)
val BackgroundDeep = Color(0xFF030509)
val SurfaceDark = Color(0xFF0F1626)
val SurfaceElevated = Color(0xFF151E33)

// Precision Borders
val BorderSubtle = Color(0xFF1E2B42)
val BorderMedium = Color(0xFF2A3B5A)
val GlassWhite = Color(0x0FFFFFFF)
val GlassBorder = Color(0x1FFFFFFF)

// Tactical Typography
val TextPrimary = Color(0xFFF8FAFC)
val TextSecondary = Color(0xFF94A3B8)
val TextTertiary = Color(0xFF475569)

// Cyber High-Tech Accents
val PrimaryBlue = Color(0xFF3B82F6)
val AccentCyan = Color(0xFF00E5FF)
val AccentTeal = Color(0xFF00E5FF)
val AccentEmerald = Color(0xFF10B981)
val AccentAmber = Color(0xFFF59E0B)
val AccentCrimson = Color(0xFFEF4444)

// Comprehensive App Theme Palette Container
data class AppThemeColors(
    val background: Color,
    val backgroundDeep: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val borderSubtle: Color,
    val borderMedium: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accentCyan: Color,
    val accentEmerald: Color,
    val accentAmber: Color,
    val accentCrimson: Color,
    val isDark: Boolean
)

val DarkThemeColors = AppThemeColors(
    background = BackgroundDark,
    backgroundDeep = BackgroundDeep,
    surface = SurfaceDark,
    surfaceElevated = SurfaceElevated,
    borderSubtle = BorderSubtle,
    borderMedium = BorderMedium,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    textTertiary = TextTertiary,
    accentCyan = AccentCyan,
    accentEmerald = AccentEmerald,
    accentAmber = AccentAmber,
    accentCrimson = AccentCrimson,
    isDark = true
)

val AmoledThemeColors = AppThemeColors(
    background = Color(0xFF000000),
    backgroundDeep = Color(0xFF000000),
    surface = Color(0xFF07090E),
    surfaceElevated = Color(0xFF0E121A),
    borderSubtle = Color(0xFF161B26),
    borderMedium = Color(0xFF222B3D),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFA1A1AA),
    textTertiary = Color(0xFF52525B),
    accentCyan = Color(0xFF00F0FF),
    accentEmerald = Color(0xFF10B981),
    accentAmber = Color(0xFFF59E0B),
    accentCrimson = Color(0xFFEF4444),
    isDark = true
)

val LightThemeColors = AppThemeColors(
    background = Color(0xFFF1F5F9),
    backgroundDeep = Color(0xFFE2E8F0),
    surface = Color(0xFFFFFFFF),
    surfaceElevated = Color(0xFFF8FAFC),
    borderSubtle = Color(0xFFE2E8F0),
    borderMedium = Color(0xFFCBD5E1),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textTertiary = Color(0xFF94A3B8),
    accentCyan = Color(0xFF0284C7),
    accentEmerald = Color(0xFF059669),
    accentAmber = Color(0xFFD97706),
    accentCrimson = Color(0xFFDC2626),
    isDark = false
)

val LocalAppThemeColors = compositionLocalOf { DarkThemeColors }