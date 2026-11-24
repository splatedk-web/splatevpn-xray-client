package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import com.danieldk.splatevpn.vpn.LanEndpoint
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LanGuideTest {
    @get:Rule val compose = createComposeRule()
    private val wifi = LanEndpoint("ap0", "Hotspot", "192.0.2.1", 18080, 11080)
    private val usb = LanEndpoint("rndis0", "USB", "192.0.2.2", 28080, 21080)

    @Test fun proxyPasswordIsHiddenUntilExplicitlyRevealed() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                ShareLanInstructionsDialog(listOf(wifi), {}, true, true, password = "private-test-password")
            }
        }
        compose.onNodeWithText("private-test-password", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Proxy login: splate", substring = true).performClick()
        compose.onNodeWithText("private-test-password", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Copy password").assertIsDisplayed()
    }

    private fun choose(platform: LanGuidePlatform) {
        compose.onNodeWithTag("lanPlatformMenu").performClick()
        compose.onNodeWithTag("lanChoose:${platform.name}").performClick()
    }

    @Test fun platformAndInterfaceChoicesCopyTheCorrectParameters() {
        val copied = mutableListOf<String>()
        var dismissed = 0
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                LanGuideContent(listOf(wifi, usb), true, true, { dismissed++ }, copied::add, {})
            }
        }
        compose.onNodeWithTag("lanPlatform:IOS").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("lanNext").performClick()
        compose.onNodeWithTag("lanCopyHost").performScrollTo().performClick()
        compose.onNodeWithTag("lanCopyPort").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("192.0.2.1", "18080"), copied) }
        compose.onNodeWithTag("lanEndpoint:${usb.ipAddress}").performScrollTo().performClick()
        compose.onNodeWithTag("lanCopyHost").performScrollTo().performClick()
        compose.onNodeWithTag("lanCopyPort").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("192.0.2.2", "28080"), copied.takeLast(2)) }
        choose(LanGuidePlatform.APPS)
        compose.onNodeWithTag("lanCopyPort").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("21080", copied.last()) }
        choose(LanGuidePlatform.WINDOWS)
        compose.onNodeWithTag("lanAdvanced").performScrollTo().performClick()
        compose.onNodeWithTag("lanEnableCommand").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(copied.last().contains("http=192.0.2.2:28080;https=192.0.2.2:28080"))
        }
        choose(LanGuidePlatform.LINUX)
        compose.onNodeWithTag("lanLinux:TERMINAL").performScrollTo().performClick()
        compose.onNodeWithTag("lanEnableCommand").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(copied.last().contains("socks5h://192.0.2.2:21080")) }
        compose.onNodeWithTag("lanNext").performClick()
        compose.onNodeWithTag("lanDisableCommand").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LAN_SHELL_DISABLE_COMMAND, copied.last()) }
        compose.onNodeWithTag("lanNext").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun unavailableSharingDisablesCopyAndAddressesUpdateLive() {
        val endpoints = mutableStateOf(emptyList<LanEndpoint>())
        val connected = mutableStateOf(false)
        val active = mutableStateOf(false)
        compose.setContent {
            SplateVPNTheme(languageSetting = "en") {
                LanGuideContent(endpoints.value, connected.value, active.value, {}, {}, {})
            }
        }
        compose.onNodeWithTag("lanNext").performClick()
        compose.onNodeWithTag("lanCopyHost").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { endpoints.value = listOf(wifi); connected.value = true }
        compose.onNodeWithTag("lanCopyPort").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithTag("lanCopyHost").performScrollTo().assertIsEnabled()
        compose.runOnIdle { endpoints.value = listOf(usb) }
        compose.onNodeWithTag("lanCopyHost").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(wifi.ipAddress).assertDoesNotExist()
        compose.onAllNodesWithText(usb.ipAddress).assertCountEquals(2)
        compose.runOnIdle { endpoints.value = emptyList() }
        compose.onNodeWithTag("lanCopyHost").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(usb.ipAddress).assertDoesNotExist()
        compose.onNodeWithTag("lanNext").assertIsDisplayed()
        compose.onNodeWithTag("lanClose").assertIsDisplayed()
    }

    @Test fun russianGuideIllustrationsRenderForEveryPlatform() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru") {
                ShareLanInstructionsDialog(listOf(wifi), {}, true, true)
            }
        }
        capture("overview")
        compose.onNodeWithTag("lanNext").performClick()
        for (platform in LanGuidePlatform.entries) {
            if (platform != LanGuidePlatform.ANDROID) choose(platform)
            compose.onNodeWithTag("lanIllustration:${platform.name}").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("lanCopyHost").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("lanCopyPort").performScrollTo().assertIsDisplayed()
            capture(platform.name.lowercase())
        }
    }

    @Test fun largeRussianTextKeepsNavigationAndCopyReachable() {
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru") {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Box(Modifier.width(340.dp).height(720.dp)) {
                        LanGuideContent(listOf(wifi), true, true, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("lanPlatform:MACOS").performScrollTo().performClick()
        compose.onNodeWithTag("lanNext").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lanCopyHost").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lanCopyPort").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lanBack").assertIsDisplayed()
        compose.onNodeWithTag("lanNext").assertIsDisplayed()
        compose.onNodeWithTag("lanClose").assertIsDisplayed()
        capture("large-text")
    }

    @Test fun actualDialogShowsIllustrationAndCloses() {
        val open = mutableStateOf(true)
        compose.setContent {
            SplateVPNTheme(languageSetting = "ru") {
                if (open.value) ShareLanInstructionsDialog(listOf(wifi), { open.value = false }, true, true)
            }
        }
        compose.onNodeWithTag("lanNext").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lanCopyPort").performScrollTo().assertIsDisplayed()
        capture("dialog")
        compose.onNodeWithTag("lanClose").performClick()
        compose.onNodeWithTag("lanGuide").assertDoesNotExist()
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "lan-guide-$name.png")
        file.outputStream().use { out -> compose.onNodeWithTag("lanGuide").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out) }
    }
}
