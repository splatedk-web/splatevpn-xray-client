package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch
import com.danieldk.splatevpn.ui.components.SplateRadioButton as RadioButton

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.*
import com.danieldk.splatevpn.vpn.TorPhase
import com.danieldk.splatevpn.vpn.TorBootstrapStage
import com.danieldk.splatevpn.vpn.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

@Composable
fun ServerConnectionModeSelector(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val browsingTor by viewModel.serverBrowserTor.collectAsState()
    val saved by viewModel.torSettings.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TorModeSelector(browsingTor, false, viewModel::browseServerMode)
        Text(LocalAppStrings.current.localized("Режим подключения: %s", "Connection mode: %s").format(if (saved.enabled) "Tor" else "VPN"),
            color = LocalAppThemeColors.current.textSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

/** The same semantic tab actions work with touch, keyboard and a TV remote. */
@Composable
internal fun TorModeSelector(useTor: Boolean, busy: Boolean, onSelect: (Boolean) -> Unit) {
    val colors = LocalAppThemeColors.current
    SlidingTabStrip(if (useTor) 1 else 0, listOf(colors.accentCyan, colors.accentEmerald),
        onSelect = { if (!busy) onSelect(it == 1) }, modifier = Modifier.selectableGroup()) { selected ->
        listOf(false, true).forEachIndexed { index, tor ->
            Row(Modifier.weight(1f).fillMaxHeight().testTag(if (tor) "connectionMode:TOR" else "connectionMode:VPN")
                .tvFocusOutline().selectable(selected = (if (useTor) 1 else 0) == index, enabled = !busy, role = Role.Tab) { onSelect(tor) }
                .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (tor) Icons.Default.Public else Icons.Default.Shield, null, Modifier.size(18.dp),
                    tint = if (selected == index) colors.textPrimary else colors.textSecondary)
                Spacer(Modifier.width(8.dp))
                Text(if (tor) "Tor" else "VPN", color = if (selected == index) colors.textPrimary else colors.textSecondary,
                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeConnectionModeSelector(viewModel: MainViewModel) {
    val tor by viewModel.torSettings.collectAsState()
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var bridgesOpen by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                enabled = !busy,
                modifier = Modifier.testTag("homeSimpleConnectionMode"),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Icon(if (tor.enabled) Icons.Default.Public else Icons.Default.Shield, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (tor.enabled) "Tor" else "VPN")
                Icon(Icons.Default.ArrowDropDown, strings.localized("Выбрать режим", "Choose connection mode"),
                    Modifier.size(18.dp))
            }
            SplateDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                listOf(false, true).forEach { useTor ->
                    DropdownMenuItem(
                        text = { Text(if (useTor) "Tor" else "VPN") },
                        trailingIcon = { if (useTor == tor.enabled) Icon(Icons.Default.Check, null) },
                        onClick = {
                            menuOpen = false
                            if (!busy && useTor != tor.enabled) {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        error = viewModel.selectConnectionMode(useTor).exceptionOrNull()?.message
                                    } finally { busy = false }
                                }
                            }
                        }
                    )
                }
            }
        }
        if (tor.enabled) TextButton(
            onClick = { bridgesOpen = true },
            modifier = Modifier.testTag("homeSimpleTorBridges")
        ) {
            Text(strings.localized("Мосты", "Bridges"))
        }
    }
    error?.let { Text(it, color = colors.accentCrimson, fontSize = 12.sp) }

    if (tor.enabled && bridgesOpen) ToolDialog(onDismissRequest = { bridgesOpen = false }, maxWidth = 520.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.localized("Мосты Tor", "Tor bridges"), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { bridgesOpen = false }) { Icon(Icons.Default.Close, strings.cancel) }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            TorSettingsCard(viewModel, simple = true)
        }
    }
}

