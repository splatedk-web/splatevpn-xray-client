package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.BalancerGoal
import com.danieldk.splatevpn.data.BalancerMetric
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.ui.theme.LocalAppResources

@Composable
internal fun BalancerQuickControls(value: BalancerPreferences, onChange: (BalancerPreferences) -> Unit) {
    val r = LocalAppResources.current
    val goalLabels = mapOf(
        BalancerGoal.GENERAL to r.getString(R.string.bqc_goal_general),
        BalancerGoal.TELEGRAM to "Telegram",
        BalancerGoal.YOUTUBE to "YouTube",
        BalancerGoal.TIKTOK to "TikTok",
        BalancerGoal.YOUTUBE_RU to r.getString(R.string.bqc_goal_youtube_ru),
        BalancerGoal.INSTAGRAM to "Instagram",
        BalancerGoal.DISCORD to "Discord",
        BalancerGoal.WHATSAPP to "WhatsApp",
        BalancerGoal.TWITCH to "Twitch"
    )
    val metricLabels = mapOf(
        BalancerMetric.LATENCY to r.getString(R.string.bqc_metric_latency),
        BalancerMetric.DOWNLOAD to r.getString(R.string.bqc_metric_download)
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickBalancerDropdown(r.getString(R.string.bqc_goal_title), goalLabels.getValue(value.goal), goalIcon(value.goal),
                BalancerGoal.entries, value.goal, goalLabels::getValue, "balancer_goal", Modifier.weight(1f).fillMaxHeight(), optionIcon = { goalIcon(it) }) {
                onChange(value.copy(goal = it))
            }
            QuickBalancerDropdown(r.getString(R.string.bqc_metric_title), metricLabels.getValue(value.metric), rememberVectorPainter(Icons.Default.Speed),
                BalancerMetric.entries, value.metric, { metric ->
                    if (metric == BalancerMetric.DOWNLOAD) r.getString(R.string.bqc_metric_download_traffic) else metricLabels.getValue(metric)
                }, "balancer_metric", Modifier.weight(1f).fillMaxHeight()) {
                onChange(value.copy(metric = it))
            }
        }
        val goalHint = when (value.goal) {
            BalancerGoal.GENERAL -> r.getString(R.string.bqc_hint_general)
            BalancerGoal.TELEGRAM -> r.getString(R.string.bqc_hint_telegram)
            BalancerGoal.YOUTUBE -> r.getString(R.string.bqc_hint_youtube)
            BalancerGoal.TIKTOK -> r.getString(R.string.bqc_hint_tiktok)
            BalancerGoal.YOUTUBE_RU -> r.getString(R.string.bqc_hint_youtube_ru)
            BalancerGoal.INSTAGRAM, BalancerGoal.DISCORD, BalancerGoal.WHATSAPP, BalancerGoal.TWITCH ->
                r.getString(R.string.bqc_hint_https_service, goalLabels.getValue(value.goal))
        }
        val hint = if (value.metric == BalancerMetric.DOWNLOAD)
            "$goalHint ${r.getString(R.string.bqc_hint_download, value.speedBudgetMiB)}" else goalHint
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("balancer_quick_hint"))
    }
}

@Composable
private fun goalIcon(goal: BalancerGoal): Painter = when (goal) {
    BalancerGoal.GENERAL -> rememberVectorPainter(Icons.Default.TrackChanges)
    BalancerGoal.TELEGRAM -> painterResource(R.drawable.ic_service_telegram)
    BalancerGoal.YOUTUBE, BalancerGoal.YOUTUBE_RU -> painterResource(R.drawable.ic_service_youtube)
    BalancerGoal.TIKTOK -> painterResource(R.drawable.ic_service_tiktok)
    BalancerGoal.INSTAGRAM -> painterResource(R.drawable.ic_service_instagram)
    BalancerGoal.DISCORD -> painterResource(R.drawable.ic_service_discord)
    BalancerGoal.WHATSAPP -> painterResource(R.drawable.ic_service_whatsapp)
    BalancerGoal.TWITCH -> painterResource(R.drawable.ic_service_twitch)
}

@Composable
private fun <T> QuickBalancerDropdown(
    title: String, selection: String, icon: Painter,
    options: List<T>, current: T, optionLabel: (T) -> String,
    tag: String, modifier: Modifier, optionIcon: (@Composable (T) -> Painter)? = null, onSelect: (T) -> Unit
) {
    val r = LocalAppResources.current
    var expanded by remember { mutableStateOf(false) }
    val menuState = r.getString(if (expanded) R.string.bqc_expanded else R.string.bqc_collapsed)
    Box(modifier) {
        Row(Modifier.fillMaxSize().defaultMinSize(minHeight = 60.dp)
            .liquidGlass(cornerRadius = 14, innerSheen = true)
            .semantics(mergeDescendants = true) { role = Role.Button; stateDescription = menuState }
            .tactileClickable { expanded = !expanded }.testTag(tag)
            .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selection, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        SplateDropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            options.forEachIndexed { index, option ->
                SplateDropdownMenuItem(text = { Text(optionLabel(option), style = MaterialTheme.typography.bodyMedium) },
                    modifier = Modifier.testTag("${tag}_option_$index"),
                    leadingIcon = optionIcon?.let { iconFor ->
                        { Icon(iconFor(option), null, modifier = Modifier.size(18.dp)) }
                    },
                    trailingIcon = if (current == option) ({ Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    onClick = { expanded = false; onSelect(option) })
            }
        }
    }
}
