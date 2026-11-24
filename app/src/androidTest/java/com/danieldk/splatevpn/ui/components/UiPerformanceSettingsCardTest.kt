package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UiPerformanceSettingsCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun eachControlReportsItsChoiceAndLightModeCanBeReversed() {
        val preferences = mutableStateOf(UiPerformanceSettings())
        val blur = mutableStateOf(true)
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    UiPerformanceSettingsCard(preferences.value, blur.value,
                        onAnimationsChanged = { preferences.value = preferences.value.copy(animationsEnabled = it) },
                        onBackgroundChanged = { preferences.value = preferences.value.copy(decorativeBackgroundEnabled = it) },
                        onHighlightsChanged = { preferences.value = preferences.value.copy(glassHighlightsEnabled = it) },
                        onLightMode = { preferences.value = UiPerformanceSettings(false, false, false); blur.value = false },
                        onRestoreEffects = { preferences.value = UiPerformanceSettings(); blur.value = true })
                }
            }
        }
        compose.onNodeWithTag("performance:animations").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("performance:background").assertIsOn().performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("performance:highlights").assertIsOn().performScrollTo().performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(UiPerformanceSettings(false, false, false), preferences.value)
            assertTrue("Only the preset should turn blur off", blur.value)
        }
        compose.onNodeWithTag("performance:lightMode").performScrollTo().assertIsEnabled().performClick().assertIsNotEnabled()
        compose.runOnIdle { assertFalse(blur.value) }
        compose.onNodeWithTag("performance:restoreEffects").performScrollTo().assertIsEnabled().performClick().assertIsNotEnabled()
        compose.onNodeWithTag("performance:animations").performScrollTo().assertIsOn()
        compose.onNodeWithTag("performance:background").performScrollTo().assertIsOn()
        compose.onNodeWithTag("performance:highlights").performScrollTo().assertIsOn()
        compose.runOnIdle { assertTrue(blur.value) }
    }
}
