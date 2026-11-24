package com.danieldk.splatevpn.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.data.UiPerformanceSettings
import com.danieldk.splatevpn.ui.screens.AppTab
import com.danieldk.splatevpn.ui.screens.FloatingLiquidDock
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.SplateVPNTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Visual review of production materials without launching MainActivity, network or animation clocks. */
class GlassHighlightsReviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun referenceBlueViolet() = review("dark", true, "glass-highlights-reference.png")
    @Test fun sunsetRed() = review("sunset", true, "glass-highlights-sunset.png")
    @Test fun highlightsDisabled() = review("sunset", false, "glass-highlights-flat.png")

    private fun review(theme: String, highlights: Boolean, screenshotName: String) {
        var buttonClicks = 0
        val dockVisits = mutableListOf<AppTab>()
        compose.setContent {
            SplateVPNTheme(themeSetting = theme, languageSetting = "ru", performanceSettings =
                UiPerformanceSettings(animationsEnabled = false, glassHighlightsEnabled = highlights)) {
                CompositionLocalProvider(LocalGlassTransparency provides .95f, LocalGlassBlurEnabled provides true) {
                    ReviewScreen(theme == "sunset", highlights, onConnect = { buttonClicks++ },
                        onDockVisit = { dockVisits += it })
                }
            }
        }
        compose.onNodeWithText("Cloudflare · VLESS").assertIsDisplayed()
        compose.onNodeWithText("Умный маршрут").assertIsDisplayed()
        compose.onNodeWithTag("reviewConnect").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("reviewFrosted").assertIsDisplayed()
        compose.waitForIdle()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null) ?: context.filesDir, screenshotName).outputStream().use {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }

        compose.onNodeWithTag("reviewConnect").performClick()
        compose.onNodeWithTag("reviewTab:1").performClick().assertIsSelected()
        compose.onNodeWithTag("mainTab:SERVERS").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals(1, buttonClicks)
            assertEquals(listOf(AppTab.SERVERS), dockVisits)
        }
    }
}

@Composable
private fun ReviewScreen(red: Boolean, highlights: Boolean, onConnect: () -> Unit, onDockVisit: (AppTab) -> Unit) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val (dockBackdrop, captureContent) = rememberDockBackdrop()
    var category by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(AppTab.HOME) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(captureContent)) {
            ReviewArtwork(red)
            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (highlights) "Свет на стекле" else "Блики отключены", color = Color.White,
                        fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (red) "Красная палитра приложения" else "Синий и фиолетовый фон",
                        color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
                }
                SlidingTabStrip(category, listOf(colors.accentCyan, colors.accentViolet), { category = it },
                    tabHeight = 60.dp) { selected ->
                    listOf("Команды", "Входящие").forEachIndexed { index, label ->
                        Column(Modifier.weight(1f).fillMaxHeight().testTag("reviewTab:$index")
                            .selectable(category == index, role = Role.Tab) { category = index },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center) {
                            if (index == 0) Text("⌘", color = Color.White, fontSize = 27.sp, lineHeight = 28.sp)
                            else Icon(Icons.Default.Inbox, null, tint = Color.White, modifier = Modifier.size(26.dp))
                            Text(label, color = if (selected == index) Color.White else Color.White.copy(alpha = .8f),
                                fontSize = 14.sp)
                        }
                    }
                }
                // Unequal corner radii expose any reflection that ignores the actual button shape.
                SplateButton(onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("reviewConnect"),
                    shape = RoundedCornerShape(topStart = 27.dp, topEnd = 12.dp, bottomEnd = 27.dp, bottomStart = 12.dp)) {
                    Text("Подключить", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, elevated = true).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Dns, null, tint = colors.accentViolet, modifier = Modifier.size(27.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Cloudflare · VLESS", color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("WS / 104.21.13.229:443", color = colors.textSecondary, fontSize = 11.sp)
                        }
                        Text("42 мс", color = colors.accentEmerald, fontSize = 11.sp,
                            modifier = Modifier.liquidGlass(cornerRadius = 9).padding(8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 18).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Умный маршрут", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f))
                    Text("Изменить  ›", color = colors.accentCyan, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Пинг" to "42 мс", "Загрузка" to "0 КБ/с", "Отдача" to "0 КБ/с").forEach { (title, value) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(title, color = colors.textSecondary, fontSize = 11.sp)
                            Text(value, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                // This sample is part of the captured content, rather than floating above it.
                // The production dock below retains its real elevation outside that capture.
                Column(Modifier.fillMaxWidth().frostedGlass(cornerRadius = 26.dp, elevation = 0.dp)
                    .testTag("reviewFrosted").padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Матовый материал", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Мягкое отражение по краям", color = colors.textSecondary, fontSize = 12.sp)
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            CompositionLocalProvider(LocalGlassBackdrop provides dockBackdrop) {
                FloatingLiquidDock(tab, { tab = it; onDockVisit(it) }, colors, strings)
            }
        }
    }
}

/** Broad diagonal shapes reveal the surface and its rim without visual noise behind labels. */
@Composable
private fun ReviewArtwork(red: Boolean) {
    Canvas(Modifier.fillMaxSize().background(if (red) Color(0xFF190305) else Color(0xFF06235E))) {
        drawRect(Brush.linearGradient(
            if (red) listOf(Color(0xFF180205), Color(0xFF65101F), Color(0xFF16050B))
            else listOf(Color(0xFF8B70E3), Color(0xFF2845A5), Color(0xFF021C57)),
            start = Offset.Zero, end = Offset(size.width, size.height)))
        val diagonal = Path().apply {
            moveTo(size.width * 1.2f, -size.height * .05f)
            lineTo(size.width * .08f, size.height)
            lineTo(size.width * .48f, size.height)
            lineTo(size.width * 1.45f, 0f)
            close()
        }
        drawPath(diagonal, Brush.linearGradient(
            if (red) listOf(Color(0xFFDD2842).copy(alpha = .65f), Color(0xFF4C0815))
            else listOf(Color(0xFF9DB6FF).copy(alpha = .75f), Color(0xFF04277B)),
            start = Offset(size.width, 0f), end = Offset(0f, size.height)))
        drawLine(if (red) Color(0xFFFF8B9A).copy(alpha = .25f) else Color(0xFFAEC8FF).copy(alpha = .45f),
            Offset(size.width * 1.2f, -size.height * .05f), Offset(size.width * .08f, size.height), strokeWidth = 2.dp.toPx())
    }
}
