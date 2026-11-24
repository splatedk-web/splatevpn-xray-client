package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateDropdownMenuItem as DropdownMenuItem

import com.danieldk.splatevpn.ui.components.SplateDropdownMenu as DropdownMenu

import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.SplateConstants
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.lanGuide
import com.danieldk.splatevpn.vpn.LanEndpoint

@Composable
fun ShareLanInstructionsDialog(endpoints: List<LanEndpoint>, onDismiss: () -> Unit,
    vpnConnected: Boolean = false, sharingActive: Boolean = false, password: String? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    val app = LocalAppStrings.current
    val resources = com.danieldk.splatevpn.ui.theme.LocalAppResources.current
    ToolDialog(onDismiss, Modifier.testTag("lanGuideDialogPanel"), maxWidth = 640.dp,
        contentPadding = PaddingValues(0.dp)) {
        if (password != null) {
            var visible by remember { mutableStateOf(false) }
            TextButton(onClick = { visible = !visible }, modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(resources.getString(com.danieldk.splatevpn.R.string.tool_proxy_login,
                    if (visible) password else "••••••••"))
            }
            if (visible) TextButton(onClick = { clipboard.setText(AnnotatedString(password)) },
                modifier = Modifier.padding(horizontal = 12.dp)) {
                Text(resources.getString(com.danieldk.splatevpn.R.string.tool_copy_password))
            }
        }
        Box(Modifier.weight(1f, fill = false)) {
            LanGuideContent(endpoints, vpnConnected, sharingActive, onDismiss, onCopy = { value ->
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, app.uiCopied, Toast.LENGTH_SHORT).show()
            }, onOpenSource = { url -> runCatching { uri.openUri(url) } }, authenticationRequired = password != null)
        }
    }
}

