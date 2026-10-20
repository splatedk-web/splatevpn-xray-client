package com.danieldk.splatevpn.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.vpn.LanEndpoint

@Composable
fun ShareLanInstructionsDialog(endpoints: List<LanEndpoint>, onDismiss: () -> Unit) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selectedIp by remember { mutableStateOf<String?>(null) }
    val endpoint = endpoints.firstOrNull { it.ipAddress == selectedIp } ?: endpoints.firstOrNull()
    fun copy(value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, strings.uiCopied, Toast.LENGTH_SHORT).show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WifiTethering, null, tint = colors.accentCyan, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(strings.shareLanDialogTitle, color = colors.textPrimary,
                    fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(strings.shareLanStep1, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Text(strings.shareLanStep2, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                if (endpoint == null) {
                    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 12,
                        accentBorder = colors.accentAmber.copy(alpha = 0.4f)).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(strings.shareLanNoEndpoints, color = colors.accentAmber, fontSize = 13.sp, lineHeight = 18.sp)
                        Text(strings.shareLanNotReady, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                } else {
                    Text(strings.shareLanActiveEndpoints, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    endpoints.forEach { candidate ->
                        val selected = candidate.ipAddress == endpoint.ipAddress
                        Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 10,
                            accentBorder = if (selected) colors.accentCyan.copy(alpha = 0.5f) else null)
                            .tactileClickable { selectedIp = candidate.ipAddress }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(lanEndpointLabel(candidate, strings), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text("${candidate.ipAddress} · HTTP ${candidate.httpPort} · SOCKS5 ${candidate.socksPort}",
                                    color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                            }
                            if (selected) Icon(Icons.Default.CheckCircle, null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                    }
                    LanCopyField(strings.shareLanHttpProxy, endpoint.httpProxyString, ::copy)
                    LanCopyField(strings.shareLanSocksProxy, endpoint.socksProxyString, ::copy)
                    HorizontalDivider(color = colors.borderSubtle)
                    Text(strings.shareLanWindowsTitle, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                    Text(strings.shareLanWindowsSettingsGuide, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                    Text(strings.shareLanWindowsInetcplGuide, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                    LanCopyField(strings.shareLanWindowsProxyString, endpoint.windowsHttpProxyString, ::copy)
                    val exceptions = strings.shareLanWindowsExceptionsValue
                    LanCopyField(strings.shareLanWindowsExceptions, exceptions, ::copy)
                    val registryPath = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
                    val enableCommand = "Set-ItemProperty -Path '$registryPath' -Name ProxyEnable -Value 1; " +
                        "Set-ItemProperty -Path '$registryPath' -Name ProxyServer -Value \"${endpoint.windowsHttpProxyString}\"; " +
                        "Set-ItemProperty -Path '$registryPath' -Name ProxyOverride -Value \"$exceptions\""
                    LanCopyField(strings.shareLanWindowsCmdEnable, enableCommand, ::copy)
                    LanCopyField(strings.shareLanWindowsCmdDisable,
                        "Set-ItemProperty -Path '$registryPath' -Name ProxyEnable -Value 0", ::copy)
                    Text(strings.shareLanStep3Mac.replace("10809", endpoint.httpPort.toString()),
                        color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                    Text(strings.shareLanStep3Telegram.replace("10809", endpoint.httpPort.toString()).replace("10808", endpoint.socksPort.toString()),
                        color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } }
    )
}

@Composable
private fun LanCopyField(title: String, value: String, onCopy: (String) -> Unit) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 10).padding(10.dp)) {
        Text(title, color = colors.textPrimary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(value, color = colors.textSecondary, fontSize = 11.5.sp, lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth())
        }
        TextButton(onClick = { onCopy(value) }, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.ContentCopy, null, tint = colors.accentCyan, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(strings.uiCopy, color = colors.accentCyan)
        }
    }
}
