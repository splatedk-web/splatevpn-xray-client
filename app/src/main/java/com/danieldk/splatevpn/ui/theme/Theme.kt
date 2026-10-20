package com.danieldk.splatevpn.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Строит Material3 ColorScheme из палитры приложения, чтобы стандартные
 * компоненты (Switch, Slider, Checkbox, диалоги, bottom sheet, поля ввода)
 * автоматически подхватывали цвета выбранной темы.
 */
private fun buildColorScheme(c: AppThemeColors): ColorScheme {
    val primaryContainer = c.accentCyan.copy(alpha = if (c.isDark) 0.22f else 0.16f).compositeOver(c.surface)
    val secondaryContainer = c.accentSecondary.copy(alpha = if (c.isDark) 0.20f else 0.14f).compositeOver(c.surface)
    val tertiaryContainer = c.accentViolet.copy(alpha = if (c.isDark) 0.20f else 0.14f).compositeOver(c.surface)

    return if (c.isDark) {
        darkColorScheme(
            primary = c.accentCyan,
            onPrimary = c.onAccent,
            primaryContainer = primaryContainer,
            onPrimaryContainer = c.textPrimary,
            secondary = c.accentSecondary,
            onSecondary = contentColorOn(c.accentSecondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = c.textPrimary,
            tertiary = c.accentViolet,
            onTertiary = contentColorOn(c.accentViolet),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = c.textPrimary,
            background = c.background,
            onBackground = c.textPrimary,
            surface = c.surface,
            onSurface = c.textPrimary,
            surfaceVariant = c.surfaceElevated,
            onSurfaceVariant = c.textSecondary,
            surfaceTint = c.accentCyan,
            surfaceContainerLowest = c.backgroundDeep,
            surfaceContainerLow = c.background,
            surfaceContainer = c.surface,
            surfaceContainerHigh = c.surfaceElevated,
            surfaceContainerHighest = c.surfaceElevated,
            outline = c.borderMedium,
            outlineVariant = c.borderSubtle,
            error = c.accentCrimson,
            onError = contentColorOn(c.accentCrimson),
            scrim = Color.Black
        )
    } else {
        lightColorScheme(
            primary = c.accentCyan,
            onPrimary = c.onAccent,
            primaryContainer = primaryContainer,
            onPrimaryContainer = c.textPrimary,
            secondary = c.accentSecondary,
            onSecondary = contentColorOn(c.accentSecondary),
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = c.textPrimary,
            tertiary = c.accentViolet,
            onTertiary = contentColorOn(c.accentViolet),
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = c.textPrimary,
            background = c.background,
            onBackground = c.textPrimary,
            surface = c.surface,
            onSurface = c.textPrimary,
            surfaceVariant = c.surfaceElevated,
            onSurfaceVariant = c.textSecondary,
            surfaceTint = c.accentCyan,
            surfaceContainerLowest = c.surface,
            surfaceContainerLow = c.surfaceElevated,
            surfaceContainer = c.surfaceElevated,
            surfaceContainerHigh = c.backgroundDeep,
            surfaceContainerHighest = c.backgroundDeep,
            outline = c.borderMedium,
            outlineVariant = c.borderSubtle,
            error = c.accentCrimson,
            onError = contentColorOn(c.accentCrimson),
            scrim = Color.Black
        )
    }
}

@Composable
fun SplateVPNTheme(
    themeSetting: String = "dark",
    languageSetting: String = "system",
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val appColors = resolveThemeColors(themeSetting, isSystemDark)
    val appStrings = getStringsForLanguage(languageSetting)

    val colorScheme = remember(appColors) { buildColorScheme(appColors) }
    val selectionColors = remember(appColors) {
        TextSelectionColors(
            handleColor = appColors.accentCyan,
            backgroundColor = appColors.accentCyan.copy(alpha = 0.32f)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar/Nav bar colors are handled by enableEdgeToEdge() in SDK 35+
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !appColors.isDark
            controller.isAppearanceLightNavigationBars = !appColors.isDark
            // Убираем системную полупрозрачную подложку навигационной панели (3-кнопочная навигация),
            // иначе внизу появляется тёмная полоса поверх контента.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(
        LocalAppThemeColors provides appColors,
        LocalAppStrings provides appStrings,
        LocalTextSelectionColors provides selectionColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
