package com.danieldk.splatevpn.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.data.SpeedtestError
import com.danieldk.splatevpn.data.SpeedtestState
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Rule
import org.junit.Test

class SpeedtestPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rendersProgressCompletionAndErrorsInEverySupportedLanguage() {
        val language = mutableStateOf("ru")
        val state = mutableStateOf<SpeedtestState>(SpeedtestState.Idle)
        compose.setContent { SplateVPNTheme(languageSetting = language.value) { SpeedtestPanel(state.value, {}, {}) } }
        for (locale in listOf("ru", "en", "zh-Hans", "fa")) {
            compose.runOnIdle { language.value = locale; state.value = SpeedtestState.Running(0f, 0f, 0) }
            compose.waitForIdle()
            compose.runOnIdle { state.value = SpeedtestState.Running(.7f, 12.5f, 5000000, listOf(0f, 7f, 12.5f)) }
            compose.waitForIdle()
            compose.runOnIdle { state.value = SpeedtestState.Completed(12.5f, 45, 5000000, 3.5, "example.test", listOf(1f, 12.5f)) }
            compose.waitForIdle()
            for (error in SpeedtestError.entries) {
                compose.runOnIdle { state.value = SpeedtestState.Error(error) }
                compose.waitForIdle()
            }
        }
    }
}
