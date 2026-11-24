package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.data.AppUiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiModeTest {
    @Test
    fun defaultsToSimpleOnUnknownOrNullId() {
        assertEquals(AppUiMode.SIMPLE, AppUiMode.fromId(null))
        assertEquals(AppUiMode.SIMPLE, AppUiMode.fromId(""))
        assertEquals(AppUiMode.SIMPLE, AppUiMode.fromId("unknown"))
        assertEquals(AppUiMode.SIMPLE, AppUiMode.fromId("SIMPLE"))
        assertEquals(AppUiMode.PRO, AppUiMode.fromId("pro"))
        assertEquals(AppUiMode.PRO, AppUiMode.fromId(" PRO "))
    }

    @Test
    fun simpleModeShowsOnlyHomeAndSettingsTabs() {
        assertEquals(listOf(AppTab.HOME, AppTab.SETTINGS), visibleAppTabs(AppUiMode.SIMPLE))
        assertEquals(AppTab.entries, visibleAppTabs(AppUiMode.PRO))
    }

    @Test
    fun simpleModeFiltersAdvancedSettingsCategories() {
        val simpleCategories = visibleSettingsCategories(AppUiMode.SIMPLE)
        assertEquals(
            listOf(SettingsCategory.APPEARANCE, SettingsCategory.PROTECTION, SettingsCategory.ABOUT),
            simpleCategories
        )
        assertFalse(SettingsCategory.ADVANCED in simpleCategories)
        assertFalse(SettingsCategory.TOR in simpleCategories)
        assertFalse(SettingsCategory.ROUTING in simpleCategories)

        val proCategories = visibleSettingsCategories(AppUiMode.PRO)
        assertTrue(proCategories.containsAll(SettingsCategory.entries))
    }
}