@Composable
internal fun LanGuideContent(endpoints: List<LanEndpoint>, vpnConnected: Boolean, sharingActive: Boolean,
    onDismiss: () -> Unit, onCopy: (String) -> Unit, onOpenSource: (String) -> Unit, authenticationRequired: Boolean = true) {
    val app = LocalAppStrings.current
    val s = app.lanGuide
    val r = com.danieldk.splatevpn.ui.theme.LocalAppResources.current
    val c = LocalAppThemeColors.current
    var platform by rememberSaveable { mutableStateOf(LanGuidePlatform.ANDROID) }
    var linux by rememberSaveable { mutableStateOf(LanLinuxMode.GNOME) }
    var stage by rememberSaveable { mutableIntStateOf(0) }
    var selectedIp by rememberSaveable { mutableStateOf<String?>(null) }
    var platformMenu by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var troubleshooting by rememberSaveable { mutableStateOf(false) }
    val endpoint = lanGuideEndpoint(endpoints, selectedIp)
    val ready = vpnConnected && sharingActive && endpoint != null
    val host = endpoint?.ipAddress ?: s.hostPlaceholder
    val httpPort = endpoint?.httpPort ?: SplateConstants.XRAY_LAN_HTTP_PORT
    val socksPort = endpoint?.socksPort ?: SplateConstants.XRAY_LAN_SOCKS_PORT
    val platformLabel = if (platform == LanGuidePlatform.APPS) s.apps else platform.label

    Column(Modifier.fillMaxWidth().testTag("lanGuide")) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WifiTethering, null, tint = c.accentCyan, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, color = c.textPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(s.subtitle, color = c.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("lanClose")) { Icon(Icons.Default.Close, app.close, tint = c.textSecondary) }
        }
        if (stage > 0) Box(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            OutlinedButton(onClick = { platformMenu = true }, modifier = Modifier.fillMaxWidth().testTag("lanPlatformMenu")) {
                Icon(lanPlatformIcon(platform), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(platformLabel, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(platformMenu, { platformMenu = false }) {
                LanGuidePlatform.entries.forEach { choice ->
                    DropdownMenuItem(text = { Text(if (choice == LanGuidePlatform.APPS) s.apps else choice.label) },
                        leadingIcon = { Icon(lanPlatformIcon(choice), null) }, modifier = Modifier.testTag("lanChoose:${choice.name}"),
                        onClick = { platform = choice; stage = 1; advanced = false; platformMenu = false })
                }
            }
        }
        ScrollableTabRow(selectedTabIndex = stage, edgePadding = 0.dp, containerColor = Color.Transparent, contentColor = c.accentCyan) {
            s.stages.forEachIndexed { index, label ->
                Tab(selected = stage == index, onClick = { stage = index }, modifier = Modifier.testTag("lanStage:$index"),
                    text = { Text("${index + 1}. $label", fontSize = 12.sp, maxLines = 1, softWrap = false) })
            }
        }
        key(stage, platform, linux) {
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("lanGuideScroll").padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LanGuideNotice(when {
                    !vpnConnected -> s.disconnected
                    !sharingActive -> s.sharingOff
                    endpoint == null -> s.noAddress
                    else -> s.available
                }, ready)
                when (stage) {
                    0 -> {
                        Text(s.choose, color = c.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        LanGuidePlatform.entries.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { choice ->
                                    val selected = platform == choice
                                    Surface(modifier = Modifier.weight(1f).testTag("lanPlatform:${choice.name}")
                                        .liquidGlass(cornerRadius = 14, accentBorder = if (selected) c.accentCyan else null)
                                        .tvFocusOutline().selectable(selected, role = Role.RadioButton, onClick = { platform = choice }),
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color.Transparent,
                                        border = BorderStroke(1.dp, if (selected) c.accentCyan else c.borderSubtle)) {
                                        Row(Modifier.padding(12.dp).heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(lanPlatformIcon(choice), null, tint = if (selected) c.accentCyan else c.textSecondary, modifier = Modifier.size(22.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (choice == LanGuidePlatform.APPS) s.apps else choice.label, color = c.textPrimary,
                                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                        LanConnectionPicture(platform)
                        Text(s.prepare, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        s.prepareSteps.forEachIndexed { index, step -> LanNumberedStep(index + 1, if (!authenticationRequired && index == 1) step.copy(description = r.getString(com.danieldk.splatevpn.R.string.lan_auth_off_help)) else step) }
                        when (platform) {
                            LanGuidePlatform.ANDROID, LanGuidePlatform.IOS -> LanGuideNotice(s.mobileConnection)
                            LanGuidePlatform.MACOS -> LanGuideNotice(s.macConnection)
                            else -> Unit
                        }
                        Text(s.scope, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    1 -> {
                        if (endpoint == null) LanGuideNotice(s.noAddressHint)
                        else {
                            Text(s.endpoint, color = c.textPrimary, fontWeight = FontWeight.Bold)
                            if (endpoints.size > 1) Text(s.chooseEndpoint, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            endpoints.forEach { candidate ->
                                val selected = candidate.ipAddress == endpoint.ipAddress
                                Surface(modifier = Modifier.fillMaxWidth().testTag("lanEndpoint:${candidate.ipAddress}")
                                    .liquidGlass(cornerRadius = 12, accentBorder = if (selected) c.accentCyan else null)
                                    .tvFocusOutline().selectable(selected, role = Role.RadioButton, onClick = { selectedIp = candidate.ipAddress }),
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) c.accentCyan else c.borderSubtle)) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(lanEndpointLabel(candidate, app), color = c.textPrimary, fontSize = 12.sp)
                                            Text(candidate.ipAddress, color = c.accentCyan, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                                        }
                                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = c.accentCyan)
                                    }
                                }
                            }
                        }
                        if (platform == LanGuidePlatform.LINUX) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LanLinuxMode.entries.forEach { mode ->
                                FilterChip(selected = linux == mode, onClick = { linux = mode },
                                    label = { Text(if (mode == LanLinuxMode.TERMINAL) "bash / zsh" else mode.name, fontSize = 12.sp) },
                                    modifier = Modifier.testTag("lanLinux:${mode.name}"))
                            }
                        }
                        s.setup(platform, linux).let { steps -> steps.forEachIndexed { index, step ->
                            val needsAuthStep = platform in setOf(LanGuidePlatform.IOS, LanGuidePlatform.MACOS, LanGuidePlatform.APPS) && index == steps.lastIndex
                            LanNumberedStep(index + 1, if (!authenticationRequired && needsAuthStep) step.copy(description = r.getString(com.danieldk.splatevpn.R.string.lan_auth_off_help)) else step)
                        } }
                        if (platform == LanGuidePlatform.LINUX && linux == LanLinuxMode.TERMINAL) {
                            LanCodeField(s.shellEnable, endpoint?.let(::lanShellEnableCommand).orEmpty(), ready, "lanEnableCommand", onCopy)
                            Text(s.shellNote, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                        } else {
                            LanSettingsPicture(platform, host, httpPort, socksPort, ready, onCopy, authenticationRequired)
                            Text(s.diagramHint, color = c.textTertiary, fontSize = 11.sp, lineHeight = 16.sp)
                            Text(s.valuesHint, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        if (platform in listOf(LanGuidePlatform.WINDOWS, LanGuidePlatform.MACOS, LanGuidePlatform.LINUX)) {
                            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("lanAdvanced")) {
                                Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                                Text(s.advanced, modifier = Modifier.padding(start = 6.dp))
                            }
                            if (advanced) {
                                if (platform == LanGuidePlatform.WINDOWS) {
                                    Text(s.powershellNote, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                                    LanCodeField(s.enableCommand, endpoint?.let(::lanWindowsEnableCommand).orEmpty(), ready, "lanEnableCommand", onCopy)
                                }
                                LanCodeField(s.curlCommand, endpoint?.let { lanCurlCheckCommand(it, platform == LanGuidePlatform.WINDOWS) }.orEmpty(),
                                    ready, "lanCurlCommand", onCopy)
                                Text(s.curlHint, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                    2 -> {
                        Icon(Icons.AutoMirrored.Filled.FactCheck, null, tint = c.accentCyan, modifier = Modifier.size(44.dp))
                        Text(s.checkTitle, color = c.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        s.checkSteps.forEachIndexed { index, step -> LanNumberedStep(index + 1, step) }
                        LanGuideNotice(s.scope)
                        Text(s.disconnect, color = c.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(s.disable(platform, linux), color = c.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
                        if (platform == LanGuidePlatform.WINDOWS) LanCodeField(s.disableCommand, lanWindowsDisableCommand, true, "lanDisableCommand", onCopy)
                        if (platform == LanGuidePlatform.LINUX && linux == LanLinuxMode.TERMINAL)
                            LanCodeField(s.shellDisable, LAN_SHELL_DISABLE_COMMAND, true, "lanDisableCommand", onCopy)
                        TextButton(onClick = { troubleshooting = !troubleshooting }, modifier = Modifier.testTag("lanTroubleshoot")) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, null)
                            Text(s.troubleshoot, modifier = Modifier.padding(start = 6.dp))
                        }
                        if (troubleshooting) s.troubles.forEachIndexed { index, step -> LanNumberedStep(index + 1, step) }
                    }
                }
                if (stage > 0) TextButton(onClick = { onOpenSource(s.sourceUrl(platform, linux)) }, modifier = Modifier.testTag("lanSource")) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Text(s.source, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        HorizontalDivider(color = c.borderSubtle)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (stage > 0) OutlinedButton(onClick = { stage-- }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("lanBack")) { Text(s.back) }
            Button(onClick = { if (stage < 2) stage++ else onDismiss() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("lanNext")) {
                Text(if (stage == 2) s.done else s.next)
                if (stage < 2) Icon(Icons.Default.ChevronRight, null, modifier = Modifier.padding(start = 6.dp).size(18.dp))
            }
        }
    }
}

private fun lanPlatformIcon(platform: LanGuidePlatform): ImageVector = when (platform) {
    LanGuidePlatform.ANDROID -> Icons.Default.Android
    LanGuidePlatform.IOS -> Icons.Default.PhoneIphone
    LanGuidePlatform.WINDOWS -> Icons.Default.DesktopWindows
    LanGuidePlatform.MACOS -> Icons.Default.LaptopMac
    LanGuidePlatform.LINUX -> Icons.Default.Terminal
    LanGuidePlatform.APPS -> Icons.Default.Apps
}

@Composable
private fun LanGuideNotice(text: String, positive: Boolean = false) {
    val c = LocalAppThemeColors.current
    Surface(modifier = Modifier.liquidGlass(cornerRadius = 12, accentBorder = if (positive) c.accentCyan else null),
        shape = RoundedCornerShape(12.dp), color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(if (positive) Icons.Default.CheckCircleOutline else Icons.Default.Info, null,
                tint = if (positive) c.accentCyan else c.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, color = c.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun LanNumberedStep(number: Int, step: LanGuideStep) {
    val c = LocalAppThemeColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(modifier = Modifier.liquidGlass(cornerRadius = 9, accentBorder = c.accentCyan),
            shape = RoundedCornerShape(9.dp), color = Color.Transparent) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Text(number.toString(), color = c.accentCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(step.title, color = c.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(step.description, color = c.textSecondary, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun LanConnectionPicture(platform: LanGuidePlatform) {
    val c = LocalAppThemeColors.current
    val s = LocalAppStrings.current.lanGuide
    Row(Modifier.fillMaxWidth().border(1.dp, c.borderSubtle, RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Icon(Icons.Default.Smartphone, null, tint = c.textPrimary, modifier = Modifier.size(40.dp))
                Icon(Icons.Default.Shield, null, tint = c.accentCyan, modifier = Modifier.size(20.dp).align(Alignment.BottomEnd))
            }
            Text("SplateVPN", color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(s.phone, color = c.textSecondary, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.WifiTethering, null, tint = c.accentCyan, modifier = Modifier.size(28.dp))
            Text(s.via, color = c.textSecondary, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(lanPlatformIcon(platform), null, tint = c.accentCyan, modifier = Modifier.size(36.dp))
            Text(if (platform == LanGuidePlatform.APPS) s.apps else platform.label, color = c.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LanSettingsPicture(platform: LanGuidePlatform, host: String, httpPort: Int, socksPort: Int, ready: Boolean, onCopy: (String) -> Unit, authenticationRequired: Boolean) {
    val c = LocalAppThemeColors.current
    val s = LocalAppStrings.current.lanGuide
    Column(Modifier.fillMaxWidth().testTag("lanIllustration:${platform.name}")
        .border(1.dp, c.accentCyan.copy(alpha = .35f), RoundedCornerShape(18.dp))
        .background(c.surfaceElevated, RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(lanPlatformIcon(platform), null, tint = c.accentCyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.diagram, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        LanIllustrationField(s.proxy, if (platform == LanGuidePlatform.APPS) "SOCKS5" else s.manual)
        if (platform == LanGuidePlatform.MACOS || platform == LanGuidePlatform.LINUX)
            Text("✓ ${s.webHttp}\n✓ ${s.webHttps}", color = c.accentCyan, fontSize = 12.sp, lineHeight = 20.sp)
        val hostTitle = when (platform) {
            LanGuidePlatform.ANDROID -> s.proxyHost
            LanGuidePlatform.IOS -> s.server
            else -> s.host
        }
        LanIllustrationField(hostTitle, host, "lanCopyHost", ready, onCopy)
        LanIllustrationField(if (platform == LanGuidePlatform.ANDROID) s.proxyPort else s.port,
            (if (platform == LanGuidePlatform.APPS) socksPort else httpPort).toString(), "lanCopyPort", ready, onCopy)
        if (platform == LanGuidePlatform.IOS || platform == LanGuidePlatform.APPS) LanIllustrationField(s.auth, if (authenticationRequired) LocalAppStrings.current.onUpper else LocalAppStrings.current.offUpper)
    }
}

@Composable
private fun LanIllustrationField(title: String, value: String, tag: String? = null, enabled: Boolean = false, onCopy: (String) -> Unit = {}) {
    val c = LocalAppThemeColors.current
    val app = LocalAppStrings.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = c.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        Row(Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(9.dp)).border(1.dp, c.borderSubtle, RoundedCornerShape(9.dp))
            .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = c.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp))
            if (tag != null) IconButton(onClick = { onCopy(value) }, enabled = enabled, modifier = Modifier.testTag(tag)) {
                Icon(Icons.Default.ContentCopy, "${app.uiCopy}: $title", tint = if (enabled) c.accentCyan else c.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LanCodeField(title: String, value: String, enabled: Boolean, tag: String, onCopy: (String) -> Unit) {
    val c = LocalAppThemeColors.current
    val app = LocalAppStrings.current
    Column(Modifier.fillMaxWidth().background(c.surfaceElevated, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = c.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        SelectionContainer { Text(value.ifBlank { app.lanGuide.noAddress }, color = c.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 17.sp) }
        TextButton(onClick = { onCopy(value) }, enabled = enabled && value.isNotBlank(), modifier = Modifier.align(Alignment.End).testTag(tag)) {
            Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(app.uiCopy)
        }
    }
}
