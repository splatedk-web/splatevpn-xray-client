package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.SpeedtestError
import com.danieldk.splatevpn.data.SpeedtestState
import com.danieldk.splatevpn.ui.theme.*
import java.util.Locale

@Composable
internal fun SpeedtestPanel(state: SpeedtestState, onStart: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val tools = strings.connectionTools
    val running = state is SpeedtestState.Running
    val speed = when (state) {
        is SpeedtestState.Running -> state.currentMbps
        is SpeedtestState.Completed -> state.downloadMbps
        else -> null
    }
    val samples = when (state) {
        is SpeedtestState.Running -> state.samples
        is SpeedtestState.Completed -> state.samples
        else -> emptyList()
    }
    Column(modifier.fillMaxWidth().liquidGlass(cornerRadius = 18, accentBorder = if (running) colors.accentCyan else null)
        .padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Speed, null, Modifier.size(26.dp), tint = colors.accentCyan)
            Column(Modifier.weight(1f)) {
                Text(strings.speedtestTitle, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(tools.speedMethod, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            }
            FilledTonalIconButton(onClick = if (running) onReset else onStart, modifier = Modifier.size(44.dp)) {
                Icon(if (running) Icons.Default.Close else Icons.Default.PlayArrow,
                    if (running) tools.speedStop else tools.speedStart, tint = colors.accentCyan)
            }
        }
        if (speed != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(String.format(Locale.getDefault(), "%.1f", speed), color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(strings.speedtestSpeedUnit, color = colors.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
                Spacer(Modifier.weight(1f))
                Text(String.format(Locale.getDefault(), "%.2f %s", speed / 8f, tools.megabytesPerSecond),
                    color = colors.accentCyan, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                listOf(.25f, .5f, .75f).forEach { y -> drawLine(colors.borderSubtle, Offset(0f, size.height * y), Offset(size.width, size.height * y)) }
                if (samples.size >= 2) {
                    val ceiling = (samples.maxOrNull() ?: 1f).coerceAtLeast(1f) * 1.1f
                    val path = Path()
                    samples.forEachIndexed { i, value ->
                        val x = size.width * i / (samples.size - 1)
                        val y = size.height * (1f - (value / ceiling).coerceIn(0f, 1f))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, colors.accentCyan, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
        when (state) {
            is SpeedtestState.Running -> {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth(), color = colors.accentCyan, trackColor = colors.borderSubtle)
                Text("${strings.speedtestRunning.format((state.progress * 100).toInt())} · ${String.format(Locale.getDefault(), "%.1f %s", state.downloadedBytes / 1_000_000.0, tools.megabytes)}",
                    color = colors.textSecondary, fontSize = 11.sp)
            }
            is SpeedtestState.Completed -> {
                Text(tools.sampleDetails.format(state.totalBytes / 1_000_000.0, state.durationSeconds), color = colors.textSecondary, fontSize = 11.sp)
                Text(strings.speedtestLatency.format(if (state.pingMs >= 0) state.pingMs.toString() else "—"), color = colors.textSecondary, fontSize = 11.sp)
                Text(state.endpoint, color = colors.textTertiary, fontSize = 10.sp)
            }
            is SpeedtestState.Error -> Text(when (state.reason) {
                SpeedtestError.VPN_REQUIRED -> tools.vpnRequired
                SpeedtestError.VPN_CHANGED -> tools.vpnChanged
                SpeedtestError.TIMEOUT -> tools.timeout
                SpeedtestError.DNS -> tools.dns
                SpeedtestError.NETWORK -> tools.network
                SpeedtestError.INVALID_SAMPLE -> tools.invalidSample
            }, color = colors.accentCrimson, fontSize = 12.sp)
            else -> Unit
        }
    }
}
