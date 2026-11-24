package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.data.UpdateDownloadState
import com.danieldk.splatevpn.ui.components.glassmorphism
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onToggleConnect: () -> Unit,
    onNavigateToServers: () -> Unit
) {
    val isConnected by viewModel.isConnected.collectAsState()
    val uiMode by viewModel.uiMode.collectAsState()
    val isSimpleMode = uiMode.isSimple
    val vpnState by viewModel.vpnState.collectAsState()
    val vpnStatusMessage by viewModel.vpnStatusMessage.collectAsState()
    val savedServer by viewModel.selectedServer.collectAsState()
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val disabledIds by viewModel.disabledServerIds.collectAsState()
    val proxyServer = savedServer?.takeIf { node ->
        !isSimpleMode || viewModel.isNodeEnabled(node, disabledIds) &&
            (com.danieldk.splatevpn.data.QuickServerSelection.isPersonal(node) ||
                subscriptions.any { it.id == node.subId && it.enabled })
    }
    val protectedWtpn = proxyServer?.let { node ->
        com.danieldk.splatevpn.data.isProtectedWtpn(node.subId, subscriptions.firstOrNull { it.id == node.subId }?.url)
    } == true
    val torSettings by viewModel.torSettings.collectAsState()
    val torRuntime by viewModel.torState.collectAsState()
    val torMode = torSettings.enabled
    val selectedServer = if (torMode) com.danieldk.splatevpn.data.ServerNode(
        id = "splate-tor", name = "Tor", protocol = "tor", network = "Tor", address = "", port = 0
    ) else proxyServer
    val isAutoSelect by viewModel.isAutoSelect.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val sessionStartTime by viewModel.sessionStartTime.collectAsState()

    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current

    var showLogsDialog by remember { mutableStateOf(false) }
    var showQuickServers by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var showConnectionsDialog by remember { mutableStateOf(false) }

    val isConnecting = vpnState == com.danieldk.splatevpn.vpn.VpnState.SELECTING_SERVER ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.STARTING_CORE ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.CONNECTING ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.HEALTH_CHECKING ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.RECONNECTING

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 20.dp,
                    bottom = 20.dp + LocalBottomDockInset.current
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HomeTunnelHealthBanner(viewModel = viewModel)
            HomeUpdateBanner(viewModel = viewModel, onOpenDialog = { showUpdateDialog = true })

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .liquidGlass(
                                cornerRadius = 12,
                                accentBorder = if (isConnected) colors.accentConnected.copy(alpha = 0.6f) else colors.accentCyan.copy(alpha = 0.3f),
                                innerSheen = true
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (isConnected) colors.accentConnected else colors.accentCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "SPLATE VPN",
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = String.format(strings.coreSubtitle, com.danieldk.splatevpn.SplateConstants.XRAY_VERSION),
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Header actions: Auto in Simple mode, connections and journal in Pro mode.
                if (isSimpleMode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!torMode) {
                            val boltRotationState = if (isPinging && LocalUiPerformanceSettings.current.animationsEnabled) {
                                val boltInfinite = rememberInfiniteTransition(label = "simple_bolt_anim")
                                boltInfinite.animateFloat(
                                    initialValue = 0f,
                                    targetValue = 360f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(1000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Restart
                                    ),
                                    label = "simple_bolt_spin"
                                )
                            } else {
                                rememberUpdatedState(0f)
                            }
                            Row(
                                modifier = Modifier
                                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .liquidGlass(
                                        cornerRadius = 12,
                                        accentBorder = if (isAutoSelect) colors.accentCyan.copy(alpha = 0.60f) else null,
                                        backgroundColor = if (isAutoSelect) colors.accentCyan.copy(alpha = 0.12f) else null,
                                        innerSheen = true
                                    )
                                    .testTag("homeSimpleAutoToggle")
                                    .tactileClickable(targetScale = 0.92f) {
                                        if (isPinging) viewModel.cancelPingChecks() else viewModel.toggleAutoSelectServer()
                                    }
                                    .padding(horizontal = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = if (isAutoSelect) colors.accentCyan else colors.textSecondary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .graphicsLayer {
                                            if (isPinging) rotationZ = boltRotationState.value
                                        }
                                )
                                Text(
                                    text = strings.localized("Авто", "Auto"),
                                    color = if (isAutoSelect) colors.accentCyan else colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                    }
                } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Quick Connections Monitor Button (isolated recomposition scope)
                    HomeLiveConnectionsButton(
                        viewModel = viewModel,
                        onClick = { showConnectionsDialog = true }
                    )

                    // Quick Terminal / Diagnostics Button with tactile feedback
                    IconButton(
                        onClick = { showLogsDialog = true },
                        modifier = Modifier
                            .size(48.dp)
                            .liquidGlass(cornerRadius = 12, innerSheen = true)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = strings.logsDialogTitle,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            }

            Spacer(modifier = Modifier.height(if (isSimpleMode) 12.dp else 24.dp))

            // Status Badge & Live Timer (isolated recomposition scope)
            if (!isSimpleMode) {
                VpnStatusBadge(
                    vpnState = vpnState,
                    sessionStartTime = sessionStartTime,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    statusMessage = vpnStatusMessage
                )
            }

            AnimatedVisibility(
                visible = isConnected && (torMode || !protectedWtpn),
                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
            ) {
                HomeEgressLocationSection(
                    viewModel = viewModel,
                    hideAddress = !torMode && protectedWtpn,
                    isSimpleMode = isSimpleMode
                )
            }

            Spacer(modifier = Modifier.height(if (isSimpleMode) 10.dp else 20.dp))

            // Central Cyber Power Core
            CyberPowerCore(
                isConnected = isConnected,
                vpnState = vpnState,
                onClick = onToggleConnect
            )

            if (isSimpleMode) com.danieldk.splatevpn.ui.components.HomeConnectionModeSelector(viewModel)

            Spacer(modifier = Modifier.height(if (isSimpleMode) 12.dp else 16.dp))

            // Active Server Node Card header (hidden in Simple mode to reduce clutter)
            if (!isSimpleMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedServer == null) strings.noServerSelected else strings.activeGateway,
                    modifier = Modifier.weight(1f),
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                if (!torMode) {
                // Auto-Select Action with tactile click and rotation
                val boltRotation = if (isPinging && LocalUiPerformanceSettings.current.animationsEnabled) {
                    val boltInfinite = rememberInfiniteTransition(label = "bolt_anim")
                    val spin by boltInfinite.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "bolt_spin"
                    )
                    spin
                } else {
                    0f
                }

                Row(
                    modifier = Modifier
                        .liquidGlass(
                            cornerRadius = 10,
                            accentBorder = if (isAutoSelect) colors.accentCyan.copy(alpha = 0.5f) else null,
                            innerSheen = true
                        )
                        .heightIn(min = 48.dp).tactileClickable(targetScale = 0.92f) { if (isPinging) viewModel.cancelPingChecks() else viewModel.toggleAutoSelectServer() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isAutoSelect) colors.accentCyan else colors.textSecondary,
                        modifier = Modifier
                            .size(14.dp)
                            .graphicsLayer {
                                if (isPinging) rotationZ = boltRotation
                            }
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = when {
                            isPinging -> strings.pinging
                            isAutoSelect -> strings.autoSelectOn.removePrefix("⚡").trimStart()
                            else -> strings.autoFastest
                        },
                        color = if (isAutoSelect) colors.accentCyan else colors.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                }
            }
            }

            if (!isSimpleMode && !torMode) {
                val preferences by viewModel.balancerPreferences.collectAsState()
                com.danieldk.splatevpn.ui.components.BalancerQuickControls(preferences, viewModel::setQuickBalancerPreferences)
                Spacer(Modifier.height(8.dp))
            }

            val protoColor = getProtocolColor(selectedServer?.protocol ?: "", colors)

            if (!isSimpleMode || !torMode) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(
                        cornerRadius = 20,
                        accentBorder = if (isConnected) colors.accentEmerald.copy(alpha = 0.50f) else protoColor.copy(alpha = 0.25f),
                        innerSheen = true
                    )
                    .tactileClickable(targetScale = 0.98f) {
                        showQuickServers = true
                    }
                    .testTag("homeServerQuickPick")
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .liquidGlass(
                            cornerRadius = 14,
                            accentBorder = protoColor.copy(alpha = 0.45f),
                            backgroundColor = protoColor.copy(alpha = .08f),
                            innerSheen = false
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (selectedServer != null) Icons.Default.Dns else Icons.Default.AddCircleOutline,
                        contentDescription = null,
                        tint = if (selectedServer != null) protoColor else colors.accentCyan,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = selectedServer?.let { server ->
                                if (server.address.isBlank()) server.name else server.name.replace(server.address, "••••")
                            } ?: strings.addServerOrSub,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    if (!isSimpleMode && !torMode && selectedServer != null) {
                        Text("${selectedServer!!.protocol.uppercase()} · ${selectedServer!!.network.uppercase()}",
                            color = colors.textSecondary, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!protectedWtpn) com.danieldk.splatevpn.ui.components.SpoilerAddress(
                            address = "${selectedServer!!.address}:${selectedServer!!.port}", modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp))
                    } else {
                    Text(
                        text = if (torMode) {
                            if (torRuntime.phase == com.danieldk.splatevpn.vpn.TorPhase.STARTING) {
                                strings.localized("Подключение Tor: ", "Connecting Tor: ") + "${torRuntime.progress}%"
                            } else if (torSettings.useBridges) {
                                strings.localized("Мостов: ", "Bridges: ") + torSettings.bridges.size
                            } else strings.localized("Сеть Tor · открыть настройки", "Tor network · open settings")
                        } else strings.tapToChooseNode,
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (selectedServer != null) {
                    val ping = selectedServer!!.ping
                    val pingColor = when {
                        ping in 1..120 -> colors.accentEmerald
                        ping in 121..250 -> colors.accentAmber
                        ping > 250 -> colors.accentCrimson
                        else -> colors.textTertiary
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(pingColor.copy(alpha = 0.12f))
                            .border(1.dp, pingColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        SignalBars(pingMs = ping, isConnected = true)
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (ping > 0) "$ping ms" else "---",
                            color = pingColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.accentCyan.copy(alpha = 0.15f))
                            .border(1.dp, colors.accentCyan.copy(alpha = 0.40f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = strings.add,
                            color = colors.accentCyan,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (isSimpleMode) {
                if (torMode) com.danieldk.splatevpn.ui.components.TorSubscriptionCards(viewModel)
                else SimpleHomeServerList(viewModel = viewModel, selectedServerId = proxyServer?.id)
            }
            if (!isSimpleMode && !torMode) com.danieldk.splatevpn.ui.components.ConnectionProfilesCard(viewModel)

            Spacer(Modifier.height(20.dp))

            // Real-time Telemetry HUD with live waveform chart (isolated recomposition scope)
            if (!isSimpleMode) TelemetryHUDSection(
                viewModel = viewModel,
                isConnected = isConnected,
                selectedServer = selectedServer
            )

            var toolsExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            val ru = strings is RussianAppStrings
            Spacer(Modifier.height(16.dp))
            if (!isSimpleMode) TextButton(onClick = { toolsExpanded = !toolsExpanded }, modifier = Modifier.fillMaxWidth().testTag("homeToolsToggle")) {
                Text(if (toolsExpanded) strings.localized("Скрыть инструменты", "Hide tools")
                    else strings.localized("Инструменты соединения", "Connection tools"),
                    color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(if (toolsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = colors.textPrimary)
            }
            AnimatedVisibility(!isSimpleMode && toolsExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (isConnected) {
                        val speedtestState by viewModel.speedtestState.collectAsState()
                        SpeedtestCard(state = speedtestState,
                            onStart = viewModel::startSpeedtest, onReset = viewModel::resetSpeedtest)
                    }
                    LiveConnectionsOverviewSection(viewModel = viewModel, isConnected = isConnected,
                        onClick = { showConnectionsDialog = true })
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showQuickServers) QuickServerPickerDialog(viewModel, onDismiss = { showQuickServers = false },
        onAllServers = if (isSimpleMode) null else ({ showQuickServers = false; onNavigateToServers() }))
    if (showLogsDialog) {
        LogsDialog(
            viewModel = viewModel,
            onDismiss = { showLogsDialog = false }
        )
    }

    if (showConnectionsDialog) {
        LiveConnectionsDialog(
            viewModel = viewModel,
            onDismiss = { showConnectionsDialog = false }
        )
    }

    if (showUpdateDialog) {
        val updateInfo by viewModel.updateInfo.collectAsState()
        updateInfo?.let { info ->
            UpdateDialog(
                viewModel = viewModel,
                updateInfo = info,
                onDismiss = { showUpdateDialog = false }
            )
        }
    }
}

@Composable
private fun HomeTunnelHealthBanner(viewModel: MainViewModel) {
    val healthIssue by viewModel.tunnelHealthIssue.collectAsState()
    val colors = LocalAppThemeColors.current
    AnimatedVisibility(
        visible = healthIssue != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        healthIssue?.let { issue ->
            com.danieldk.splatevpn.ui.components.TunnelHealthBanner(
                issue = issue,
                onPrimaryAction = { viewModel.handleHealthIssuePrimaryAction(issue) },
                onSecondaryAction = { viewModel.handleHealthIssueSecondaryAction(issue) },
                onDismiss = { viewModel.dismissDnsHealthIssue() },
                colors = colors,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun HomeUpdateBanner(viewModel: MainViewModel, onOpenDialog: () -> Unit) {
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateDownloadState by viewModel.updateDownloadState.collectAsState()
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    AnimatedVisibility(
        visible = updateInfo != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        updateInfo?.let { info ->
            val bannerBorderColor = when (updateDownloadState) {
                is UpdateDownloadState.Completed -> colors.accentEmerald
                is UpdateDownloadState.Downloading -> colors.accentCyan
                is UpdateDownloadState.Error -> colors.accentCrimson
                else -> colors.accentCyan
            }
            val bannerBgColor = when (updateDownloadState) {
                is UpdateDownloadState.Completed -> colors.accentEmerald.copy(alpha = 0.12f)
                is UpdateDownloadState.Error -> colors.accentCrimson.copy(alpha = 0.10f)
                else -> colors.accentCyan.copy(alpha = 0.10f)
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .tvFocusOutline().clickable { onOpenDialog() },
                color = bannerBgColor,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, bannerBorderColor.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bannerIcon = when (updateDownloadState) {
                            is UpdateDownloadState.Completed -> Icons.Default.CheckCircle
                            is UpdateDownloadState.Error -> Icons.Default.ErrorOutline
                            else -> Icons.Default.SystemUpdate
                        }
                        Icon(
                            imageVector = bannerIcon,
                            contentDescription = null,
                            tint = bannerBorderColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            val bannerTitle = when (updateDownloadState) {
                                is UpdateDownloadState.Completed -> strings.updateDownloaded
                                is UpdateDownloadState.Downloading -> strings.updateDownloading
                                is UpdateDownloadState.Error -> strings.updateFailed
                                else -> strings.updateAvailableTitle
                            }
                            Text(
                                text = bannerTitle,
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            val bannerSubtitle = when (val st = updateDownloadState) {
                                is UpdateDownloadState.Completed -> strings.updateTapToInstall
                                is UpdateDownloadState.Downloading -> {
                                    if (st.progress >= 0f)
                                        "${(st.progress * 100).toInt()}% • v${info.version}"
                                    else
                                        "v${info.version}"
                                }
                                is UpdateDownloadState.Error -> st.updateMessage(strings)
                                else -> String.format(strings.updateAvailableDesc, info.version)
                            }
                            Text(
                                text = bannerSubtitle,
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    if (updateDownloadState is UpdateDownloadState.Downloading) {
                        val state = updateDownloadState as UpdateDownloadState.Downloading
                        Spacer(Modifier.height(8.dp))
                        if (state.progress >= 0f) {
                            val progressAnim by animateFloatAsState(
                                targetValue = state.progress.coerceIn(0f, 1f),
                                animationSpec = tween(180, easing = LinearEasing),
                                label = "banner_progress"
                            )
                            LinearProgressIndicator(
                                progress = { progressAnim },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = colors.accentAmber,
                                trackColor = colors.accentAmber.copy(alpha = 0.2f)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = colors.accentAmber,
                                trackColor = colors.accentAmber.copy(alpha = 0.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeLiveConnectionsButton(viewModel: MainViewModel, onClick: () -> Unit) {
    val liveConnectionsCount by viewModel.liveConnectionsTotalCount.collectAsState()
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    Row(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .liquidGlass(
                cornerRadius = 12,
                accentBorder = if (liveConnectionsCount > 0) colors.accentCyan.copy(alpha = 0.55f) else null,
                innerSheen = true
            )
            .tactileClickable(targetScale = 0.90f, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Hub,
            contentDescription = strings.liveConnectionsTitle,
            tint = if (liveConnectionsCount > 0) colors.accentCyan else colors.textSecondary,
            modifier = Modifier.size(17.dp)
        )
        if (liveConnectionsCount > 0) {
            Text(
                text = liveConnectionsCount.toString(),
                color = colors.accentCyan,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun HomeEgressLocationSection(
    viewModel: MainViewModel,
    hideAddress: Boolean,
    isSimpleMode: Boolean
) {
    val countryFlag by viewModel.countryFlag.collectAsState()
    val countryName by viewModel.countryName.collectAsState()
    val currentIp by viewModel.currentIp.collectAsState()
    val isDetectingIp by viewModel.isDetectingIp.collectAsState()
    com.danieldk.splatevpn.ui.components.EgressLocationCard(
        countryFlag = countryFlag,
        countryName = countryName,
        address = if (hideAddress) "" else currentIp,
        isDetecting = isDetectingIp,
        onRefresh = viewModel::refreshIpLocation,
        modifier = Modifier.padding(top = if (isSimpleMode) 4.dp else 12.dp)
    )
}

@Composable
private fun VpnStatusBadge(
    vpnState: com.danieldk.splatevpn.vpn.VpnState,
    sessionStartTime: Long,
    isConnected: Boolean,
    isConnecting: Boolean,
    statusMessage: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current

    var sessionSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sessionStartTime) {
        if (sessionStartTime > 0L) {
            while (isActive) {
                sessionSeconds = (System.currentTimeMillis() - sessionStartTime) / 1000
                delay(1000)
            }
        } else {
            sessionSeconds = 0L
        }
    }

    val sessionTimeFormatted = remember(sessionSeconds) {
        val h = sessionSeconds / 3600
        val m = (sessionSeconds % 3600) / 60
        val s = sessionSeconds % 60
        if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    val targetStatusColor = when (vpnState) {
        com.danieldk.splatevpn.vpn.VpnState.CONNECTED -> colors.accentConnected
        com.danieldk.splatevpn.vpn.VpnState.FAILED -> colors.accentCrimson
        com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED -> colors.textSecondary
        else -> colors.accentAmber
    }
    val statusColor by animateColorAsState(
        targetValue = targetStatusColor,
        animationSpec = tween(400),
        label = "status_color"
    )

    val dotPulseScaleState: State<Float> = if (isConnecting && LocalUiPerformanceSettings.current.animationsEnabled) {
        val badgeInfinite = rememberInfiniteTransition(label = "badge_anim")
        badgeInfinite.animateFloat(
            initialValue = 0.65f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "badge_dot_scale"
        )
    } else {
        rememberUpdatedState(1f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .liquidGlass(
                cornerRadius = 20,
                accentBorder = if (isConnected) colors.accentConnected.copy(alpha = 0.6f) else null,
                innerSheen = true
            )
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .graphicsLayer {
                    val scale = dotPulseScaleState.value
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        AnimatedContent(
            targetState = vpnState to statusMessage,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn(tween(200)))
                    .togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(150)))
            },
            label = "status_badge_text"
        ) { (state, customMsg) ->
            val text = when {
                !customMsg.isNullOrBlank() && (state == com.danieldk.splatevpn.vpn.VpnState.RECONNECTING ||
                        state == com.danieldk.splatevpn.vpn.VpnState.SELECTING_SERVER ||
                        state == com.danieldk.splatevpn.vpn.VpnState.FAILED) -> customMsg
                state == com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED -> strings.standbyUnprotected
                state == com.danieldk.splatevpn.vpn.VpnState.SELECTING_SERVER -> strings.stateSelectingServer
                state == com.danieldk.splatevpn.vpn.VpnState.STARTING_CORE -> strings.stateStartingCore
                state == com.danieldk.splatevpn.vpn.VpnState.CONNECTING -> strings.stateConnecting
                state == com.danieldk.splatevpn.vpn.VpnState.HEALTH_CHECKING -> strings.stateHealthChecking
                state == com.danieldk.splatevpn.vpn.VpnState.CONNECTED -> sessionTimeFormatted
                state == com.danieldk.splatevpn.vpn.VpnState.RECONNECTING -> strings.stateReconnecting
                state == com.danieldk.splatevpn.vpn.VpnState.FAILED -> strings.stateFailed
                else -> strings.standbyUnprotected
            }
            Text(
                text = text,
                color = statusColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun TelemetryHUDSection(
    viewModel: MainViewModel,
    isConnected: Boolean,
    selectedServer: com.danieldk.splatevpn.data.ServerNode?
) {
    val downloadSpeed by viewModel.downloadSpeed.collectAsState()
    val uploadSpeed by viewModel.uploadSpeed.collectAsState()
    val sessionRxBytes by viewModel.sessionRxBytes.collectAsState()
    val sessionTxBytes by viewModel.sessionTxBytes.collectAsState()
    val speedHistory by viewModel.speedHistory.collectAsState()

    TelemetryHUD(
        isConnected = isConnected,
        latency = if ((selectedServer?.ping ?: 0L) > 0) "${selectedServer?.ping} ms" else "---",
        pingMs = selectedServer?.ping ?: 0L,
        downSpeed = downloadSpeed,
        upSpeed = uploadSpeed,
        sessionRxBytes = sessionRxBytes,
        sessionTxBytes = sessionTxBytes,
        speedHistory = speedHistory
    )
}

@Composable
private fun LiveConnectionsOverviewSection(
    viewModel: MainViewModel,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val liveConnectionsCount by viewModel.liveConnectionsTotalCount.collectAsState()
    val liveConnectionsTotalHits by viewModel.liveConnectionsTotalHits.collectAsState()
    val liveProxyCount by viewModel.liveConnectionsProxyCount.collectAsState()
    val liveDirectCount by viewModel.liveConnectionsDirectCount.collectAsState()
    val recentConnections by viewModel.liveConnections.collectAsState()

    LiveConnectionsOverviewCard(
        totalCount = liveConnectionsCount,
        totalHits = liveConnectionsTotalHits,
        proxyCount = liveProxyCount,
        directCount = liveDirectCount,
        recentConnections = recentConnections.take(3),
        isConnected = isConnected,
        onClick = onClick
    )
}

@Composable
fun CyberPowerCore(
    isConnected: Boolean,
    vpnState: com.danieldk.splatevpn.vpn.VpnState,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val labelMeasurer = rememberTextMeasurer()
    val labelStyle = remember {
        TextStyle(fontSize = 11.sp, lineHeight = 22.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
    }

    val isConnecting = vpnState == com.danieldk.splatevpn.vpn.VpnState.SELECTING_SERVER ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.STARTING_CORE ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.CONNECTING ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.HEALTH_CHECKING ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.RECONNECTING
    val isFailed = vpnState == com.danieldk.splatevpn.vpn.VpnState.FAILED

    val targetColor = when {
        isFailed -> colors.accentCrimson
        isConnected -> colors.accentConnected
        isConnecting -> colors.accentAmber
        else -> colors.accentCyan
    }

    val coreColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "core_color"
    )

    val isSpinning = isConnected || isConnecting
    val infiniteTransition = if (isSpinning && LocalUiPerformanceSettings.current.animationsEnabled)
        rememberInfiniteTransition(label = "core_anim") else null

    // Read animated values only inside draw / graphicsLayer phases: 0 recompositions per frame.
    val orbitalRotationState: State<Float> = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isConnecting) 1300 else 6400,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_rotation"
    ) ?: rememberUpdatedState(0f)

    // Breathing glow aura
    val breathingGlowState: State<Float> = infiniteTransition?.animateFloat(
        initialValue = if (isConnecting) 0.40f else 0.30f,
        targetValue = if (isConnecting) 1.0f else 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isConnecting) 650 else 2200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_glow"
    ) ?: rememberUpdatedState(if (isConnecting) .70f else .55f)

    // Fluid quantum energy waves expanding outward
    val waveProgressState: State<Float> = infiniteTransition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isConnecting) 1200 else 2800,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_progress"
    ) ?: rememberUpdatedState(.5f)

    val coreText = when (vpnState) {
        com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED -> strings.coreConnect
        com.danieldk.splatevpn.vpn.VpnState.SELECTING_SERVER -> strings.coreDiscSelect
        com.danieldk.splatevpn.vpn.VpnState.STARTING_CORE -> strings.coreDiscBoot
        com.danieldk.splatevpn.vpn.VpnState.CONNECTING -> strings.coreDiscLink
        com.danieldk.splatevpn.vpn.VpnState.HEALTH_CHECKING -> strings.coreDiscVerify
        com.danieldk.splatevpn.vpn.VpnState.CONNECTED -> strings.coreConnected
        com.danieldk.splatevpn.vpn.VpnState.RECONNECTING -> strings.coreDiscRetry
        com.danieldk.splatevpn.vpn.VpnState.FAILED -> strings.coreDiscError
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(210.dp)
            .tactileClickable(targetScale = 0.94f, onClick = onClick)
    ) {
        // 1. Fluid Quantum Ripple Waves (cached stroke, draw-phase reads only)
        if (isConnected || isConnecting) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val baseRadius = 74.dp.toPx()
                        val maxExpansion = 28.dp.toPx()
                        val waveStroke = Stroke(width = 1.8.dp.toPx())
                        onDrawBehind {
                            val waveProgress = waveProgressState.value
                            val breathingGlow = breathingGlowState.value
                            val r1 = baseRadius + (maxExpansion * waveProgress)
                            val alpha1 = ((1f - waveProgress) * 0.35f * breathingGlow).coerceIn(0f, 1f)
                            drawCircle(
                                color = coreColor.copy(alpha = alpha1),
                                radius = r1,
                                center = center,
                                style = waveStroke
                            )
                            val p2 = (waveProgress + 0.5f) % 1f
                            val r2 = baseRadius + (maxExpansion * p2)
                            val alpha2 = ((1f - p2) * 0.35f * breathingGlow).coerceIn(0f, 1f)
                            drawCircle(
                                color = coreColor.copy(alpha = alpha2),
                                radius = r2,
                                center = center,
                                style = waveStroke
                            )
                        }
                    }
            )
        }

        // 2. Diffuse Ambient Neon Halo (cached radial brush, GPU alpha & scale breathing)
        if (isConnected || isConnecting) {
            Spacer(
                modifier = Modifier
                    .size(190.dp)
                    .graphicsLayer {
                        val glow = breathingGlowState.value
                        alpha = glow
                        val s = 0.95f + glow * 0.08f
                        scaleX = s
                        scaleY = s
                    }
                    .drawWithCache {
                        val centerOffset = Offset(size.width * 0.5f, size.height * 0.5f)
                        val haloBrush = Brush.radialGradient(
                            colors = listOf(
                                coreColor.copy(alpha = 0.30f),
                                coreColor.copy(alpha = 0.09f),
                                Color.Transparent
                            ),
                            center = centerOffset,
                            radius = size.minDimension * 0.5f
                        )
                        onDrawBehind {
                            drawCircle(brush = haloBrush)
                        }
                    }
            )
        }

        // 3. Fluid Orbital Light Stream (cached sweep gradient, GPU rotation & alpha)
        Spacer(
            modifier = Modifier
                .size(174.dp)
                .graphicsLayer {
                    rotationZ = if (isSpinning) orbitalRotationState.value else 0f
                    alpha = if (isSpinning) 0.58f + breathingGlowState.value * 0.42f else 1f
                }
                .drawWithCache {
                    val ringStroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                    val dormantStroke = Stroke(width = 1.2.dp.toPx())
                    val sweepBrush = if (isSpinning) {
                        Brush.sweepGradient(
                            colors = listOf(
                                Color.Transparent,
                                coreColor.copy(alpha = 0.05f),
                                coreColor.copy(alpha = 0.28f),
                                coreColor.copy(alpha = 0.78f),
                                coreColor.copy(alpha = 1.0f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, size.height * 0.5f)
                        )
                    } else null
                    val dormantColor = colors.borderSubtle.copy(alpha = 0.45f)
                    onDrawBehind {
                        if (sweepBrush != null) {
                            drawCircle(brush = sweepBrush, style = ringStroke)
                        } else {
                            drawCircle(color = dormantColor, style = dormantStroke)
                        }
                    }
                }
        )

        // 4. Central Volumetric Liquid Glass Orb
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(142.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.glassSurfaceElevated,
                            colors.background,
                            colors.backgroundDeep
                        ),
                        radius = 220f
                    )
                )
                .drawWithCache {
                    val centerOffset = Offset(size.width * 0.5f, size.height * 0.5f)
                    val innerGlowBrush = Brush.radialGradient(
                        colors = listOf(
                            coreColor.copy(alpha = 0.24f),
                            coreColor.copy(alpha = 0.07f),
                            Color.Transparent
                        ),
                        center = centerOffset,
                        radius = size.width * 0.45f
                    )
                    val glassHighlightBrush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (colors.isDark) 0.22f else 0.40f),
                            Color.White.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.50f, size.height * 0.20f),
                        radius = size.width * 0.45f
                    )
                    val highlightTopLeft = Offset(size.width * 0.15f, size.height * 0.04f)
                    val highlightSize = Size(size.width * 0.70f, size.height * 0.46f)
                    onDrawWithContent {
                        drawContent()
                        if (isConnected || isConnecting) {
                            drawCircle(
                                brush = innerGlowBrush,
                                alpha = breathingGlowState.value
                            )
                        }
                        drawOval(
                            brush = glassHighlightBrush,
                            topLeft = highlightTopLeft,
                            size = highlightSize
                        )
                    }
                }
                .border(
                    width = 1.5.dp,
                    brush = Brush.sweepGradient(
                        listOf(
                            if (isConnected || isConnecting) coreColor.copy(alpha = 0.90f) else colors.glassBorderLight,
                            if (isConnected || isConnecting) coreColor.copy(alpha = 0.25f) else colors.borderSubtle,
                            Color.White.copy(alpha = if (colors.isDark) 0.35f else 0.50f),
                            if (isConnected || isConnecting) coreColor.copy(alpha = 0.90f) else colors.glassBorderLight
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Dynamic Icon with smooth scaling spring transition
                AnimatedContent(
                    targetState = isConnected to isConnecting,
                    transitionSpec = {
                        (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(220)))
                            .togetherWith(scaleOut(spring(dampingRatio = Spring.DampingRatioNoBouncy)) + fadeOut(tween(160)))
                    },
                    label = "core_icon_transition"
                ) { (connected, connecting) ->
                    val icon = when {
                        connected -> Icons.Default.Shield
                        connecting -> Icons.Default.Sync
                        else -> Icons.Default.PowerSettingsNew
                    }
                    Box(contentAlignment = Alignment.Center) {
                        // Subtle icon shadow/glow (GPU alpha & scale, 0 recompositions)
                        if (connected) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = coreColor.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .size(46.dp)
                                    .graphicsLayer {
                                        val g = breathingGlowState.value
                                        alpha = g
                                        val s = 1.12f + g * 0.06f
                                        scaleX = s
                                        scaleY = s
                                    }
                            )
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (connected || connecting) coreColor else colors.accentCyan,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Status Text + Optional Pulse Dot
                AnimatedContent(
                    targetState = coreText,
                    modifier = Modifier.fillMaxWidth(),
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn(tween(180)))
                            .togetherWith(slideOutVertically { -it / 2 } + fadeOut(tween(140)))
                    },
                    label = "core_text_transition"
                ) { text ->
                    val label = text.uppercase(Locale.ROOT)
                    val measuredWidth = remember(label, labelMeasurer) {
                        labelMeasurer.measure(label, labelStyle, softWrap = false, maxLines = 1).size.width + 2f
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    val density = LocalDensity.current
                    val available = with(density) { (maxWidth - if (isConnected) 10.dp else 0.dp).toPx() }
                    val fit = (available / measuredWidth).coerceIn(.1f, 1f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isConnected) {
                            Box(Modifier.size(5.dp).clip(CircleShape).background(coreColor)
                                .graphicsLayer {
                                    val g = breathingGlowState.value
                                    scaleX = .85f + .3f * g
                                    scaleY = .85f + .3f * g
                                })
                            Spacer(Modifier.width(5.dp))
                        }
                        Text(
                            text = label,
                            modifier = Modifier.width(with(density) { (measuredWidth * fit).toDp() }),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (isConnected || isConnecting) coreColor else colors.textPrimary.copy(alpha = 0.9f),
                            style = labelStyle.copy(fontSize = (11f * fit).sp, letterSpacing = (1.6f * fit).sp),
                            softWrap = false,
                            maxLines = 1
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryHUD(
    isConnected: Boolean,
    latency: String,
    pingMs: Long,
    downSpeed: String,
    upSpeed: String,
    sessionRxBytes: Long = 0L,
    sessionTxBytes: Long = 0L,
    speedHistory: List<Float> = emptyList()
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val pingStatusColor = when {
        !isConnected || pingMs <= 0 -> colors.textTertiary
        pingMs in 1..120 -> colors.accentEmerald
        pingMs in 121..250 -> colors.accentAmber
        else -> colors.accentCrimson
    }

    val sessionRxFormatted = formatBytes(sessionRxBytes)
    val sessionTxFormatted = formatBytes(sessionTxBytes)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 20,
                elevated = true,
                innerSheen = true
            )
            .padding(vertical = 14.dp, horizontal = 14.dp)
    ) {
        // Row 1: speed metrics
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LATENCY
            TelemetryItem(
                label = strings.latencyLabel,
                value = if (isConnected) latency else "---",
                statusDotColor = pingStatusColor,
                pingMs = if (isConnected && pingMs > 0) pingMs else null,
                isConnected = isConnected
            )

            TelemetrySeparator()

            // DOWNLINK
            TelemetryItem(
                label = strings.downlinkLabel,
                value = if (isConnected) downSpeed else "0 KB/s",
                icon = Icons.Default.ArrowDownward,
                iconTint = colors.accentCyan
            )

            TelemetrySeparator()

            // UPLINK
            TelemetryItem(
                label = strings.uplinkLabel,
                value = if (isConnected) upSpeed else "0 KB/s",
                icon = Icons.Default.ArrowUpward,
                iconTint = colors.accentViolet
            )
        }

        // Real-time speed waveform graph (last 30 seconds)
        if (isConnected && speedHistory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            SpeedWaveformChart(
                history = speedHistory,
                isConnected = isConnected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
            )
        }

        // Row 2: session totals (only visible when connected and bytes accumulated)
        AnimatedVisibility(
            visible = isConnected && (sessionRxBytes > 0 || sessionTxBytes > 0),
            enter = fadeIn(tween(400)) + expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(tween(250)) + shrinkVertically()
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 2.dp)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    colors.borderSubtle.copy(alpha = 0.7f),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryItem(
                        label = strings.sessionDownloaded,
                        value = sessionRxFormatted,
                        icon = Icons.Default.Download,
                        iconTint = colors.accentCyan.copy(alpha = 0.7f)
                    )
                    TelemetrySeparator()
                    TelemetryItem(
                        label = strings.sessionUploaded,
                        value = sessionTxFormatted,
                        icon = Icons.Default.Upload,
                        iconTint = colors.accentSecondary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

@Composable
private fun TelemetrySeparator() {
    val colors = LocalAppThemeColors.current
    Box(
        modifier = Modifier
            .height(32.dp)
            .width(1.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        colors.borderSubtle.copy(alpha = 0.8f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
fun SignalBars(
    pingMs: Long,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppThemeColors.current
    val activeBars = when {
        !isConnected || pingMs <= 0 -> 0
        pingMs <= 120 -> 3
        pingMs <= 250 -> 2
        else -> 1
    }
    val barColor = when (activeBars) {
        3 -> colors.accentEmerald
        2 -> colors.accentAmber
        1 -> colors.accentCrimson
        else -> colors.textTertiary
    }
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.height(11.dp)
    ) {
        val heights = listOf(4.dp, 7.dp, 10.dp)
        heights.forEachIndexed { index, h ->
            Box(
                modifier = Modifier
                    .width(2.5.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (index < activeBars) barColor else colors.borderMedium.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
fun TelemetryItem(
    label: String,
    value: String,
    statusDotColor: Color? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    pingMs: Long? = null,
    isConnected: Boolean = false
) {
    val colors = LocalAppThemeColors.current
    val actualIconTint = iconTint ?: colors.textSecondary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pingMs != null) {
                SignalBars(pingMs = pingMs, isConnected = isConnected)
                Spacer(modifier = Modifier.width(5.dp))
            } else if (statusDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusDotColor)
                )
                Spacer(modifier = Modifier.width(5.dp))
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = actualIconTint,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Text(
                text = label,
                color = colors.textSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 13.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SpeedWaveformChart(
    history: List<Float>,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppThemeColors.current
    val primaryColor = colors.accentCyan
    val headPulseScale = if (isConnected && LocalUiPerformanceSettings.current.animationsEnabled) {
        val pulseTransition = rememberInfiniteTransition(label = "waveform_pulse")
        val scale by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.7f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "head_pulse"
        )
        scale
    } else {
        1f
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f || history.isEmpty()) return@Canvas

        // Subtle horizontal guide lines (33% and 66%)
        val gridAlpha = if (colors.isDark) 0.08f else 0.05f
        drawLine(
            color = colors.borderMedium.copy(alpha = gridAlpha),
            start = androidx.compose.ui.geometry.Offset(0f, height * 0.33f),
            end = androidx.compose.ui.geometry.Offset(width, height * 0.33f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        )
        drawLine(
            color = colors.borderMedium.copy(alpha = gridAlpha),
            start = androidx.compose.ui.geometry.Offset(0f, height * 0.66f),
            end = androidx.compose.ui.geometry.Offset(width, height * 0.66f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
        )

        val maxVal = (history.maxOrNull() ?: 1f).coerceAtLeast(15f)
        val stepX = width / (history.size - 1).coerceAtLeast(1)

        val path = androidx.compose.ui.graphics.Path()
        val fillPath = androidx.compose.ui.graphics.Path()

        val points = history.mapIndexed { index, value ->
            val x = index * stepX
            val normalized = (value / maxVal).coerceIn(0.04f, 1f)
            val y = height - (normalized * height * 0.82f)
            androidx.compose.ui.geometry.Offset(x, y)
        }

        if (points.isNotEmpty()) {
            path.moveTo(points.first().x, points.first().y)
            fillPath.moveTo(points.first().x, height)
            fillPath.lineTo(points.first().x, points.first().y)

            for (i in 0 until points.size - 1) {
                val p0 = points[i]
                val p1 = points[i + 1]
                val midX = (p0.x + p1.x) / 2
                path.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
                fillPath.cubicTo(midX, p0.y, midX, p1.y, p1.x, p1.y)
            }

            fillPath.lineTo(points.last().x, height)
            fillPath.close()

            // Draw glowing translucent gradient under waveform
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (isConnected) 0.28f else 0.06f),
                        colors.accentViolet.copy(alpha = if (isConnected) 0.10f else 0.02f),
                        Color.Transparent
                    )
                )
            )

            // Draw smooth glowing neon curve
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        colors.accentViolet.copy(alpha = 0.70f),
                        primaryColor
                    )
                ),
                style = Stroke(
                    width = 2.2.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )

            if (isConnected) {
                val lastPoint = points.last()
                drawCircle(
                    color = primaryColor.copy(alpha = 0.35f),
                    radius = 6.dp.toPx() * headPulseScale,
                    center = lastPoint
                )
                drawCircle(
                    color = primaryColor,
                    radius = 3.dp.toPx(),
                    center = lastPoint
                )
                drawCircle(
                    color = Color.White,
                    radius = 1.2.dp.toPx(),
                    center = lastPoint
                )
            }
        }
    }
}

@Composable
fun SpeedtestCard(
    state: com.danieldk.splatevpn.data.SpeedtestState,
    onStart: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) = com.danieldk.splatevpn.ui.components.SpeedtestPanel(state, onStart, onReset, modifier)
@Composable
fun LiveConnectionsOverviewCard(
    totalCount: Int,
    totalHits: Int,
    proxyCount: Int,
    directCount: Int,
    recentConnections: List<com.danieldk.splatevpn.vpn.LiveConnection>,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current

    val alphaPulse = if (isConnected && totalCount > 0 && LocalUiPerformanceSettings.current.animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "conn_dot")
        val pulse by infiniteTransition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "conn_pulse"
        )
        pulse
    } else {
        1.0f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 18,
                elevated = true,
                innerSheen = true,
                accentBorder = if (isConnected && totalCount > 0) colors.accentCyan.copy(alpha = 0.45f) else null
            )
            .tactileClickable(targetScale = 0.96f) { onClick() }
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            // Header: Title + Live Activity Badge + Chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.accentCyan.copy(alpha = 0.12f))
                            .border(1.dp, colors.accentCyan.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = colors.accentCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = strings.liveConnectionsTitle,
                                color = colors.textPrimary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            if (isConnected) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .graphicsLayer { alpha = alphaPulse }
                                        .clip(CircleShape)
                                        .background(colors.accentEmerald)
                                )
                            }
                        }
                        Text(
                            text = if (totalCount > 0) {
                                String.format(strings.liveConnectionsSummary, totalCount, totalHits)
                            } else if (isConnected) {
                                strings.localized("Ожидание сетевых запросов...", "Waiting for network requests...")
                            } else {
                                strings.noConnectionsYet
                            },
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (totalCount > 0) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.accentCyan.copy(alpha = 0.14f))
                                .border(1.dp, colors.accentCyan.copy(alpha = 0.40f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$totalCount",
                                color = colors.accentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Real-Time Domain / Route Mini Stream Preview
            if (recentConnections.isNotEmpty()) {
                HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.4f))

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    recentConnections.forEach { conn ->
                        val ruleColor = when (conn.ruleType) {
                            com.danieldk.splatevpn.vpn.OutboundRuleType.PROXY -> colors.accentCyan
                            com.danieldk.splatevpn.vpn.OutboundRuleType.DIRECT -> colors.accentEmerald
                            com.danieldk.splatevpn.vpn.OutboundRuleType.BLOCK -> colors.accentCrimson
                            com.danieldk.splatevpn.vpn.OutboundRuleType.DNS -> colors.accentViolet
                            com.danieldk.splatevpn.vpn.OutboundRuleType.OTHER -> colors.textSecondary
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f, fill = false)
                            ) {
                                Text(
                                    text = conn.network,
                                    color = if (conn.network == "UDP") colors.accentAmber else colors.accentEmerald,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = conn.destination,
                                    color = colors.textPrimary,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = ":${conn.port}",
                                    color = colors.textTertiary,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (conn.hitCount > 1) {
                                    Text(
                                        text = "×${conn.hitCount}",
                                        color = colors.accentAmber,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(ruleColor.copy(alpha = 0.12f))
                                    .border(0.5.dp, ruleColor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = conn.ruleType.label(),
                                    color = ruleColor,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
