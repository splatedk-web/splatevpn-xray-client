package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.glassTransparencyTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (value < .005f) strings.glassDefault else "${(value * 100).roundToInt()}%", color = colors.accentCyan, fontSize = 11.sp)
        }
        Text(strings.glassTransparencySubtitle, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        Slider(value = value, onValueChange = onPreview, onValueChangeFinished = onCommit,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            thumb = {
                Box(Modifier.size(width = 42.dp, height = 30.dp)
                    .shadow(3.dp, RoundedCornerShape(15.dp))
                    .background(colors.surfaceElevated, RoundedCornerShape(15.dp))
                    .border(1.dp, colors.borderMedium.copy(alpha = .65f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 14.dp, height = 3.dp).background(colors.accentCyan.copy(alpha = .45f), RoundedCornerShape(2.dp)))
                }
            },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(28.dp)) {
                    val corner = CornerRadius(size.height / 2)
                    drawRoundRect(colors.borderSubtle.copy(alpha = .55f), cornerRadius = corner)
                    if (state.value > 0f) drawRoundRect(
                        Brush.horizontalGradient(listOf(colors.accentCyan.copy(alpha = .16f), colors.accentSecondary.copy(alpha = .30f))),
                        size = Size(size.width * state.value, size.height), cornerRadius = corner)
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
