package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.TorBridgeMetric
import com.danieldk.splatevpn.ui.theme.LocalAppResources
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
internal fun TorBridgeMetricSelector(metric: TorBridgeMetric, enabled: Boolean, onSelect: (TorBridgeMetric) -> Unit) {
    val r = LocalAppResources.current
    val colors = LocalAppThemeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(r.getString(R.string.tor_metric_title), color = colors.textPrimary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TorBridgeMetric.entries.forEach { value ->
                FilterChip(selected = metric == value, onClick = { onSelect(value) }, enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("torMetric:${value.id}"),
                    label = { Text(r.getString(if (value == TorBridgeMetric.LATENCY) R.string.tor_metric_latency else R.string.tor_metric_speed)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentCyan.copy(alpha = .15f),
                        selectedLabelColor = colors.accentCyan))
            }
        }
        Text(r.getString(if (metric == TorBridgeMetric.LATENCY) R.string.tor_metric_latency_hint else R.string.tor_metric_speed_hint),
            color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
}
