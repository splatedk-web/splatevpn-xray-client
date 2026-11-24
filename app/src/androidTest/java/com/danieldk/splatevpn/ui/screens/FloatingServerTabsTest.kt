package com.danieldk.splatevpn.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.components.LocalGlassBlurEnabled
import com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class FloatingServerTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun russianLabelsAndCountsShareEachSegmentCenter() {
        compose.setContent {
            SplateVPNTheme(themeSetting = "oneplus", languageSetting = "ru") {
                CompositionLocalProvider(
                    LocalGlassBlurEnabled provides false,
                    LocalUiPerformanceSettings provides UiPerformanceSettings(animationsEnabled = false)
                ) {
                    Box(Modifier.width(360.dp).background(Color(0xFF240B13)).testTag("alignmentHeader")) {
                        ServerCategoryTabs(NodeViewMode.ALL, 536, 5, 5, 9) { }
                    }
                }
            }
        }
        assertLabelsAndCountsCentered(listOf("Узлы", "Подписки", "Личные"), listOf("536", "5/5", "9"))
        save("servers-tabs-alignment-ru.png", compose.onNodeWithTag("alignmentHeader").captureToImage().asAndroidBitmap())
    }

    @Test fun scrollingContentReachesBehindTheHeaderInsteadOfAClippedRectangle() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                CompositionLocalProvider(LocalGlassBlurEnabled provides false) {
                    FloatingServerListPanel(Modifier.width(300.dp).height(280.dp).background(Color.Black).testTag("panel"),
                        header = { Box(Modifier.fillMaxWidth().height(64.dp)) }) { inset ->
                        LazyColumn(Modifier.fillMaxSize().testTag("underlayList"), contentPadding = PaddingValues(top = inset)) {
                            items((0..19).toList()) { index ->
                                Box(Modifier.fillMaxWidth().height(60.dp).background(Color(0xFF22CC66)).testTag("row:$index"))
                            }
                        }
                    }
                }
            }
        }
        val first = compose.onNodeWithTag("panel").captureToImage()
        val initialPixel = first.toPixelMap()[first.width / 2, first.height / 12]
        assertTrue("The first item starts below the floating header", initialPixel.green < .1f)
        compose.onNodeWithTag("underlayList").performTouchInput { swipeUp() }
        compose.waitForIdle()
        val scrolled = compose.onNodeWithTag("panel").captureToImage()
        val behindHeader = scrolled.toPixelMap()[scrolled.width / 2, scrolled.height / 12]
        assertTrue("Scrolled rows must occupy the area behind the header", behindHeader.green > .6f)
        save("servers-overlay-underlay-qa.png", scrolled.asAndroidBitmap())
    }

    @Test fun enlargedTextFitsInsideTheCapsuleAndReducedMotionStillSelectsImmediately() {
        val mode = mutableStateOf(NodeViewMode.SUBSCRIPTIONS)
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                val deviceDensity = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalDensity provides Density(deviceDensity, 1.3f),
                    LocalGlassBlurEnabled provides false,
                    LocalUiPerformanceSettings provides UiPerformanceSettings(animationsEnabled = false)
                ) {
                    FloatingServerListPanel(Modifier.width(300.dp).height(280.dp).background(Color(0xFF05151E)).testTag("panel"),
                        header = { ServerCategoryTabs(mode.value, 1909, 6, 6, 0) { mode.value = it } }) { inset ->
                        LazyColumn(Modifier.fillMaxSize().testTag("underlayList"), contentPadding = PaddingValues(top = inset)) {
                            items((0..19).toList()) { index ->
                                Box(Modifier.fillMaxWidth().height(60.dp).background(Color(0xFF244B5B)).testTag("row:$index"))
                            }
                        }
                    }
                }
            }
        }
        val header = compose.onNodeWithTag("serverFloatingTabs").fetchSemanticsNode().boundsInRoot
        val capsule = compose.onNodeWithTag("categoryCapsule").fetchSemanticsNode().boundsInRoot
        assertTrue(capsule.top >= header.top)
        assertTrue(capsule.bottom <= header.bottom)
        assertLabelsAndCountsCentered(listOf("Nodes", "Subscriptions", "Personal"), listOf("1909", "6/6", "0"))
        compose.onNodeWithText("6/6", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Subscriptions", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(action(layouts))
                assertFalse("At 130% text scale the category label must remain readable", layouts.single().hasVisualOverflow)
            }
        val row = compose.onNodeWithTag("row:0").fetchSemanticsNode().boundsInRoot
        assertTrue("The initial row remains clear of the capsule", row.top > header.bottom)
        compose.onNodeWithTag("serverCategory:PERSONAL").performClick()
        compose.runOnIdle { assertEquals(NodeViewMode.PERSONAL, mode.value) }
        val personal = compose.onNodeWithTag("serverCategory:PERSONAL").fetchSemanticsNode().boundsInRoot
        val moved = compose.onNodeWithTag("categoryCapsule").fetchSemanticsNode().boundsInRoot
        assertEquals("Reduced motion must snap the capsule to the newly selected tab", personal.left, moved.left, 1f)
        compose.onNodeWithTag("underlayList").performTouchInput { swipeUp() }
        compose.waitForIdle()
        save("servers-tabs-large-font-qa.png", compose.onNodeWithTag("panel").captureToImage().asAndroidBitmap())
    }

    private fun assertLabelsAndCountsCentered(labels: List<String>, counts: List<String>) {
        val modes = listOf(NodeViewMode.ALL, NodeViewMode.SUBSCRIPTIONS, NodeViewMode.PERSONAL)
        var countCenterY: Float? = null
        modes.indices.forEach { index ->
            val segment = compose.onNodeWithTag("serverCategory:${modes[index].name}")
                .fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithText(labels[index], useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val count = compose.onNodeWithText(counts[index], useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("serverCategoryContent:${labels[index]}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertEquals("The icon and text group must be centered", segment.center.x, content.center.x, 1f)
            assertEquals("${labels[index]} count must share the label's axis", label.center.x, count.center.x, 1f)
            compose.onAllNodesWithTag("serverCategoryIcon:${labels[index]}", useUnmergedTree = true)
                .fetchSemanticsNodes().firstOrNull()?.let { icon ->
                    val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
                    assertEquals("Icons must remain legible", 18f * density, icon.boundsInRoot.width, 1f)
                    assertTrue("The icon belongs next to its label", label.left - icon.boundsInRoot.right < 9f * density)
                    assertEquals("The icon is centered beside title and count", content.center.y, icon.boundsInRoot.center.y, 1f)
                }
            countCenterY?.let { previous ->
                assertEquals("Counts must remain on one line when a label wraps", previous, count.center.y, 1f)
            }
            countCenterY = count.center.y
        }
    }

    private fun save(name: String, bitmap: Bitmap) {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        File(app.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
