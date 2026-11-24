package com.danieldk.splatevpn.ui.screens

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.components.ToolDialog
import com.danieldk.splatevpn.ui.components.LocalTvControls
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import android.graphics.Bitmap

class QuickServerPickerTest {
    @Test fun speedModeShowsEveryMeasuredCandidateAndMarksMissingSamples() {
        val now = System.currentTimeMillis()
        val nodes = (1..3).map { QuickServerCandidate(ServerNode("$it", "Node $it", "vless", "192.0.2.1", 443), 100, now) }
        val measured = nodes.take(2).mapIndexed { index, candidate -> candidate.server.id to CandidateMeasurement(
            QuickServerSelection.profile(candidate.server), 1, emptyList(), now, BalancerGoal.GENERAL, true, if (index == 0) 2.8 else 55.5) }.toMap()
        compose.setContent { SplateVPNTheme(languageSetting = "en") { ToolDialog({}) {
            QuickServerPickerContent(nodes, QuickServerSource.ALL, "1", false, false, null,
                {}, {}, {}, {}, {}, measurements = measured, metric = BalancerMetric.DOWNLOAD)
        } } }
        compose.onNodeWithText("2.8 Mbps").assertIsDisplayed()
        compose.onNodeWithText("55.5 Mbps").assertIsDisplayed()
        compose.onNodeWithText("Mbps not measured").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir, "quick-speed-results.png").outputStream().use {
            compose.onNodeWithTag("quickServerList", useUnmergedTree = true).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @get:Rule val compose = createComposeRule()
    @Test fun activeTorCanBeSwitchedBackToVpnFromTheSameControl() {
        var modeSwitches = 0
        compose.setContent { SplateVPNTheme(languageSetting = "en") { ToolDialog({}) {
            QuickServerPickerContent(emptyList(), QuickServerSource.ALL, null, true, false, null,
                {}, { if (it == null) modeSwitches++ }, {}, {}, {})
        } } }
        compose.onNodeWithText("VPN").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, modeSwitches) }
    }
    @Test fun backgroundFailureDoesNotRemoveAndRecreateTheRow() {
        val candidate = QuickServerCandidate(ServerNode("one", "One", "vless", "192.0.2.1", 443), 50, 1000000)
        val available = mutableStateOf(setOf("one"))
        compose.setContent { SplateVPNTheme(languageSetting = "en") { ToolDialog({}) {
            QuickServerPickerContent(listOf(candidate), QuickServerSource.ALL, null, false, false, null,
                {}, {}, {}, {}, {}, availableIds = available.value)
        } } }
        val before = compose.onNodeWithTag("quickServer:one").assertIsEnabled().fetchSemanticsNode().id
        repeat(5) {
            compose.runOnIdle { available.value = emptySet() }
            assertEquals(before, compose.onNodeWithTag("quickServer:one").assertIsDisplayed().assertIsNotEnabled().fetchSemanticsNode().id)
            compose.runOnIdle { available.value = setOf("one") }
            assertEquals(before, compose.onNodeWithTag("quickServer:one").assertIsEnabled().fetchSemanticsNode().id)
        }
    }
    @Test fun opensWithoutSelectingAndSupportsSourcesNodesAndFullList() {
        var selected: String? = null
        var all = false
        val source = mutableStateOf(QuickServerSource.ALL)
        val nodes = (1..10).map { QuickServerCandidate(ServerNode("$it", "Server $it", "vless", "192.0.2.1", 443), it * 10L, 1000000) }
        compose.setContent { SplateVPNTheme(languageSetting = "en") { ToolDialog({}) {
            QuickServerPickerContent(nodes, source.value, "1", false, false, null, { source.value = it },
                { selected = it }, {}, { all = true }, {})
        } } }
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithTag("quickSource:PERSONAL").performClick()
        compose.runOnIdle { assertEquals(QuickServerSource.PERSONAL, source.value) }
        compose.onNodeWithTag("quickServerList", useUnmergedTree = true).performScrollToNode(hasTestTag("quickServer:10"))
        compose.onNodeWithTag("quickServer:10").performClick()
        compose.runOnIdle { assertEquals("10", selected) }
        compose.onNodeWithText("All servers").performClick()
        compose.runOnIdle { assertTrue(all) }
    }
    @Test fun emptyStateHasAnEscapeAndBusyStatePreventsRepeatedActions() {
        val busy = mutableStateOf(false)
        var refreshes = 0
        compose.setContent { SplateVPNTheme(languageSetting = "en") { ToolDialog({}) {
            QuickServerPickerContent(emptyList(), QuickServerSource.ALL, null, false, busy.value, null,
                {}, {}, { refreshes++ }, {}, {})
        } } }
        compose.onNodeWithText("No recently verified servers. Tap Refresh list.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh list").performClick()
        compose.runOnIdle { assertEquals(1, refreshes); busy.value = true }
        compose.onNodeWithTag("quickSource:ALL").assertIsNotEnabled()
        compose.onNodeWithText("All servers").assertIsNotEnabled()
    }
    @OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun tvHasInitialFocusAndRemoteCanChooseAServer() {
        lateinit var input: InputModeManager
        var selected: String? = null
        val candidate = QuickServerCandidate(ServerNode("one", "One", "vless", "192.0.2.1", 443), 50, 1000000)
        compose.setContent {
            input = LocalInputModeManager.current
            SplateVPNTheme(languageSetting = "en") { CompositionLocalProvider(LocalTvControls provides true) {
                ToolDialog({}) { QuickServerPickerContent(listOf(candidate), QuickServerSource.ALL, null, false, false,
                    null, {}, { selected = it }, {}, {}, {}) }
            } }
        }
        compose.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
        compose.onNodeWithTag("quickSource:ALL").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("quickServer:one").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { assertEquals("one", selected) }
    }

    @Test fun narrowRussianLayoutKeepsFiltersAndFooterAlignedWithLargeText() {
        val nodes = listOf(
            QuickServerCandidate(ServerNode("first", "Очень длинное название импортированного сервера для проверки переноса", "vless", "192.0.2.1", 443), 176, 1000000),
            QuickServerCandidate(ServerNode("second", "Второй проверенный сервер", "vless", "192.0.2.2", 443), 971, 1000000)
        )
        compose.setContent {
            SplateVPNTheme(themeSetting = "oneplus", languageSetting = "ru") {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                    ToolDialog({}, maxWidth = 320.dp) {
                        QuickServerPickerContent(nodes, QuickServerSource.ALL, "second", false, false, null,
                            {}, {}, {}, {}, {})
                    }
                }
            }
        }
        val filters = QuickServerSource.entries.map { compose.onNodeWithTag("quickSource:${it.name}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        filters.forEach { assertEquals(filters.first().width, it.width, 1f); assertEquals(filters.first().top, it.top, 1f) }
        compose.onNodeWithTag("quickSource:ALL").assertIsSelected()
        val bitmap = compose.onNodeWithTag("toolDialogViewport").captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "quick-picker-layout-ru.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Подписки", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(action(layouts))
                val layout = layouts.single()
                assertFalse("Source label must fit: size=${layout.size}, lines=${layout.lineCount}, heightOverflow=${layout.didOverflowHeight}, widthOverflow=${layout.didOverflowWidth}", layout.hasVisualOverflow)
            }
        val tor = compose.onNodeWithText("Tor").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val all = compose.onNodeWithText("Все серверы").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals("Footer actions must share a row", tor.top, all.top, 1f)
        assertEquals("Footer actions must have equal height", tor.bottom, all.bottom, 1f)
        compose.onNodeWithText("176 мс", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasText("AM", substring = true), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun shortWindowCanScrollToEveryServerAndFooter() {
        val nodes = (1..10).map { QuickServerCandidate(ServerNode("$it", "Server $it", "vless", "192.0.2.1", 443), it * 10L, 1000000) }
        compose.setContent { SplateVPNTheme(languageSetting = "en") {
            ToolDialog({}, modifier = Modifier.height(250.dp), maxWidth = 320.dp) {
                QuickServerPickerContent(nodes, QuickServerSource.ALL, null, false, false, null, {}, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("quickServerList", useUnmergedTree = true).performScrollToNode(hasTestTag("quickServer:10"))
        compose.onNodeWithTag("quickServer:10").assertIsDisplayed()
        compose.onNodeWithTag("quickServerList", useUnmergedTree = true).performScrollToNode(hasText("All servers"))
        compose.onNodeWithText("All servers").assertIsDisplayed()
    }
}
