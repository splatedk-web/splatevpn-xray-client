package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import androidx.compose.foundation.layout.*
import com.danieldk.splatevpn.ui.components.liquidGlass
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.DeleteOutline
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.DomainRouteMatch
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.LocalAppResources
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.ui.screens.SelectableSettingChip
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RoutingToolsCard(viewModel: MainViewModel) {
    val r = LocalAppResources.current
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val entries by viewModel.temporaryExceptions.collectAsState()
    val permanent by viewModel.customRoutes.collectAsState()
    val profiles by viewModel.connectionProfiles.collectAsState()
    val bindings by viewModel.networkProfileBindings.collectAsState()
    val connections by viewModel.liveConnections.collectAsState()
    var network by remember { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf("") }
    var outbound by rememberSaveable { mutableStateOf("direct") }
    var minutes by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<DomainRouteMatch?>(null) }
    var inspected by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AdvancedRoutingCard(viewModel)
        GlassSettingsCard {
        Text(r.getString(R.string.network_profiles_title), style = MaterialTheme.typography.titleMedium)
        GlassSettingsHelp(r.getString(R.string.network_profiles_help))
        listOf("wifi" to R.string.network_wifi, "cellular" to R.string.network_cellular).forEach { (key, title) ->
            Text("${r.getString(title)}: ${bindings[key]?.takeIf { it.isNotBlank() } ?: r.getString(R.string.network_no_binding)}")
            Row {
                TextButton(onClick = { network = key }) { Text(r.getString(R.string.network_bind)) }
                if (!bindings[key].isNullOrBlank()) TextButton(onClick = { scope.launch { viewModel.bindNetworkProfile(key, null, "") } }) { Text(r.getString(R.string.network_remove)) }
            }
        }
        }
        GlassSettingsCard {
        Text(r.getString(R.string.rules_sites), style = MaterialTheme.typography.titleMedium)
        GlassSettingsHelp(r.getString(R.string.site_rules_help))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            OutlinedTextField(target, { target = it.take(253); inspected = false; error = null }, modifier = Modifier.fillMaxWidth(),
                label = { Text(r.getString(R.string.route_domain)) }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Public, null, tint = colors.accentCyan, modifier = Modifier.size(18.dp)) },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.accentCyan, unfocusedBorderColor = colors.borderMedium,
                    focusedTextColor = colors.textPrimary, unfocusedTextColor = colors.textPrimary))
        }
        Text(r.getString(R.string.site_rule_route), color = colors.textSecondary, fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("direct" to strings.customRouteDirect, "proxy" to strings.customRouteProxy, "block" to strings.customRouteBlock).forEach { (key, label) ->
                SelectableSettingChip(label = label, isSelected = outbound == key, onClick = { outbound = key })
            }
        }
        Text(r.getString(R.string.site_rule_duration), color = colors.textSecondary, fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableSettingChip(label = r.getString(R.string.rule_permanent), isSelected = minutes == 0, onClick = { minutes = 0 })
            listOf(15 to R.string.exception_15m, 60 to R.string.exception_1h, 1440 to R.string.exception_1d).forEach { (value, label) ->
                SelectableSettingChip(label = r.getString(label), isSelected = minutes == value, onClick = { minutes = value })
            }
        }
        GlassSettingsAction(strings.add, enabled = !busy && target.isNotBlank(), onClick = { scope.launch {
            busy = true
            try {
                val result = if (minutes == 0) runCatching {
                    val host = requireNotNull(com.danieldk.splatevpn.data.AdFilterCompiler.normalizeDomain(target))
                    viewModel.addCustomRoute("domain:$host", outbound)
                } else viewModel.addTemporaryException(target, false, outbound, minutes)
                error = if (result.isFailure) r.getString(R.string.exception_error) else null
            } finally { busy = false }
        } })
        permanent.forEach { (domain, mode) ->
            Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 12).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(domain.removePrefix("domain:").removePrefix("full:"), color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text((when (mode) { "direct" -> strings.customRouteDirect; "block" -> strings.customRouteBlock; else -> strings.customRouteProxy }) + " · " + r.getString(R.string.rule_permanent), color = colors.textSecondary, fontSize = 11.sp)
                }
                IconButton(onClick = { viewModel.removeCustomRoute(domain) }) { Icon(Icons.Default.DeleteOutline, strings.delete, tint = colors.accentCrimson, modifier = Modifier.size(20.dp)) }
            }
        }
        entries.forEach { entry ->
            Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 12).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.target, color = colors.textPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(when (entry.outbound) { "direct" -> strings.customRouteDirect; "block" -> strings.customRouteBlock; else -> strings.customRouteProxy }, color = colors.accentCyan, fontSize = 11.sp)
                    Text("${r.getString(R.string.exception_expires)}: ${DateFormat.getDateTimeInstance().format(Date(entry.expiresAt))}", color = colors.textSecondary, fontSize = 11.sp)
                }
                IconButton(onClick = { scope.launch { viewModel.removeTemporaryException(entry.id) } }) { Icon(Icons.Default.DeleteOutline, strings.delete, tint = colors.accentCrimson, modifier = Modifier.size(20.dp)) }
            }
        }
        run {
            HorizontalDivider(color = colors.borderSubtle)
            GlassSettingsHelp(r.getString(R.string.route_explanation_hint))
            GlassSettingsAction(r.getString(R.string.route_inspect), enabled = !busy && target.isNotBlank(), onClick = { scope.launch {
                busy = true
                try {
                    val result = viewModel.inspectDomain(target)
                    error = if (result.isFailure) r.getString(R.string.route_error) else null
                    match = result.getOrNull()
                    inspected = result.isSuccess
                } finally { busy = false }
            } })
            if (inspected) Text(match?.let { "${r.getString(R.string.route_rule)} ${it.rule}: ${it.selector} → ${it.outbound}" } ?: r.getString(R.string.route_no_match))
            val observed = connections.filter { it.destination.equals(target.trim(), true) }
            if (observed.isNotEmpty()) {
                Text(r.getString(R.string.route_observed))
                observed.forEach { Text("${it.network}:${it.port} → ${it.outboundTag}") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    network?.let { selected ->
        AlertDialog(onDismissRequest = { network = null }, title = { Text(r.getString(R.string.network_bind)) },
            text = { Column {
                if (profiles.isEmpty()) Text(r.getString(R.string.network_no_profiles))
                profiles.forEach { profile -> TextButton(onClick = {
                    scope.launch { viewModel.bindNetworkProfile(selected, profile.id, profile.name) }
                    network = null
                }) { Text(profile.name) } }
            } }, confirmButton = { TextButton(onClick = { network = null }) { Text(strings.close) } })
    }
}
