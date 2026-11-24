package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.screens.SelectableSettingChip
import com.danieldk.splatevpn.ui.theme.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun DnsProviderChoices(selected: String, customUrl: String, checks: DnsProviderCheckState,
    canCheck: Boolean, onSelect: (String) -> Unit, onCustomUrl: (String) -> Unit, onCheck: () -> Unit, onCancel: () -> Unit) {
    val c = LocalAppThemeColors.current
    val r = LocalAppResources.current
    val strings = LocalAppStrings.current
    val endpoints = DnsProviderCatalog.endpoints(customUrl)
    val byKey = endpoints.associateBy { it.key }
    val rows = listOf(listOf("cloudflare", "google", "quad9", "adguard"), listOf("yandex", "dns_ai", "custom")) +
        SmartDnsProviders.all.drop(1).map { it.key }.chunked(3)
    DisposableEffect(Unit) { onDispose(onCancel) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { keys ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEach { key ->
                    val provider = requireNotNull(byKey[key])
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SelectableSettingChip(Modifier.fillMaxWidth().testTag("dnsProvider:$key"),
                            label = if (key == "custom") strings.dnsCustom else provider.label,
                            isSelected = selected == key, onClick = { onSelect(key) })
                        checks.results[key]?.let { result ->
                            val status = when (result.status) {
                                DnsProviderCheckStatus.WAITING -> r.getString(R.string.dns_check_waiting)
                                DnsProviderCheckStatus.CHECKING -> r.getString(R.string.dns_check_running)
                                DnsProviderCheckStatus.AVAILABLE -> r.getString(R.string.dns_check_ok, result.latencyMs ?: 0)
                                DnsProviderCheckStatus.FAILED -> r.getString(R.string.dns_check_failed)
                                DnsProviderCheckStatus.INVALID -> r.getString(R.string.dns_check_invalid)
                            }
                            val compact = when (result.status) {
                                DnsProviderCheckStatus.AVAILABLE -> "✓ " + r.getString(R.string.dns_check_latency, result.latencyMs ?: 0)
                                DnsProviderCheckStatus.FAILED -> r.getString(R.string.dns_check_no_reply_short)
                                else -> status
                            }
                            Text(compact, modifier = Modifier.testTag("dnsResult:$key").semantics { contentDescription = status },
                                fontSize = 10.sp, textAlign = TextAlign.Center,
                                color = when (result.status) {
                                    DnsProviderCheckStatus.AVAILABLE -> c.accentEmerald
                                    DnsProviderCheckStatus.FAILED, DnsProviderCheckStatus.INVALID -> c.accentCrimson
                                    else -> c.textSecondary
                                })
                        }
                    }
                }
            }
        }
        if (selected == "custom") {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(customUrl, onCustomUrl, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(strings.dnsCustomUrlHint, fontSize = 11.sp) },
                    leadingIcon = { Icon(Icons.Default.Link, null, tint = c.accentCyan, modifier = Modifier.size(18.dp)) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accentCyan, unfocusedBorderColor = c.borderMedium))
            }
        }
        Text(r.getString(R.string.dns_check_help), color = c.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        if (checks.running) {
            val complete = checks.results.values.count { it.status !in setOf(DnsProviderCheckStatus.WAITING, DnsProviderCheckStatus.CHECKING) }
            LinearProgressIndicator(progress = { complete.toFloat() / checks.results.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            GlassSettingsAction(strings.cancel, onClick = onCancel)
        } else {
            if (checks.completedAt > 0) Text(r.getString(R.string.dns_check_completed,
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(checks.completedAt))), color = c.textSecondary, fontSize = 11.sp)
            GlassSettingsAction(r.getString(R.string.dns_check_action), enabled = canCheck, onClick = onCheck)
        }
    }
}
