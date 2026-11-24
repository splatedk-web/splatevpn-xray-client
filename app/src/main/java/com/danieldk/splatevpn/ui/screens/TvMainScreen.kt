package com.danieldk.splatevpn.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.BalancerPreferences
import com.danieldk.splatevpn.data.isProtectedWtpn
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.BalancerSettingsCard
import com.danieldk.splatevpn.ui.components.DeviceTransferSettingsCard
import com.danieldk.splatevpn.ui.components.LocalTvControls
import com.danieldk.splatevpn.ui.components.TorSettingsCard
import com.danieldk.splatevpn.ui.components.TunnelHealthBanner
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tvFocusOutline
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.vpn.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class TvPage { CONNECTION, SERVERS, APPS, TRANSFER, SETTINGS }

/** A remote-first Android TV layout with safe overscan, live telemetry, and DPAD focus management. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvMainScreen(viewModel: MainViewModel, onToggleConnect: () -> Unit) {
    val state by viewModel.vpnState.collectAsState()
    val statusMessage by viewModel.vpnStatusMessage.collectAsState()
    val selectedNode by viewModel.selectedServer.collectAsState()
    val tor by viewModel.torSettings.collectAsState()
    val browsingTor by viewModel.serverBrowserTor.collectAsState()
    val torState by viewModel.torState.collectAsState()
    val nodes by viewModel.allServers.collectAsState()
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val disabledIds by viewModel.disabledServerIds.collectAsState()
    val transferOfferPending by viewModel.tvTransferOfferPending.collectAsState()
    val autoSelect by viewModel.isAutoSelect.collectAsState()
    val balancerPreferences by viewModel.balancerPreferences.collectAsState()
    val healthIssue by viewModel.tunnelHealthIssue.collectAsState()
    val uploadSpeed by viewModel.uploadSpeed.collectAsState()
    val downloadSpeed by viewModel.downloadSpeed.collectAsState()
    val countryFlag by viewModel.countryFlag.collectAsState()
    val countryName by viewModel.countryName.collectAsState()
    val currentIp by viewModel.currentIp.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(TvPage.CONNECTION) }
    var settingsOpenRequest by rememberSaveable { mutableIntStateOf(0) }
    var settingsTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var manageServers by rememberSaveable { mutableStateOf(false) }
    var quickServers by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var modeBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var offerBusy by remember { mutableStateOf(false) }
    var offerError by remember { mutableStateOf<String?>(null) }
    val connectFocus = remember { FocusRequester() }
    val navConnectionFocus = remember { FocusRequester() }
    val inputModeManager = LocalInputModeManager.current
    val vpnActive = state != VpnState.DISCONNECTED && state != VpnState.FAILED
    val enabledNodes = remember(nodes, subscriptions, disabledIds) { nodes.filter(viewModel::isNodeEnabled) }

    LaunchedEffect(connectFocus, inputModeManager, transferOfferPending) {
        if (transferOfferPending) return@LaunchedEffect
        // TV launches can inherit touch mode from a mouse or launcher. Switch to keyboard mode before focusing.
        inputModeManager.requestInputMode(InputMode.Keyboard)
        withFrameNanos { }
        runCatching { connectFocus.requestFocus() }.onFailure { runCatching { navConnectionFocus.requestFocus() } }
    }

    LaunchedEffect(page) {
        error = null
    }

    BackHandler(enabled = page != TvPage.CONNECTION) {
        page = TvPage.CONNECTION
        scope.launch {
            withFrameNanos { }
            runCatching { navConnectionFocus.requestFocus() }
        }
    }
    BackHandler(enabled = page == TvPage.SERVERS && manageServers) {
        manageServers = false
    }

    fun openSettings(category: SettingsCategory? = null) {
        settingsTarget = category?.name
        settingsOpenRequest++
        page = TvPage.SETTINGS
    }

    fun selectMode(useTor: Boolean) {
        if (modeBusy) return
        scope.launch {
            modeBusy = true
            error = null
            try { error = viewModel.selectConnectionMode(useTor).exceptionOrNull()?.message }
            finally { modeBusy = false }
        }
    }

    fun finishTransferOffer(receive: Boolean) {
        if (offerBusy) return
        scope.launch {
            offerBusy = true
            offerError = null
            try {
                viewModel.completeTvTransferOffer()
                if (receive) {
                    page = TvPage.TRANSFER
                    error = viewModel.deviceTransferManager.startReceiving().exceptionOrNull()?.let {
                        strings.localized("Не удалось подготовить QR-код. Подключите телевизор к Wi-Fi или Ethernet и повторите в разделе переноса.",
                            "Could not prepare the QR code. Connect the TV to Wi-Fi or Ethernet and retry in Transfer settings.")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                offerError = strings.localized("Не удалось сохранить выбор. Попробуйте ещё раз.", "Could not save your choice. Try again.")
            } finally { offerBusy = false }
        }
    }

    val statusColor = when (state) {
        VpnState.CONNECTED -> colors.accentEmerald
        VpnState.FAILED -> colors.accentCrimson
        VpnState.DISCONNECTED -> colors.textSecondary
        else -> colors.accentAmber
    }
    val selectedServerMeta = remember(selectedNode, subscriptions) {
        selectedNode?.let { node ->
            val hidden = isProtectedWtpn(node.subId, subscriptions.firstOrNull { it.id == node.subId }?.url)
            val proto = node.protocol.uppercase()
            val ping = node.ping.takeIf { it > 0L }?.let { " · $it ms" }.orEmpty()
            if (hidden) "$proto$ping" else "$proto · ${node.address}:${node.port}$ping"
        }
    }

    Row(
        Modifier.fillMaxSize().background(colors.background).padding(horizontal = 36.dp, vertical = 26.dp)
            .testTag("tvMainScreen"),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Left navigation rail (TV Leanback pattern)
        Column(
            Modifier.width(232.dp).fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface.copy(alpha = 0.72f))
                .padding(16.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SplateVPN", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                    Text(
                        text = when (state) {
                            VpnState.CONNECTED -> strings.coreConnected
                            VpnState.FAILED -> strings.stateFailed
                            VpnState.DISCONNECTED -> "Android TV"
                            else -> strings.stateConnecting
                        },
                        color = if (state == VpnState.CONNECTED) colors.accentEmerald else colors.textSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            HorizontalDivider(color = colors.borderSubtle)
            val destinations = listOf(
                Triple(TvPage.CONNECTION, strings.localized("Подключение", "Connection"), Icons.Default.Shield),
                Triple(TvPage.SERVERS, strings.localized("Серверы", "Servers"), Icons.Default.Dns),
                Triple(TvPage.APPS, strings.tabRouting, Icons.Default.Apps),
                Triple(TvPage.TRANSFER, strings.localized("Перенос настроек", "Transfer settings"), Icons.Default.QrCode2),
                Triple(TvPage.SETTINGS, strings.localized("Настройки", "Settings"), Icons.Default.Tune)
            )
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                destinations.forEach { (destination, label, icon) ->
                    TvActionButton(
                        label = label,
                        onClick = {
                            if (destination == TvPage.SETTINGS) openSettings() else page = destination
                            if (destination == TvPage.SERVERS) manageServers = false
                        },
                        modifier = if (destination == TvPage.CONNECTION) Modifier.focusRequester(navConnectionFocus) else Modifier,
                        selected = page == destination,
                        tag = "tvPage:${destination.name}",
                        icon = icon
                    )
                }
            }
            Text(
                strings.localized("Стрелки — выбор, OK — действие, Назад — возврат.", "Arrows to move, OK to select, Back to return."),
                color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp
            )
        }

        // Right detail workspace
        Column(Modifier.weight(1f).fillMaxHeight().focusGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            error?.let { errText ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(colors.accentCrimson.copy(alpha = 0.14f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(errText, color = colors.accentCrimson, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { error = null }, modifier = Modifier.tvFocusOutline().testTag("tvDismissError")) {
                        Text(strings.localized("Скрыть", "Dismiss"), color = colors.textPrimary)
                    }
                }
            }
            when (page) {
                TvPage.CONNECTION -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item(key = "connection") {
                        TvConnectionControls(
                            state = state,
                            torMode = tor.enabled,
                            serverName = selectedNode?.name,
                            torProgress = torState.progress,
                            modeBusy = modeBusy,
                            onToggleConnect = onToggleConnect,
                            onSelectMode = ::selectMode,
                            statusMessage = statusMessage,
                            serverMeta = selectedServerMeta,
                            connectFocusRequester = connectFocus
                        )
                    }
                    if (state == VpnState.CONNECTED) item(key = "telemetry") {
                        TvTelemetryStrip(
                            downloadSpeed = downloadSpeed,
                            uploadSpeed = uploadSpeed,
                            countryFlag = countryFlag,
                            countryName = countryName,
                            currentIp = currentIp,
                            onRefreshIp = viewModel::refreshIpLocation
                        )
                    }
                    healthIssue?.let { issue ->
                        item(key = "health") {
                            TunnelHealthBanner(issue, { viewModel.handleHealthIssuePrimaryAction(issue) },
                                { viewModel.handleHealthIssueSecondaryAction(issue) }, viewModel::dismissDnsHealthIssue, colors)
                        }
                    }
                    if (!tor.enabled) item(key = "balancer") {
                        TvBalancerControls(autoSelect, balancerPreferences, viewModel::setAutoSelect, viewModel::setBalancerPreferences)
                    }
                    item(key = "quickRow1") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvActionButton(
                                label = strings.localized("Выбрать сервер", "Choose a server"),
                                onClick = { quickServers = true },
                                modifier = Modifier.weight(1f),
                                tag = "tvChooseServer",
                                icon = Icons.Default.Dns
                            )
                            TvActionButton(
                                label = strings.localized("Сеть и DNS", "Network and DNS"),
                                onClick = { openSettings(SettingsCategory.NETWORK) },
                                modifier = Modifier.weight(1f),
                                tag = "tvNetworkSettings",
                                icon = Icons.Default.Public
                            )
                        }
                    }
                    item(key = "quickRow2") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvActionButton(
                                label = strings.localized("Получить настройки с телефона", "Receive settings from phone"),
                                onClick = { page = TvPage.TRANSFER },
                                modifier = Modifier.weight(1f),
                                tag = "tvReceiveSettings",
                                icon = Icons.Default.QrCode2
                            )
                            TvActionButton(
                                label = strings.localized("Журнал и диагностика", "Logs & diagnostics"),
                                onClick = { showLogs = true },
                                modifier = Modifier.weight(1f),
                                tag = "tvLogs",
                                icon = Icons.Default.Terminal
                            )
                        }
                    }
                }
                TvPage.SERVERS -> if (manageServers) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvActionButton(
                            label = strings.localized("Назад к списку серверов", "Back to server list"),
                            onClick = { manageServers = false },
                            tag = "tvBackToServers",
                            icon = Icons.AutoMirrored.Filled.ArrowBack
                        )
                        Box(Modifier.weight(1f)) { ServersScreen(viewModel) }
                    }
                } else LazyColumn(Modifier.fillMaxSize().testTag("tvServersList"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "serversHeader") {
                        Text(strings.localized("Выбор подключения", "Choose connection"), color = colors.textPrimary,
                            fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvActionButton("VPN", { viewModel.browseServerMode(false) }, Modifier.weight(1f), true, !browsingTor, "tvServersMode:VPN", icon = Icons.Default.Shield)
                            TvActionButton("Tor", { viewModel.browseServerMode(true) }, Modifier.weight(1f), true, browsingTor, "tvServersMode:TOR", icon = Icons.Default.Public)
                        }
                    }
                    item(key = "serversTools") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvActionButton(
                                label = strings.tabNodes + " · " + strings.localized("Подписки", "Subscriptions"),
                                onClick = { manageServers = true },
                                modifier = Modifier.weight(1f),
                                tag = "tvManageServers",
                                icon = Icons.Default.Settings
                            )
                            if (!browsingTor && enabledNodes.isNotEmpty()) {
                                TvActionButton(
                                    label = if (isPinging) strings.localized("Проверка пинга…", "Testing ping…")
                                    else strings.localized("Проверить пинг", "Test ping"),
                                    onClick = { viewModel.testAllPings() },
                                    modifier = Modifier.weight(1f),
                                    enabled = !isPinging,
                                    tag = "tvTestPing",
                                    icon = Icons.Default.Speed
                                )
                            }
                        }
                    }
                    if (browsingTor) item(key = "torCard") {
                        TorSettingsCard(viewModel)
                    }
                    if (!browsingTor && enabledNodes.isEmpty()) item(key = "emptyServers") {
                        Column(
                            Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16).padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                strings.localized("Серверов пока нет. Получите серверы и настройки с телефона в разделе переноса или добавьте подписку.",
                                    "No servers yet. Receive servers and settings from your phone in Transfer settings or add a subscription."),
                                color = colors.textSecondary, fontSize = 18.sp
                            )
                            TvActionButton(
                                label = strings.localized("Открыть перенос настроек", "Open Transfer settings"),
                                onClick = { page = TvPage.TRANSFER },
                                tag = "tvEmptyTransfer",
                                icon = Icons.Default.QrCode2
                            )
                        }
                    }
                    if (!browsingTor) items(enabledNodes, key = { it.id }) { node ->
                        val pingLabel = when {
                            node.ping > 0L -> "${node.ping} ms"
                            node.ping < 0L -> strings.localized("Недоступен", "Failed")
                            else -> null
                        }
                        val pingColor = when {
                            node.ping in 1L..180L -> colors.accentEmerald
                            node.ping in 181L..450L -> colors.accentAmber
                            node.ping > 450L || node.ping < 0L -> colors.accentCrimson
                            else -> colors.textSecondary
                        }
                        val subUrl = subscriptions.firstOrNull { it.id == node.subId }?.url
                        TvActionButton(
                            label = node.name,
                            onClick = {
                                if (!modeBusy) scope.launch {
                                    modeBusy = true
                                    error = null
                                    try {
                                        error = viewModel.selectVpnServer(node.id).exceptionOrNull()?.message
                                    } finally { modeBusy = false }
                                }
                            },
                            enabled = !modeBusy,
                            selected = !tor.enabled && selectedNode?.id == node.id,
                            tag = "tvServer:${node.id}",
                            subtitle = if (isProtectedWtpn(node.subId, subUrl)) node.protocol.uppercase()
                            else "${node.protocol.uppercase()} · ${node.address}:${node.port}",
                            trailingText = pingLabel,
                            trailingColor = pingColor
                        )
                    }
                }
                TvPage.TRANSFER -> LazyColumn(Modifier.fillMaxSize().testTag("tvTransferList"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(strings.localized("Перенос настроек", "Transfer settings"), color = colors.textPrimary,
                            fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(strings.localized("Подключите телефон и телевизор к одной Wi-Fi или Ethernet-сети. Камера телевизору не нужна — просто покажите QR на экране и отсканируйте телефоном.",
                            "Connect your phone and TV to the same Wi-Fi or Ethernet network. No TV camera is needed — show the QR on TV and scan it with your phone."), color = colors.textSecondary, fontSize = 17.sp)
                    }
                    item { DeviceTransferSettingsCard(viewModel.deviceTransferManager, viewModel.webDavSyncManager, vpnActive) }
                }
                TvPage.APPS -> PerAppScreen(viewModel)
                TvPage.SETTINGS -> SettingsScreen(viewModel, settingsOpenRequest, settingsTarget)
            }
        }
    }
    if (quickServers) QuickServerPickerDialog(viewModel, { quickServers = false }, {
        quickServers = false; manageServers = true; page = TvPage.SERVERS
    })
    if (showLogs) LogsDialog(viewModel = viewModel, onDismiss = { showLogs = false })
    if (transferOfferPending) TvTransferOfferDialog(offerBusy, offerError,
        onReceive = { finishTransferOffer(true) }, onLater = { finishTransferOffer(false) })
}

@Composable
private fun TvTelemetryStrip(
    downloadSpeed: String,
    uploadSpeed: String,
    countryFlag: String,
    countryName: String,
    currentIp: String,
    onRefreshIp: () -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    Row(
        Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(strings.localized("Загрузка", "Download"), color = colors.textSecondary, fontSize = 13.sp)
            Text(downloadSpeed.ifBlank { "0 B/s" }, color = colors.accentCyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(strings.localized("Отдача", "Upload"), color = colors.textSecondary, fontSize = 13.sp)
            Text(uploadSpeed.ifBlank { "0 B/s" }, color = colors.accentEmerald, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            Modifier.weight(1.4f).clip(RoundedCornerShape(12.dp)).tvFocusOutline()
                .clickable(role = Role.Button, onClick = onRefreshIp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = listOf(countryFlag, countryName).filter { it.isNotBlank() }.joinToString(" ")
                        .ifBlank { strings.localized("Внешний IP", "External IP") },
                    color = colors.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    currentIp.ifBlank { "…" },
                    color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.Default.Refresh, strings.refresh, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun TvBalancerControls(
    enabled: Boolean,
    preferences: BalancerPreferences,
    onEnabledChange: (Boolean) -> Unit,
    onPreferencesChange: (BalancerPreferences) -> Unit
) {
    val strings = LocalAppStrings.current
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingToggleRow(Icons.Default.Bolt, strings.autoSelectTitle, strings.autoSelectSubtitle,
            enabled, onEnabledChange, Modifier.testTag("tvAutoBalancer"))
        BalancerSettingsCard(preferences, enabled, onPreferencesChange)
    }
}

@Composable
internal fun TvConnectionControls(
    state: VpnState,
    torMode: Boolean,
    serverName: String?,
    torProgress: Int,
    modeBusy: Boolean,
    onToggleConnect: () -> Unit,
    onSelectMode: (Boolean) -> Unit,
    statusMessage: String? = null,
    serverMeta: String? = null,
    connectFocusRequester: FocusRequester? = null
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val connecting = state !in setOf(VpnState.DISCONNECTED, VpnState.FAILED, VpnState.CONNECTED)
    val active = state != VpnState.DISCONNECTED && state != VpnState.FAILED
    val stateAccent = when {
        state == VpnState.CONNECTED -> colors.accentEmerald
        state == VpnState.FAILED -> colors.accentCrimson
        connecting -> colors.accentAmber
        else -> colors.accentCyan
    }
    Column(
        Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, accentBorder = stateAccent.copy(alpha = 0.45f)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(stateAccent))
            Text(when {
                state == VpnState.CONNECTED -> strings.coreConnected
                state == VpnState.FAILED -> strings.stateFailed
                connecting && torMode -> strings.localized("Подключение Tor", "Connecting Tor") + " ${torProgress.coerceIn(0, 100)}%"
                connecting -> strings.stateConnecting
                else -> strings.localized("Готов к подключению", "Ready to connect")
            }, color = if (state == VpnState.CONNECTED) colors.accentEmerald else colors.textPrimary,
                fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (torMode) "Tor" else serverName ?: strings.noServerSelected, color = colors.textPrimary,
                fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!torMode && !serverMeta.isNullOrBlank()) {
                Text(serverMeta, color = colors.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!statusMessage.isNullOrBlank() && (state == VpnState.FAILED || connecting)) {
                Text(statusMessage, color = if (state == VpnState.FAILED) colors.accentCrimson else colors.textSecondary,
                    fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton("VPN", { onSelectMode(false) }, Modifier.weight(1f), !modeBusy, !torMode, "tvMode:VPN", icon = Icons.Default.Shield)
            TvActionButton("Tor", { onSelectMode(true) }, Modifier.weight(1f), !modeBusy, torMode, "tvMode:TOR", icon = Icons.Default.Public)
        }
        TvActionButton(
            label = if (active) {
                if (connecting) strings.localized("Отменить подключение", "Cancel connection") else strings.localized("Отключить", "Disconnect")
            } else strings.coreConnect,
            onClick = onToggleConnect,
            modifier = if (connectFocusRequester != null) Modifier.focusRequester(connectFocusRequester) else Modifier,
            enabled = !modeBusy,
            selected = state == VpnState.CONNECTED,
            tag = "tvConnect",
            prominent = true,
            icon = Icons.Default.PowerSettingsNew
        )
        if (torMode) Text(strings.localized("Первое подключение Tor может занять больше минуты. Дождитесь завершения.",
            "The first Tor connection can take more than a minute. Wait for it to complete."), color = colors.textSecondary, fontSize = 15.sp)
    }
}

@Composable
internal fun TvActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tag: String,
    subtitle: String? = null,
    prominent: Boolean = false,
    icon: ImageVector? = null,
    trailingText: String? = null,
    trailingColor: Color? = null
) {
    val colors = LocalAppThemeColors.current
    val tvControls = LocalTvControls.current
    var focused by remember { mutableStateOf(false) }
    val showTvFocus = focused && tvControls
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = if (prominent) 74.dp else 60.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { this.selected = selected }
            .testTag(tag),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            if (showTvFocus) 3.dp else 1.dp,
            when {
                showTvFocus -> colors.textPrimary
                selected -> colors.accentCyan.copy(alpha = 0.7f)
                else -> colors.borderMedium
            }
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = when {
                showTvFocus -> colors.accentCyan.copy(alpha = 0.22f)
                prominent && selected -> colors.accentEmerald.copy(alpha = 0.18f)
                prominent -> colors.accentCyan.copy(alpha = 0.16f)
                selected -> colors.accentCyan.copy(alpha = 0.14f)
                else -> colors.surface
            },
            contentColor = colors.textPrimary
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(if (prominent) 26.dp else 20.dp),
                    tint = if (showTvFocus || selected || prominent) colors.accentCyan else colors.textSecondary
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    label,
                    fontSize = if (prominent) 22.sp else 17.sp,
                    fontWeight = if (selected || prominent) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                subtitle?.let {
                    Text(it, color = colors.textSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailingText?.let {
                Text(
                    it,
                    color = trailingColor ?: colors.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}
