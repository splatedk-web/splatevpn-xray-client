package com.danieldk.splatevpn.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    private val palettes = ThemeKeys.filter { it != "system" }.associateWith { resolveThemeColors(it, true) }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    @Test fun textHierarchyRemainsReadableOnBothCardSurfaces() {
        palettes.forEach { (name, colors) ->
            listOf(colors.textPrimary, colors.textSecondary, colors.textTertiary).forEach { foreground ->
                listOf(colors.surface, colors.surfaceElevated).forEach { background ->
                    assertTrue("$name text contrast: ${contrast(foreground, background)}", contrast(foreground, background) >= 4.5f)
                }
            }
        }
    }

    @Test fun filledControlsHaveReadableForegroundInEveryTheme() {
        palettes.forEach { (name, colors) ->
            assertTrue("$name primary", contrast(colors.onAccent, colors.accentCyan) >= 4.5f)
            listOf(colors.accentSecondary, colors.accentViolet, colors.accentCrimson).forEach { fill ->
                assertTrue("$name filled accent", contrast(contentColorOn(fill), fill) >= 4.5f)
            }
        }
    }

    @Test fun lightThemeProtocolLabelsUseReadableInk() {
        palettes.filterValues { !it.isDark }.forEach { (name, colors) ->
            listOf("vless", "vmess", "trojan", "ss", "hy2", "tuic", "wg", "unknown").forEach { protocol ->
                assertTrue("$name $protocol", contrast(getProtocolColor(protocol, colors), colors.surfaceElevated) >= 4.5f)
            }
        }
    }

    @Test fun smallTextSurvivesTheGlassTintAndTopHighlight() {
        palettes.forEach { (name, colors) ->
            val surface = colors.glassSurfaceElevated.compositeOver(colors.background)
            val wash = colors.accentCyan.copy(alpha = if (colors.isDark) 0.065f else 0.045f).compositeOver(surface)
            val highlight = colors.glassSpecular.copy(alpha = if (colors.isDark) 0.06f else 0.28f).compositeOver(wash)
            assertTrue("$name glass text contrast: ${contrast(colors.textTertiary, highlight)}",
                contrast(colors.textTertiary, highlight) >= 4.5f)
        }
    }
}
