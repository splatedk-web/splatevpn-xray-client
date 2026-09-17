package com.danieldk.splatevpn.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.glassmorphism
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
fun SettingsScreen(
    viewModel: MainViewModel
) {
    val routingMode by viewModel.routingMode.collectAsState()
    val isAutoSelect by viewModel.isAutoSelect.collectAsState()
    val dnsProvider by viewModel.dnsProvider.collectAsState()
    val xhttpMode by viewModel.xhttpMode.collectAsState()
    val autoUpdateInterval by viewModel.autoUpdateInterval.collectAsState()
    val isBlockIpv6 by viewModel.isBlockIpv6.collectAsState()
    val isRouteOnlySniffing by viewModel.isRouteOnlySniffing.collectAsState()
    val isBlockQuic by viewModel.isBlockQuic.collectAsState()
    val isFakeDns by viewModel.isFakeDns.collectAsState()
    val appTheme by viewModel.appTheme.collectAsState()
    val appLanguage by viewModel.appLanguage.collectAsState()

    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var isUpdatingSubs by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(colors.background, colors.backgroundDeep)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 100.dp)
        ) {
            Text(
                text = strings.settingsTitle,
                color = colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.settingsSubtitle,
                color = colors.textSecondary,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Section 1: Appearance & Interface (Theme & Language)
            SectionHeader(title = strings.sectionAppearance)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassmorphism(16)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Theme Selection
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.themeTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.themeSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val themes = listOf(
                            "dark" to strings.themeDark,
                            "amoled" to strings.themeAmoled,
                            "light" to strings.themeLight,
                            "system" to strings.themeSystem
                        )
                        themes.forEach { (key, label) ->
                            val selected = appTheme.lowercase() == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentCyan.copy(alpha = 0.2f) else colors.surfaceElevated)
                                    .border(1.dp, if (selected) colors.accentCyan else colors.borderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.setAppTheme(key) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) colors.accentCyan else colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.borderSubtle)

                // Language Selection
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.languageTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.languageSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val languages = listOf(
                            "ru" to strings.langRu,
                            "en" to strings.langEn,
                            "system" to strings.langSystem
                        )
                        languages.forEach { (key, label) ->
                            val selected = appLanguage.lowercase() == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentCyan.copy(alpha = 0.2f) else colors.surfaceElevated)
                                    .border(1.dp, if (selected) colors.accentCyan else colors.borderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.setAppLanguage(key) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) colors.accentCyan else colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 2: Automation & Subscriptions
            SectionHeader(title = strings.sectionAutomation)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassmorphism(16)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Auto-select lowest ping server
                SettingToggleRow(
                    icon = Icons.Default.Bolt,
                    title = strings.autoSelectTitle,
                    subtitle = strings.autoSelectSubtitle,
                    checked = isAutoSelect,
                    onCheckedChange = { viewModel.setAutoSelect(it) }
                )

                HorizontalDivider(color = colors.borderSubtle)

                // Auto-update subscriptions interval
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Update, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.autoUpdateTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.autoUpdateSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val intervals = listOf("off" to strings.intervalOff, "6h" to strings.interval6h, "12h" to strings.interval12h, "24h" to strings.interval24h)
                        intervals.forEach { (key, label) ->
                            val selected = autoUpdateInterval == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentCyan.copy(alpha = 0.2f) else colors.surfaceElevated)
                                    .border(1.dp, if (selected) colors.accentCyan else colors.borderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.setAutoUpdateInterval(key) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) colors.accentCyan else colors.textPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.borderSubtle)

                // Update all subscriptions now button
                Button(
                    onClick = {
                        isUpdatingSubs = true
                        viewModel.updateAllSubscriptions { count ->
                            isUpdatingSubs = false
                            Toast.makeText(context, String.format(strings.updatedCount, count), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isUpdatingSubs
                ) {
                    if (isUpdatingSubs) {
                        CircularProgressIndicator(color = colors.accentCyan, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isUpdatingSubs) strings.updating else strings.updateAllSubsNow, color = colors.textPrimary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 3: Routing & Anti-Censorship
            SectionHeader(title = strings.sectionRouting)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassmorphism(16)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // DNS Provider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Dns, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.dnsProviderTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.dnsProviderSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val dnsOptions = listOf(
                            "cloudflare" to "Cloudflare",
                            "google" to "Google",
                            "quad9" to "Quad9",
                            "adguard" to "AdGuard"
                        )
                        dnsOptions.forEach { (key, label) ->
                            val selected = dnsProvider.lowercase() == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentCyan.copy(alpha = 0.2f) else colors.surfaceElevated)
                                    .border(1.dp, if (selected) colors.accentCyan else colors.borderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.setDnsProvider(key) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) colors.accentCyan else colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.borderSubtle)

                // XHTTP Transport Mode
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(strings.xhttpModeTitle, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(strings.xhttpModeSubtitle, color = colors.textSecondary, fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val modes = listOf(
                            "auto" to "Auto",
                            "stream-one" to "Stream-1",
                            "stream-up" to "Stream-Up",
                            "packet-up" to "Packet"
                        )
                        modes.forEach { (key, label) ->
                            val selected = xhttpMode.lowercase() == key
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentCyan.copy(alpha = 0.2f) else colors.surfaceElevated)
                                    .border(1.dp, if (selected) colors.accentCyan else colors.borderSubtle, RoundedCornerShape(8.dp))
                                    .clickable { viewModel.setXhttpMode(key) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) colors.accentCyan else colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.borderSubtle)

                // FakeDNS
                SettingToggleRow(
                    icon = Icons.Default.Dns,
                    title = strings.fakeDnsTitle,
                    subtitle = strings.fakeDnsSubtitle,
                    checked = isFakeDns,
                    onCheckedChange = { viewModel.setFakeDns(it) }
                )

                HorizontalDivider(color = colors.borderSubtle)

                // Block QUIC
                SettingToggleRow(
                    icon = Icons.Default.Security,
                    title = strings.blockQuicTitle,
                    subtitle = strings.blockQuicSubtitle,
                    checked = isBlockQuic,
                    onCheckedChange = { viewModel.setBlockQuic(it) }
                )

                HorizontalDivider(color = colors.borderSubtle)

                // Sniffing (RouteOnly mode)
                SettingToggleRow(
                    icon = Icons.Default.Visibility,
                    title = strings.sniffingTitle,
                    subtitle = strings.sniffingSubtitle,
                    checked = isRouteOnlySniffing,
                    onCheckedChange = { viewModel.setRouteOnlySniffing(it) }
                )

                HorizontalDivider(color = colors.borderSubtle)

                // IPv6 Leak Protection
                SettingToggleRow(
                    icon = Icons.Default.Shield,
                    title = strings.blockIpv6Title,
                    subtitle = strings.blockIpv6Subtitle,
                    checked = isBlockIpv6,
                    onCheckedChange = { viewModel.setBlockIpv6(it) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 4: Core Info
            SectionHeader(title = strings.sectionCore)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassmorphism(16)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoRow(label = strings.coreVersion, value = "Xray 26.9.9 (XTLS)")
                InfoRow(label = strings.coreArch, value = "arm64-v8a (Native PIE)")
                InfoRow(label = strings.coreTunnel, value = "hev-socks5-tunnel (tun0)")
                InfoRow(label = strings.coreProtocols, value = "VLESS, VMess, Trojan, Shadowsocks, XHTTP, WS, gRPC")
                InfoRow(label = strings.coreFakeDns, value = "198.18.0.0/15 (65535 IPs)")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section 5: Diagnostics & Logs
            SectionHeader(title = strings.sectionDiagnostics)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassmorphism(16)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.diagnosticsDesc,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Button(
                    onClick = { showLogsDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentCyan.copy(alpha = 0.2f)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.accentCyan.copy(alpha = 0.5f))
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, tint = colors.accentCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        strings.openLogsButton,
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(100.dp))
        }

        if (showLogsDialog) {
            LogsDialog(
                viewModel = viewModel,
                onDismiss = { showLogsDialog = false }
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    val colors = LocalAppThemeColors.current
    Text(
        text = title,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
fun SettingToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalAppThemeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(20.dp))
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accentCyan,
                checkedTrackColor = colors.accentCyan.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    val colors = LocalAppThemeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp)
        Text(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
