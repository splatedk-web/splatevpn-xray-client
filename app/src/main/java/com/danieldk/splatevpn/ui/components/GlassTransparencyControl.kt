package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassTransparencyControl(value: Float, onPreview: (Float) -> Unit, onCommit: () -> Unit, onReset: () -> Unit,
    blurEnabled: Boolean, onBlurChange: (Boolean) -> Unit) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val thumbScale by animateFloatAsState(if (dragging) 1.10f else 1f, label = "glass_slider_thumb")
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.glassTransparencyTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Box(Modifier.liquidGlass(cornerRadius = 9, accentBorder = colors.accentCyan.copy(alpha = .4f),
                backgroundColor = colors.accentCyan.copy(alpha = .08f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("${(value * 100).roundToInt()}%", color = colors.accentCyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(strings.glassTransparencySubtitle, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        Slider(value = value, onValueChange = onPreview, onValueChangeFinished = onCommit,
            interactionSource = interaction,
            modifier = Modifier.tvFocusOutline().fillMaxWidth().height(48.dp).testTag("glassTransparencySlider"),
            thumb = {
                Row(Modifier.size(width = 36.dp, height = 30.dp)
                    .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                    .shadow(3.dp, RoundedCornerShape(12.dp))
                    .liquidGlass(cornerRadius = 12, backgroundColor = colors.surfaceElevated,
                        accentBorder = colors.accentCyan, borderAlpha = .65f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically) {
                    repeat(2) { Box(Modifier.size(width = 2.dp, height = 11.dp)
                        .background(colors.accentCyan.copy(alpha = .75f), RoundedCornerShape(1.dp))) }
                }
            },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                    val corner = CornerRadius(size.height / 2)
                    drawRoundRect(Brush.horizontalGradient(listOf(colors.glassSurfaceElevated, colors.borderSubtle.copy(alpha = .35f))), cornerRadius = corner)
                    if (state.value > 0f) drawRoundRect(
                        Brush.horizontalGradient(listOf(colors.accentCyan.copy(alpha = .55f), colors.accentSecondary.copy(alpha = .85f))),
                        size = Size(size.width * state.value, size.height), cornerRadius = corner)
                    for (tick in 1..3) {
                        val x = size.width * tick / 4f
                        drawCircle(colors.textPrimary.copy(alpha = .32f), 1.dp.toPx(), Offset(x, size.height / 2))
                    }
                }
            })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(strings.glassMoreSolid, color = colors.textSecondary, fontSize = 10.sp)
            Text(strings.glassMoreClear, color = colors.textSecondary, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(strings.glassBlurTitle, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(strings.glassBlurSubtitle, color = colors.textSecondary, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Switch(checked = blurEnabled, onCheckedChange = onBlurChange, enabled = android.os.Build.VERSION.SDK_INT >= 31,
                modifier = Modifier.semantics { contentDescription = strings.glassBlurTitle })
        }
        if (android.os.Build.VERSION.SDK_INT < 31) Text(strings.glassLegacyHint, color = colors.textSecondary, fontSize = 10.sp)
        if (value > .005f) TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(strings.glassDefault, fontSize = 11.sp)
        }
    }
}
