package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateRadioButton as RadioButton
import com.danieldk.splatevpn.ui.components.SplateDropdownMenuItem as DropdownMenuItem

import com.danieldk.splatevpn.ui.components.SplateDropdownMenu as DropdownMenu

import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.testTag
import androidx.compose.material.icons.filled.Layers
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
internal fun BalancerSettingsCard(value: BalancerPreferences, autoSelectEnabled: Boolean, onChange: (BalancerPreferences) -> Unit, embedded: Boolean = false) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current.connectionTools
    var expanded by remember { mutableStateOf(false) }
    val modes = listOf(
        Triple(BalancerMode.BALANCED, strings.balanced, strings.balancedHint),
        Triple(BalancerMode.FAILOVER, strings.failover, strings.failoverHint),
        Triple(BalancerMode.INITIAL_ONLY, strings.initial, strings.initialHint)
    )
    val frame = if (embedded) Modifier else Modifier.liquidGlass(cornerRadius = 20).padding(16.dp)
    Column(Modifier.fillMaxWidth().then(frame).testTag("balancerSettings")
        .then(if (LocalUiPerformanceSettings.current.animationsEnabled) Modifier.animateContentSize() else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("balancerExpand").tactileClickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tune, null, tint = colors.accentCyan, modifier = Modifier.size(22.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(strings.balancerSettings, color = colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(modes.first { it.first == value.mode }.second, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = colors.textSecondary)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BalancerQuickControls(value, onChange)
                val resources = LocalAppResources.current
                SettingsToggleContent(Icons.Default.Layers,
                    resources.getString(com.danieldk.splatevpn.R.string.balancer_warm_standby),
                    resources.getString(com.danieldk.splatevpn.R.string.balancer_warm_standby_hint),
                    value.warmStandby, onChange = { onChange(value.copy(warmStandby = it)) })
                if (value.metric == com.danieldk.splatevpn.data.BalancerMetric.DOWNLOAD) {
                    BalancerOption(resources.getString(com.danieldk.splatevpn.R.string.balancer_speed_budget),
                        value.speedBudgetMiB, listOf(16, 64, 128, 256), {
                            resources.getString(com.danieldk.splatevpn.R.string.balancer_speed_budget_value, it)
                        }) { onChange(value.copy(speedBudgetMiB = it)) }
                }
                if (!autoSelectEnabled) Text(strings.disabledHint, color = colors.accentAmber, fontSize = 12.sp)
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    modes.forEach { (mode, title, hint) ->
                        SettingsChoiceRow(title, hint, value.mode == mode, tag = "balancerMode:$mode") {
                            onChange(value.copy(mode = mode))
                        }
                    }
                }
                HorizontalDivider(color = colors.borderSubtle.copy(alpha = .5f))
                if (value.monitorsConnection) {
                    BalancerOption(strings.healthInterval, value.healthIntervalSeconds, listOf(2, 5, 10, 15, 30), strings::seconds) {
                        onChange(value.copy(healthIntervalSeconds = it))
                    }
                    BalancerOption(strings.failureCount, value.failureConfirmations, listOf(2, 3, 4, 5), Int::toString) {
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
                TextButton(onClick = { onChange(BalancerPreferences()) }, modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
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
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), color = colors.textPrimary, fontSize = 12.sp, lineHeight = 17.sp)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.widthIn(min = 80.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(label(value), fontSize = 12.sp)
                Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(label(option)) }, onClick = { expanded = false; onSelect(option) }) }
            }
        }
    }
}
