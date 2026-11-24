package com.danieldk.splatevpn.ui.screens

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.danieldk.splatevpn.data.AppRoutingMode
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppRoutingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val apps = listOf(AppItem("Browser", "test.browser", null), AppItem("Bank", "test.bank", null))

    @Test fun listDrawsToScreenBottomButLastCardScrollsAboveDock() {
        val manyApps = (1..40).map { AppItem("Application $it", "test.app$it", null) }
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                CompositionLocalProvider(LocalBottomDockInset provides 96.dp) {
                    AppRoutingContent(AppRoutingMode.EXCLUDE, emptySet(), false, manyApps, false, {}, { _, _ -> })
                }
            }
        }
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("appRoutingList")
        assertEquals(root.bottom.value, list.getUnclippedBoundsInRoot().bottom.value, .5f)
        list.performScrollToKey("app:test.app40")
        val last = compose.onNodeWithTag("app:test.app40").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Last card must clear the dock", last.bottom <= root.bottom - 96.dp)
    }

    @Test fun checkboxesAreDraftsAndApplyCommitsOneCompleteRule() {
        val mode = mutableStateOf(AppRoutingMode.ALL)
        val packages = mutableStateOf(emptySet<String>())
        var applied = 0
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                AppRoutingContent(mode.value, packages.value, false, apps, false, {}, { newMode, newPackages ->
                    applied++
                    mode.value = newMode
                    packages.value = newPackages
                })
            }
        }
        compose.onNodeWithTag("appModeINCLUDE").performClick()
        compose.onNodeWithTag("appRoutingApply").assertIsNotEnabled()
        compose.onNodeWithTag("appRoutingList").performScrollToKey("app:test.browser")
        compose.onNodeWithTag("app:test.browser").performClick()
        compose.runOnIdle { assertEquals(0, applied); assertEquals(AppRoutingMode.ALL, mode.value) }
        compose.onNodeWithTag("appRoutingApply").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, applied)
            assertEquals(AppRoutingMode.INCLUDE, mode.value)
            assertEquals(setOf("test.browser"), packages.value)
        }
        compose.onNodeWithTag("appRoutingApply").assertDoesNotExist()
    }

    @Test fun exclusionsAndDiscardRemainClearWithLargeRussianText() {
        var applied = 0
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    AppRoutingContent(AppRoutingMode.ALL, emptySet(), true, apps, false, {}, { _, _ -> applied++ })
                }
            }
        }
        compose.onNodeWithTag("appModeEXCLUDE").performScrollTo().performClick()
        compose.onNodeWithTag("appRoutingApply").assertIsDisplayed().assertIsEnabled()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "app-routing-compact-review.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithTag("appRoutingDiscard").performClick()
        compose.onNodeWithTag("appRoutingApply").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, applied) }
    }

    @Test fun emptySearchAndSelectedOnlyHaveAnExplicitWayBack() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                AppRoutingContent(AppRoutingMode.EXCLUDE, emptySet(), false, apps, false, {}, { _, _ -> })
            }
        }
        val list = compose.onNodeWithTag("appRoutingList")
        list.performScrollToKey("picker")
        compose.onNodeWithTag("appSearch").performTextInput("not-installed")
        list.performScrollToKey("empty")
        compose.onNodeWithText("No applications found. Change your search.").assertIsDisplayed()
        compose.onNodeWithText("Show all applications").performClick()
        list.performScrollToKey("app:test.bank")
        compose.onNodeWithTag("app:test.bank").assertIsDisplayed()
        list.performScrollToKey("picker")
        compose.onNodeWithText("Selected only").performClick()
        list.performScrollToKey("empty")
        compose.onNodeWithText("Nothing selected yet. Show all applications to choose some.").assertIsDisplayed()
    }
}
