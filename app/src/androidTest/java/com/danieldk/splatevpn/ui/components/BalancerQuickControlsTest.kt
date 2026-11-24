package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.BalancerGoal
import com.danieldk.splatevpn.data.BalancerMetric
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BalancerQuickControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun goalAndSpeedSelectionUpdateIndependentlyAndShowDataBudget() {
        val value = mutableStateOf(BalancerPreferences(speedBudgetMiB = 128))
        compose.setContent {
            SplateVPNTheme(themeSetting = "dark", languageSetting = "en",
                performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                    BalancerQuickControls(value.value) { value.value = it }
                }
            }
        }
        compose.onNodeWithTag("balancer_goal").performClick()
        compose.onNodeWithTag("balancer_goal_option_1").performClick()
        compose.runOnIdle { assertEquals(BalancerGoal.TELEGRAM, value.value.goal) }
        compose.onNodeWithTag("balancer_quick_hint").assertTextContains("MTProto", substring = true)
        compose.onNodeWithTag("balancer_metric").performClick()
        compose.onNodeWithTag("balancer_metric_option_1").assertTextContains("uses data", substring = true).performClick()
        compose.runOnIdle {
            assertEquals(BalancerMetric.DOWNLOAD, value.value.metric)
            assertEquals(BalancerGoal.TELEGRAM, value.value.goal)
        }
        compose.onNodeWithTag("balancer_quick_hint").assertTextContains("128 MiB", substring = true)
    }

    @Test fun narrowLargeTextControlsHaveEqualWidthAndDoNotClipSelectedLabels() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                SplateVPNTheme(themeSetting = "dark", languageSetting = "en",
                    performanceSettings = UiPerformanceSettings(animationsEnabled = false)) {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                        BalancerQuickControls(BalancerPreferences(goal = BalancerGoal.YOUTUBE_RU)) {}
                    }
                }
            }
        }
        val goal = compose.onNodeWithTag("balancer_goal").fetchSemanticsNode().boundsInRoot
        val metric = compose.onNodeWithTag("balancer_metric").fetchSemanticsNode().boundsInRoot
        assertEquals(goal.width, metric.width, 1f)
        assertTrue("Controls must not overlap, including when stacked for large text", goal.right <= metric.left || goal.bottom <= metric.top)
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("YouTube (RU)", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertTrue(results.isNotEmpty())
        assertFalse(results.any { it.hasVisualOverflow })
        compose.onNodeWithTag("balancer_metric").performClick()
        compose.onNodeWithTag("balancer_metric_option_1").assertIsDisplayed()
    }
}
