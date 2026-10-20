package com.danieldk.splatevpn.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.BalancerMode
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.ui.theme.*

@Composable
internal fun BalancerSettingsCard(value: BalancerPreferences, autoSelectEnabled: Boolean, onChange: (BalancerPreferences) -> Unit) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current.connectionTools
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf(
        Triple(BalancerMode.BALANCED, strings.balanced, strings.balancedHint),
        Triple(BalancerMode.FAILOVER, strings.failover, strings.failoverHint),
        Triple(BalancerMode.INITIAL_ONLY, strings.initial, strings.initialHint)
    )
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14).animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Tune, null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(strings.balancerSettings, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(modes.first { it.first == value.mode }.second, color = colors.textSecondary, fontSize = 11.sp)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = colors.textSecondary)
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!autoSelectEnabled) Text(strings.disabledHint, color = colors.accentAmber, fontSize = 12.sp)
                modes.forEach { (mode, title, hint) ->
                    Row(Modifier.fillMaxWidth().clickable { onChange(value.copy(mode = mode)) }, verticalAlignment = Alignment.Top) {
                        RadioButton(selected = value.mode == mode, onClick = { onChange(value.copy(mode = mode)) })
                        Column(Modifier.weight(1f).padding(top = 10.dp)) {
                            Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(hint, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                        }
                    }
                }
                if (value.monitorsConnection) {
                    BalancerOption(strings.healthInterval, value.healthIntervalSeconds, listOf(2, 5, 10, 30), strings::seconds) {
                        onChange(value.copy(healthIntervalSeconds = it))
                    }
                    BalancerOption(strings.failureCount, value.failureConfirmations, listOf(2, 3, 5), Int::toString) {
                        onChange(value.copy(failureConfirmations = it))
                    }
                }
                if (value.optimizesConnection) {
                    BalancerOption(strings.optimizationInterval, value.optimizationMinutes, listOf(5, 15, 30, 60), strings::minutes) {
                        onChange(value.copy(optimizationMinutes = it))
                    }
                    BalancerOption(strings.cooldown, value.cooldownSeconds, listOf(60, 180, 600, 1800), { strings.minutes(it / 60) }) {
                        onChange(value.copy(cooldownSeconds = it))
                    }
                    BalancerOption(strings.improvement, value.improvementPercent, listOf(30, 40, 50, 80), { "$it%" }) {
                        onChange(value.copy(improvementPercent = it))
                    }
                }
                TextButton(onClick = { onChange(BalancerPreferences()) }, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.reset, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BalancerOption(title: String, value: Int, options: List<Int>, label: (Int) -> String, onSelect: (Int) -> Unit) {
    val colors = LocalAppThemeColors.current
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.weight(1f), color = colors.textPrimary, fontSize = 12.sp, lineHeight = 17.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(label(value), fontSize = 12.sp)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) }, onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}
