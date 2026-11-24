package com.danieldk.splatevpn.ui.screens

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tappingActiveSettingsTabClearsSubsectionAndSearch() {
        val openRequest = mutableIntStateOf(0)
        lateinit var navigation: SettingsNavigationState
        compose.setContent { navigation = rememberSettingsNavigationState(openRequest.intValue) }
        compose.runOnIdle { navigation.open(SettingsCategory.ADVANCED) }
        lateinit var previous: SettingsNavigationState
        compose.runOnIdle {
            assertEquals(SettingsCategory.ADVANCED, navigation.category)
            previous = navigation
            openRequest.intValue++
        }
        compose.runOnIdle {
            assertNotSame(previous, navigation)
            assertNull(navigation.category)
            navigation.search("DNS")
            openRequest.intValue++
        }
        compose.runOnIdle {
            assertNull(navigation.category)
            assertEquals("", navigation.query)
        }
    }

    @Test fun returningFromAnotherTabStartsAtSettingsCatalog() {
        val visible = mutableStateOf(true)
        lateinit var navigation: SettingsNavigationState
        compose.setContent {
            if (visible.value) navigation = rememberSettingsNavigationState(0)
        }
        compose.runOnIdle {
            navigation.open(SettingsCategory.NETWORK)
            visible.value = false
        }
        compose.runOnIdle { visible.value = true }
        compose.runOnIdle {
            assertNull(navigation.category)
            assertEquals("", navigation.query)
        }
    }

    @Test fun shortcutOpensSynchronouslyAndReturningHomeDoesNotReopenIt() {
        val openRequest = mutableIntStateOf(0)
        val unrelatedState = mutableIntStateOf(0)
        lateinit var navigation: SettingsNavigationState
        compose.setContent {
            unrelatedState.intValue
            navigation = rememberSettingsNavigationState(openRequest.intValue, SettingsCategory.NETWORK)
        }
        compose.runOnIdle {
            assertEquals(SettingsCategory.NETWORK, navigation.category)
            navigation.home()
            unrelatedState.intValue++
        }
        compose.runOnIdle {
            assertNull(navigation.category)
            openRequest.intValue++
        }
        compose.runOnIdle { assertEquals(SettingsCategory.NETWORK, navigation.category) }
    }
}
