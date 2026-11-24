package com.danieldk.splatevpn.ui.theme

import org.junit.Assert.*
import org.junit.Test

class ThemeMigrationTest {
    @Test fun legacyPreferencesAndBackupsResolveToReplacementThemes() {
        assertEquals("cyberpunk", canonicalThemeKey("CYBERPUNK"))
        assertEquals("red", canonicalThemeKey("oneplus"))
        assertEquals(CyberpunkThemeColors, resolveThemeColors("cyberpunk", true))
        assertEquals(RedThemeColors, resolveThemeColors("oneplus", true))
        assertEquals(BackdropStyle.WINTER, resolveThemeColors("winter", false).backdrop)
        assertTrue("cyberpunk" in ThemeKeys)
        assertFalse("oneplus" in ThemeKeys)
        assertTrue("winter" in ThemeKeys && "red" in ThemeKeys)
    }

    @Test fun winterHasIndependentLightAndDarkPalettes() {
        assertTrue(resolveThemeColors("winter", false).isDark)
        assertFalse(resolveThemeColors("winter_light", true).isDark)
        assertNotEquals(WinterThemeColors.background, WinterLightThemeColors.background)
        assertEquals(BackdropStyle.WINTER, WinterLightThemeColors.backdrop)
        assertTrue("winter_light" in ThemeKeys)
    }
}
