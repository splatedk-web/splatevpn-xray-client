package com.danieldk.splatevpn.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.screens.EditServerDialog
import com.danieldk.splatevpn.ui.theme.EnglishStrings
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.ui.theme.resolveThemeColors
import com.danieldk.splatevpn.vpn.LanEndpoint
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ToolWindowReviewTest {
    @get:Rule val compose = createComposeRule()
    private val still = UiPerformanceSettings(animationsEnabled = false)

    @Test fun actualLanDialogKeepsLargeTextFooterReachableAcrossLightAndDarkThemes() {
        val theme = mutableStateOf("dark")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                SplateVPNTheme(themeSetting = theme.value, languageSetting = "en", performanceSettings = still) {
                    ShareLanInstructionsDialog(listOf(LanEndpoint("ap0", "Hotspot", "192.0.2.1", 18080, 11080)),
                        {}, vpnConnected = true, sharingActive = true)
                }
            }
        }
        for (key in listOf("dark", "light")) {
            compose.runOnIdle { theme.value = key }
            compose.onNodeWithTag("lanNext").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("lanClose").assertIsDisplayed()
            val panel = compose.onNodeWithTag("lanGuideDialogPanel").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("toolDialogViewport").fetchSemanticsNode().boundsInRoot
            val next = compose.onNodeWithTag("lanNext").fetchSemanticsNode().boundsInRoot
            assertTrue("$key footer leaves its panel", next.bottom <= panel.bottom && next.top >= panel.top)
            assertTrue("$key panel reaches the system navigation area", panel.bottom < viewport.bottom)
        }
    }

    @Test fun serverEditorKeepsFailureVisibleAndWaitsForSaveCompletionBeforeClosing() {
        val source = ServerNode("editable", "Original server", "vless", "server.example", 443, uuid = "test-id")
        var dismissed = 0
        var attempts = 0
        var submitted: ServerNode? = null
        var complete: ((Result<Unit>) -> Unit)? = null
        compose.setContent {
            SplateVPNTheme(themeSetting = "dark", languageSetting = "en", performanceSettings = still) {
                EditServerDialog(source, { dismissed++ }) { node, done ->
                    attempts++
                    submitted = node
                    complete = done
                }
            }
        }
        compose.onNode(hasSetTextAction() and hasText(source.name)).performTextReplacement("Renamed server")
        compose.onNodeWithText(EnglishStrings.save).assertIsDisplayed()
        assertReadableAction(EnglishStrings.save, resolveThemeColors("dark", true).surface)
        compose.onNodeWithText(EnglishStrings.save).performClick().assertIsNotEnabled()
        compose.onNodeWithText(EnglishStrings.cancel).assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, attempts)
            assertEquals(0, dismissed)
            assertEquals("Renamed server", submitted?.name)
            requireNotNull(complete).invoke(Result.failure(IllegalStateException("Storage is unavailable")))
        }
        compose.onNodeWithText("Storage is unavailable").assertIsDisplayed()
        compose.onNodeWithText(EnglishStrings.save).assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(0, dismissed)
            requireNotNull(complete).invoke(Result.success(Unit))
        }
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun jsonProfileEditorOnlyRenamesWithoutChangingTheImportedConnection() {
        val raw = """{ "outbounds": [{ "protocol": "vless", "settings": { "opaque": true } }] }"""
        val source = ServerNode("json", "Imported JSON", "vless", "server.example", 443,
            uuid = "original-user", network = "xhttp", fingerprint = "", fullConfigJson = raw)
        var submitted: ServerNode? = null
        var dismissed = 0
        compose.setContent {
            SplateVPNTheme(themeSetting = "light", languageSetting = "en", performanceSettings = still) {
                EditServerDialog(source, { dismissed++ }) { node, done ->
                    submitted = node
                    done(Result.success(Unit))
                }
            }
        }
        compose.onNodeWithText(EnglishStrings.fieldAddress).assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText(source.name)).performTextReplacement("Renamed JSON")
        compose.onNodeWithText(EnglishStrings.save).assertIsDisplayed()
        assertReadableAction(EnglishStrings.save, resolveThemeColors("light", false).surface)
        compose.onNodeWithText(EnglishStrings.save).performClick()
        compose.runOnIdle {
            assertEquals(source.copy(name = "Renamed JSON"), submitted)
            assertEquals(1, dismissed)
        }
    }

    private fun assertReadableAction(label: String, background: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val color = layouts.single().layoutInput.style.color
        assertNotEquals("Action text must have a resolved foreground", Color.Unspecified, color)
        val a = color.luminance()
        val b = background.luminance()
        val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
        assertTrue("Action text contrast is too low: $contrast", contrast >= 4.5f)
    }
}
