package com.danieldk.splatevpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun SplateVPNTheme(
    themeSetting: String = "dark",
    languageSetting: String = "system",
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val appColors = when (themeSetting.lowercase()) {
        "amoled" -> AmoledThemeColors
        "light" -> LightThemeColors
        "system" -> if (isSystemDark) DarkThemeColors else LightThemeColors
        else -> DarkThemeColors
    }
    val appStrings = getStringsForLanguage(languageSetting)

    val colorScheme = if (appColors.isDark) {
        darkColorScheme(
            primary = appColors.accentCyan,
            background = appColors.background,
            surface = appColors.surface,
            surfaceVariant = appColors.surfaceElevated,
            onPrimary = appColors.backgroundDeep,
            onBackground = appColors.textPrimary,
            onSurface = appColors.textPrimary,
            onSurfaceVariant = appColors.textSecondary,
            secondary = appColors.accentEmerald,
            outline = appColors.borderSubtle
        )
    } else {
        lightColorScheme(
            primary = appColors.accentCyan,
            background = appColors.background,
            surface = appColors.surface,
            surfaceVariant = appColors.surfaceElevated,
            onPrimary = Color.White,
            onBackground = appColors.textPrimary,
            onSurface = appColors.textPrimary,
            onSurfaceVariant = appColors.textSecondary,
            secondary = appColors.accentEmerald,
            outline = appColors.borderSubtle
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !appColors.isDark
        }
    }

    CompositionLocalProvider(
        LocalAppThemeColors provides appColors,
        LocalAppStrings provides appStrings
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}