@Composable
fun TorSettingsCard(viewModel: MainViewModel, simple: Boolean = false) {
    val saved by viewModel.torSettings.collectAsState()
    val runtime by viewModel.torState.collectAsState()
    val storageError by viewModel.torStorageError.collectAsState()
    val bridgeLatencies by viewModel.torBridgeLatencies.collectAsState()
    val isProbingBridges by viewModel.isProbingTorBridges.collectAsState()
    val bridgeSpeeds by viewModel.torBridgeSpeeds.collectAsState()
    val benchmark by viewModel.torBridgeBenchmarkState.collectAsState()
    val resources = LocalAppResources.current
    val metric = TorBridgeMetric.fromId(saved.bridgeSelectionMetric)
    val probing = isProbingBridges || benchmark.running
    val vpnState by viewModel.vpnState.collectAsState()
    val disconnected = vpnState in setOf(VpnState.DISCONNECTED, VpnState.FAILED)
    val editable = disconnected || !saved.enabled
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var bridgeDetails by rememberSaveable { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val manual = remember(saved.bridges, saved.bridgeSubscriptions) { saved.manualBridges() }
    var bridgesText by remember(manual) { mutableStateOf(manual.joinToString("\n")) }
    var entry by rememberSaveable(saved.entryNodes) { mutableStateOf(saved.entryNodes) }
    var exit by rememberSaveable(saved.exitNodes) { mutableStateOf(saved.exitNodes) }
    var excluded by rememberSaveable(saved.excludeNodes) { mutableStateOf(saved.excludeNodes) }
    var strict by rememberSaveable(saved.strictNodes) { mutableStateOf(saved.strictNodes) }
    var importedUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var optimisticData by rememberSaveable(saved.optimisticData) { mutableStateOf(saved.optimisticData) }
    var autoSelectFastest by rememberSaveable(saved.autoSelectFastestBridge) { mutableStateOf(saved.autoSelectFastestBridge) }
    var customSni by rememberSaveable(saved.customBridgeSni) { mutableStateOf(saved.customBridgeSni) }
    val bridgeCounts = remember(saved.bridges) { saved.bridges.groupingBy(::torBridgeTransport).eachCount() }
    val selectedBridge = runCatching { saved.forConnection().bridges.singleOrNull() }.getOrNull()

    fun draft() = saved.copy(entryNodes = entry, exitNodes = exit, excludeNodes = excluded, strictNodes = strict,
        optimisticData = optimisticData, autoSelectFastestBridge = autoSelectFastest, customBridgeSni = customSni)
        .withBridgeSources(manual = bridgesText.lineSequence().map(String::trim).filter(String::isNotBlank).toList())
    fun operation(success: String, block: suspend () -> Result<Unit>) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = block()
                failed = result.isFailure
                message = result.fold({ success }, { it.message ?: strings.localized("Не удалось выполнить действие", "Action failed") })
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { failed = true; message = error.message
            } finally { busy = false }
        }
    }
    fun importText(text: String) {
        if (text.trim().startsWith("https://", true)) {
            runCatching { TorConfigParser.normalizeSubscriptionUrl(text.trim()) }.onSuccess {
                importedUrl = it
            }.onFailure { failed = true; message = it.message }
        } else operation(strings.localized("Мосты добавлены", "Bridges added")) {
            try {
                val imported = withContext(Dispatchers.Default) { TorConfigParser.parsePasted(text) }
                viewModel.applyTorConnectionSettings(saved.withBridgeSources(manual = saved.manualBridges() + imported.bridges).copy(
                    useBridges = imported.useBridges ?: (imported.bridges.isNotEmpty() || saved.useBridges),
                    selectedBridge = "", bridgeTransport = "auto", lastImportSkipped = imported.skippedBridges,
                    entryNodes = imported.entryNodes ?: saved.entryNodes, exitNodes = imported.exitNodes ?: saved.exitNodes,
                    excludeNodes = imported.excludeNodes ?: saved.excludeNodes, strictNodes = imported.strictNodes ?: saved.strictNodes))
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) { Result.failure(error) }
        }
    }
    val scan = rememberQrImporter(::importText)
    val stateLabel = when (runtime.phase) {
        TorPhase.STOPPED -> strings.localized("Готов к подключению", "Ready to connect")
        TorPhase.STARTING -> strings.localized("Подключение", "Connecting") + " · ${runtime.progress}%"
        TorPhase.RUNNING -> strings.localized("Подключён", "Connected")
        TorPhase.FAILED -> if (runtime.autoSelecting) strings.localized(
            "Не удалось подключиться через выбранные мосты. Обновите список или выберите мост вручную.",
            "The saved bridges could not connect. Refresh the list or choose a bridge manually.")
            else strings.localized("Не удалось подключиться. Попробуйте другой мост.", "Could not connect. Try another bridge.")
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (simple) {
            if (saved.bridges.isEmpty()) {
                Text(strings.localized("Загрузите мосты из подписки на главной, чтобы использовать их с Tor.",
                    "Download bridges from a subscription on Home to use them with Tor."), color = colors.textSecondary)
            } else {
                TorToggle(strings.localized("Использовать мосты", "Use bridges"), saved.useBridges, !busy) { enabled ->
                    operation(strings.localized("Мосты настроены", "Bridge setting saved")) {
                        if (enabled) viewModel.selectTorBridge("", "auto")
                        else viewModel.applyTorConnectionSettings(saved.copy(useBridges = false))
                    }
                }
            }
            OutlinedButton(onClick = { bridgeDetails = !bridgeDetails }, modifier = Modifier.fillMaxWidth()) {
                Text(if (bridgeDetails) strings.localized("Скрыть подробности", "Hide details")
                    else strings.localized("Ручной выбор моста", "Choose a bridge manually"))
            }
        }
        if (!simple) TorGlassSection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).background(colors.accentEmerald.copy(alpha = .12f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center) { Icon(Icons.Default.Public, null, tint = colors.accentEmerald) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tor", color = colors.textPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(stateLabel, color = if (runtime.phase == TorPhase.FAILED) colors.accentCrimson else colors.textSecondary, fontSize = 12.sp)
                }
            }
            if (runtime.phase == TorPhase.STARTING) LinearProgressIndicator(progress = { runtime.progress / 100f }, modifier = Modifier.fillMaxWidth(),
                color = colors.accentEmerald, trackColor = colors.borderSubtle)
            if (runtime.autoSelecting && runtime.attemptNumber > 0) {
                Text(String.format(strings.localized("Автоподбор моста: %d/%d", "Automatic bridge: %d/%d"), runtime.attemptNumber, runtime.attemptCount) +
                    " · " + runtime.bridgeTransport.replaceFirstChar { it.uppercase() },
                    modifier = Modifier.testTag("torBridgeAttempt"), color = colors.accentCyan, fontSize = 13.sp)
            }
            if (runtime.phase == TorPhase.STARTING || runtime.phase == TorPhase.FAILED && runtime.bootstrap.stage != TorBootstrapStage.READY) {
                Text(strings.localized(runtime.bootstrap.stage.ru, runtime.bootstrap.stage.en),
                    modifier = Modifier.testTag("torBootstrapStage"), color = colors.textPrimary, fontSize = 13.sp)
                Text(String.format(strings.localized("Прошло: %d с · без прогресса: %d с", "Elapsed: %d s · no progress: %d s"), runtime.elapsedSeconds, runtime.stalledSeconds),
                    modifier = Modifier.testTag("torBootstrapElapsed"), color = colors.textSecondary, fontSize = 12.sp)
                runtime.bootstrap.reason?.let { reason ->
                    Text(strings.localized(reason.ru, reason.en), color = colors.accentAmber, fontSize = 12.sp)
                }
                if (runtime.bootstrap.warning && runtime.bootstrap.reason == null) {
                    Text(strings.localized("Tor сообщает о проблеме на этой стадии подключения.",
                        "Tor reports a problem at this connection stage."), color = colors.accentAmber, fontSize = 12.sp)
                }
            }
            if (runtime.isStalled) {
                Text(if (runtime.autoSelecting && runtime.attemptNumber < runtime.attemptCount) {
                    strings.localized("Этот мост пока не отвечает. Если прогресс не появится, приложение само попробует следующий.",
                        "This bridge is not responding yet. The app will try the next one if progress does not resume.")
                } else if (runtime.bootstrap.stage == TorBootstrapStage.TLS_HANDSHAKE || runtime.bootstrap.stage == TorBootstrapStage.CIRCUIT_TLS_HANDSHAKE) {
                    strings.localized("TLS-рукопожатие ещё не завершено. Этот мост может быть недоступен в вашей сети. Можно подождать или попробовать другой мост либо WebTunnel.",
                        "The TLS handshake has not completed. This bridge may be unavailable on your network. Wait, or try another bridge or WebTunnel.")
                } else strings.localized("На этой стадии давно нет прогресса. Можно подождать или попробовать другой мост.",
                    "This stage has not advanced for a while. Wait or try another bridge."),
                    modifier = Modifier.testTag("torBootstrapStalled"), color = colors.accentAmber, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { choosing = true }, enabled = !busy && saved.bridges.isNotEmpty(),
                        modifier = Modifier.weight(1f).testTag("torStalledChooseBridge")) {
                        Text(strings.localized("Другой мост", "Another bridge"))
                    }
                    val webtunnel = saved.bridges.firstOrNull { torBridgeTransport(it) == "webtunnel" && it != selectedBridge }
                    if (webtunnel != null) OutlinedButton(onClick = {
                        operation(strings.localized("WebTunnel выбран", "WebTunnel selected")) {
                            viewModel.selectTorBridge(webtunnel, "webtunnel")
                        }
                    }, enabled = !busy, modifier = Modifier.weight(1f).testTag("torStalledWebTunnel")) {
                        Text("WebTunnel")
                    }
                }
            }
            Text(if (saved.useBridges && saved.selectedBridge.isBlank()) strings.localized(
                "Нажмите «Подключить Tor» — приложение само подберёт мост из списка. Первый запуск может занять несколько минут.",
                "Tap Connect Tor and the app will select a bridge from the list. The first connection may take a few minutes.")
                else strings.localized("Подключайтесь прямо здесь. Первый запуск может занять больше минуты.",
                    "Connect here. The first connection may take more than a minute."), color = colors.textSecondary, fontSize = 12.sp)
            if (saved.useBridges && selectedBridge == null) {
                Text(strings.localized(
                    "Список мостов пуст. Загрузите мосты из подписки или отключите использование мостов.",
                    "The bridge list is empty. Download bridges from a subscription or turn bridges off."
                ), color = colors.accentAmber, fontSize = 12.sp)
            }
            Button(onClick = {
                if (!disconnected && saved.enabled) viewModel.toggleConnectionFromServers()
                else operation(strings.localized("Tor выбран", "Tor selected")) {
                    viewModel.selectConnectionMode(true).onSuccess {
                        if (disconnected) viewModel.toggleConnectionFromServers()
                    }
                }
            }, enabled = !busy && (saved.enabled && !disconnected || !saved.useBridges || selectedBridge != null), modifier = Modifier.fillMaxWidth().testTag("torConnect")) {
                Icon(if (disconnected || !saved.enabled) Icons.Default.PowerSettingsNew else Icons.Default.Stop, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (disconnected || !saved.enabled) strings.localized("Подключить Tor", "Connect Tor")
                    else if (runtime.phase == TorPhase.STARTING) strings.localized("Отменить подключение", "Cancel connection")
                    else strings.localized("Отключить", "Disconnect"))
            }
        }
        if (storageError != null) Text(strings.localized("Сохранённые настройки Tor повреждены. Импортируйте мосты или сохраните настройки заново.",
            "Saved Tor settings could not be read. Import bridges or save the settings again."), color = colors.accentCrimson, fontSize = 12.sp)
        if (!simple) TorSubscriptionCards(viewModel, showAutoUpdate = true)
        if (!simple || bridgeDetails) TorGlassSection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AccountTree, null, tint = colors.accentCyan, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                Text(strings.localized("Мост подключения", "Connection bridge"), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            }
            Text(if (saved.useBridges && runtime.autoSelecting && runtime.phase in setOf(TorPhase.STARTING, TorPhase.RUNNING)) {
                strings.localized("Автоподбор", "Automatic selection") + " · " + runtime.bridgeTransport.replaceFirstChar { it.uppercase() }
            } else if (saved.useBridges) selectedBridge?.let(::bridgeLabel) ?: strings.localized("Мостов этого типа нет. Выберите другой тип.", "No bridges of this type. Choose another type.")
                else strings.localized("Без моста · прямое подключение Tor", "No bridge · direct Tor connection"),
                color = colors.accentCyan, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (saved.selectedBridge.isBlank()) strings.localized(
                "Приложение само пробует сохранённые мосты по очереди. В «Авто» чередуются Vanilla, WebTunnel и obfs4.",
                "Saved bridges are tried automatically, one at a time. Auto alternates Vanilla, WebTunnel and obfs4.")
                else strings.localized("Мост выбран вручную. Для автоматического перебора нажмите «Авто».",
                    "This bridge is pinned manually. Select Auto to try other saved bridges automatically."),
                color = colors.textSecondary, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to strings.localized("Авто", "Auto"), "vanilla" to "Vanilla", "webtunnel" to "WebTunnel", "obfs4" to "obfs4", "snowflake" to "Snowflake").forEach { (type, label) ->
                    val count = saved.bridges.count { type == "auto" || torBridgeTransport(it) == type }
                    FilterChip(selected = saved.useBridges && saved.bridgeTransport == type &&
                        (type != "auto" || saved.selectedBridge.isBlank()),
                        onClick = { operation(strings.localized("Мост выбран", "Bridge selected")) { viewModel.selectTorBridge("", type) } },
                        enabled = !busy && count > 0, label = { Text("$label · $count") },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.accentCyan.copy(alpha = .15f), selectedLabelColor = colors.accentCyan))
                }
            }
            if (saved.bridgeTransport == "vanilla") {
                Text(strings.localized(
                    "Порт задаёт владелец моста. Если Vanilla не подключается, попробуйте другой мост, WebTunnel или obfs4.",
                    "The bridge operator sets its port. If Vanilla cannot connect, try another bridge, WebTunnel, or obfs4."
                ), color = colors.textSecondary, fontSize = 12.sp)
            }
            if (!simple) TorBridgeMetricSelector(metric, editable && !busy && !probing) { selected ->
                operation(resources.getString(com.danieldk.splatevpn.R.string.tor_metric_saved)) {
                    viewModel.applyTorConnectionSettings(saved.copy(bridgeSelectionMetric = selected.id,
                        selectedBridge = "", autoSelectFastestBridge = true))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { choosing = true }, enabled = !busy && saved.bridges.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Text(strings.localized("Выбрать мост", "Choose bridge"))
                }
                OutlinedButton(onClick = { viewModel.probeTorBridges() }, enabled = !busy && saved.bridges.isNotEmpty() && !probing && runtime.phase != TorPhase.STARTING, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Speed, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (metric == TorBridgeMetric.SPEED) resources.getString(com.danieldk.splatevpn.R.string.tor_metric_probe) else strings.localized("Тест задержки", "Probe delay"))
                }
                TextButton(onClick = { operation(strings.localized("Мосты отключены", "Bridges disabled")) {
                    viewModel.applyTorConnectionSettings(saved.copy(useBridges = false))
                } }, enabled = !busy && saved.useBridges) { Text(strings.localized("Без мостов", "No bridges")) }
            }
            if (benchmark.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accentCyan, trackColor = colors.borderSubtle)
                Text(resources.getString(com.danieldk.splatevpn.R.string.tor_speed_progress, benchmark.completed, benchmark.total),
                    color = colors.accentCyan, fontSize = 11.sp)
            }
            if (metric == TorBridgeMetric.SPEED && benchmark.failed && !benchmark.running) {
                Text(resources.getString(com.danieldk.splatevpn.R.string.tor_speed_unavailable),
                    color = colors.accentAmber, fontSize = 11.sp)
            }
            if (probing && runtime.phase != TorPhase.STARTING) {
                TextButton(onClick = viewModel::cancelTorBridgeProbes) { Text(strings.cancel) }
            }
            if (isProbingBridges) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accentCyan, trackColor = colors.borderSubtle)
                Text(strings.localized("Проверка доступности и задержки мостов…", "Probing bridge reachability and latency…"),
                    color = colors.accentCyan, fontSize = 11.sp)
            }
            if (runtime.phase == TorPhase.RUNNING) {
                OutlinedButton(onClick = { viewModel.refreshTorCircuit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(strings.localized("Сменить цепочку (New Identity)", "New Tor circuit (New Identity)"))
                }
            }
        }
        if (!simple || bridgeDetails) TorGlassSection {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, null, tint = colors.accentAmber, modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                Text(strings.localized("Импорт мостов", "Import bridges"), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.getText()?.text?.let(::importText) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(strings.localized("Из буфера", "Paste"))
                }
                OutlinedButton(onClick = scan, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.QrCodeScanner, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("QR")
                }
            }
            if (bridgeCounts.isNotEmpty()) {
                val counts = bridgeCounts.entries.joinToString(" · ") { (transport, count) ->
                    val label = if (transport == "vanilla") strings.localized("Обычные", "Vanilla") else transport
                    "$label $count"
                }
                Text(counts, color = colors.textSecondary, fontSize = 12.sp)
            }
            if (saved.lastUpdatedAt > 0) Text(strings.localized("Загружено: ", "Downloaded: ") + java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(saved.lastUpdatedAt)), color = colors.textTertiary, fontSize = 11.sp)
            if (saved.lastImportSkipped > 0) Text(strings.localized("Некорректных мостов пропущено: ", "Malformed bridges skipped: ") + saved.lastImportSkipped,
                color = colors.accentAmber, fontSize = 12.sp)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accentCyan, trackColor = colors.borderSubtle)
        message?.let { Text(it, color = if (failed) colors.accentCrimson else colors.accentEmerald, fontSize = 12.sp) }
        if (!simple) OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth().testTag("torAdvanced")) {
            Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(strings.localized(if (advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки", if (advanced) "Hide advanced settings" else "Advanced settings"))
        }
        if (!simple && advanced) TorGlassSection {
            Text(strings.localized("Ручные мосты и реле", "Manual bridges and relays"), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            if (!editable) Text(strings.localized("Отключитесь для изменения дополнительных настроек.", "Disconnect to edit advanced settings."), color = colors.accentAmber, fontSize = 12.sp)
            OutlinedTextField(bridgesText, { bridgesText = it }, Modifier.fillMaxWidth(), enabled = editable && !busy,
                label = { Text(strings.localized("Мосты: по одному на строку", "Bridges: one per line")) }, minLines = 3, maxLines = 6)
            Text(strings.localized("Поля ниже можно оставить пустыми. Для ручного выбора — 40-значные отпечатки реле через запятую.",
                "Leave the fields below blank for automatic selection. For manual selection, enter comma-separated 40-character relay fingerprints."), color = colors.textSecondary, fontSize = 12.sp)
            OutlinedTextField(entry, { entry = it }, Modifier.fillMaxWidth(), enabled = editable && !busy, label = { Text("EntryNodes") })
            OutlinedTextField(exit, { exit = it }, Modifier.fillMaxWidth(), enabled = editable && !busy, label = { Text("ExitNodes") })
            OutlinedTextField(excluded, { excluded = it }, Modifier.fillMaxWidth(), enabled = editable && !busy, label = { Text("ExcludeNodes") })
            TorToggle(strings.localized("Строго соблюдать исключения нод", "Strict relay exclusions"), strict, editable && !busy) { strict = it }
            TorToggle(strings.localized("Оптимистичная передача данных (OptimisticData)", "Optimistic data transmission (OptimisticData)"),
                optimisticData, editable && !busy) { optimisticData = it }
            TorToggle(strings.localized("Автовыбор быстрейшего моста по задержке", "Auto-select fastest bridge by latency"),
                autoSelectFastest, editable && !busy) { autoSelectFastest = it }
            OutlinedTextField(customSni, { customSni = it }, Modifier.fillMaxWidth(), enabled = editable && !busy,
                label = { Text(strings.localized("Пользовательский SNI / Fronting (опционально)", "Custom SNI / Fronting (optional)")) })
            Text(strings.localized("Tor передаёт TCP и DNS выбранных приложений. UDP, прямые правила и SmartDNS в этом режиме не используются.",
                "Tor carries TCP and DNS for selected apps. UDP, direct routes and SmartDNS are unavailable in this mode."), color = colors.textTertiary, fontSize = 12.sp)
            Button(onClick = { operation(strings.localized("Настройки Tor сохранены", "Tor settings saved")) { viewModel.saveTorSettings(draft()) } },
                enabled = editable && !busy, modifier = Modifier.fillMaxWidth().testTag("torSave")) { Text(strings.localized("Сохранить", "Save")) }
        }
    }
    importedUrl?.let { url ->
        AddTorSubscriptionDialog(viewModel, enabled = editable, onDismiss = { importedUrl = null }, initialUrl = url)
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(strings.localized("Выберите один мост", "Choose one bridge"), modifier = Modifier.weight(1f))
            TextButton(onClick = { viewModel.probeTorBridges() }, enabled = !probing && runtime.phase != TorPhase.STARTING) {
                Text(strings.localized("Тест", "Test"), fontSize = 12.sp)
            }
        }
    },
        text = {
            val candidates = saved.bridges.filter { saved.bridgeTransport == "auto" || torBridgeTransport(it) == saved.bridgeTransport }.sortedWith(compareByDescending<String> {
                if (metric == TorBridgeMetric.SPEED) bridgeSpeeds[it] ?: 0.0 else 0.0
            }.thenBy { bridgeLatencies[it]?.takeIf { value -> value > 0 } ?: Long.MAX_VALUE })
            LazyColumn(Modifier.heightIn(max = 380.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(candidates, key = { it }) { bridge ->
                    val latency = bridgeLatencies[bridge]
                    val latencyText = if (metric == TorBridgeMetric.SPEED && bridgeSpeeds[bridge] != null)
                        resources.getString(com.danieldk.splatevpn.R.string.tor_metric_speed_value, bridgeSpeeds.getValue(bridge)) else when {
                        latency == null -> ""
                        latency > 0 -> "$latency ms"
                        else -> strings.localized("недоступен", "unreachable")
                    }
                    val latencyColor = when {
                        metric == TorBridgeMetric.SPEED && bridgeSpeeds[bridge] != null -> colors.accentEmerald
                        latency == null -> colors.textTertiary
                        latency in 1..499 -> colors.accentEmerald
                        latency in 500..1499 -> colors.accentAmber
                        else -> colors.accentCrimson
                    }
                    val sni = TorConfigParser.extractBridgeSni(bridge)
                    Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14).tvFocusOutline().selectable(selectedBridge == bridge, role = Role.RadioButton) {
                        choosing = false
                        operation(strings.localized("Мост выбран", "Bridge selected")) { viewModel.selectTorBridge(bridge, saved.bridgeTransport) }
                    }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selectedBridge == bridge, onClick = null)
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.weight(1f)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(bridgeLabel(bridge), color = colors.textPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (latencyText.isNotEmpty()) {
                                    Text(latencyText, color = latencyColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (!sni.isNullOrBlank()) {
                                Text("SNI: $sni", color = colors.accentCyan, fontSize = 10.sp)
                            }
                            Text(bridgeFingerprint(bridge).take(12) + "…", color = colors.textTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text(strings.localized("Закрыть", "Close")) } })
}

private fun bridgeLabel(bridge: String): String {
    val type = torBridgeTransport(bridge)
    val address = if (type == "vanilla") bridge.substringBefore(' ') else bridge.substringAfter(' ').substringBefore(' ')
    return "${when (type) { "vanilla" -> "Vanilla"; "webtunnel" -> "WebTunnel"; "snowflake" -> "Snowflake"; else -> "obfs4" }} · $address"
}

private fun bridgeFingerprint(bridge: String): String = bridge.split(' ').getOrNull(if (torBridgeTransport(bridge) == "vanilla") 1 else 2).orEmpty()

@Composable
private fun TorGlassSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun TorToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = LocalAppThemeColors.current.textPrimary, fontSize = 13.sp)
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}
