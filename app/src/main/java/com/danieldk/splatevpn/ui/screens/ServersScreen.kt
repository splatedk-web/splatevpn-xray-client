package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.data.isProtectedWtpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch
import com.danieldk.splatevpn.ui.components.SplateDropdownMenuItem as DropdownMenuItem

import com.danieldk.splatevpn.ui.components.SplateDropdownMenu as DropdownMenu

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import com.danieldk.splatevpn.ui.components.SplateButton as Button

import androidx.compose.ui.draw.alpha

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.PageHeader
import com.danieldk.splatevpn.ui.components.SlidingTabStrip
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.*
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.danieldk.splatevpn.data.LinkParser
import com.danieldk.splatevpn.ui.components.QrCodeGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    val tb = gb / 1024.0
    return when {
        tb >= 1.0 -> String.format(Locale.US, "%.1f TB", tb)
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

/**
 * Four primary views to clearly isolate All Nodes, Preset Servers, Subscriptions, and Personal Configs
 */
enum class NodeViewMode {
    ALL,           // Все узлы
    PRESET,        // Серверы встроенных подписок
    SUBSCRIPTIONS, // Хаб подписок
    PERSONAL       // Личные конфиги
}

private fun formatSubUpdated(timestamp: Long, strings: AppStrings): String {
    if (timestamp <= 0L) return strings.subscriptionNever
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000L -> strings.subscriptionJustNow
        diff < 3600_000L -> String.format(strings.subscriptionMinutesAgo, diff / 60_000L)
        diff < 86400_000L -> String.format(strings.subscriptionHoursAgo, diff / 3600_000L)
        else -> {
            val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

private fun formatSubNodesCount(count: Int): String {
    val remainder10 = count % 10
    val remainder100 = count % 100
    val word = when {
        remainder100 in 11..19 -> "узлов"
        remainder10 == 1 -> "узел"
        remainder10 in 2..4 -> "узла"
        else -> "узлов"
    }
    return "$count $word"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    viewModel: MainViewModel
) {
    val browsingTor by viewModel.serverBrowserTor.collectAsState()
    BackHandler(enabled = !com.danieldk.splatevpn.ui.components.LocalTvControls.current) {
        if (browsingTor) viewModel.browseServerMode(false) else viewModel.selectTab(AppTab.HOME)
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        com.danieldk.splatevpn.ui.components.ServerConnectionModeSelector(viewModel,
            Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 16.dp, start = 16.dp, end = 16.dp))
        if (browsingTor) {
            Column(Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 20.dp + LocalBottomDockInset.current)) {
                com.danieldk.splatevpn.ui.components.TorSettingsCard(viewModel)
            }
        } else Box(Modifier.weight(1f)) { VpnServersScreen(viewModel) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VpnServersScreen(
    viewModel: MainViewModel
) {
    val servers by viewModel.allServers.collectAsState()
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val isSortByPing by viewModel.isSortByPing.collectAsState()
    val pingMode by viewModel.pingMode.collectAsState()
    val isRealDelay = pingMode.lowercase() in listOf("real_delay", "http", "real")
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val strings = LocalAppStrings.current
    val pingResources = com.danieldk.splatevpn.ui.theme.LocalAppResources.current
    val colors = LocalAppThemeColors.current

    val savedListPreferences = remember { viewModel.getServersListPreferences() }
    var activeViewMode by rememberSaveable {
        mutableStateOf(NodeViewMode.entries.firstOrNull { it.name == savedListPreferences.viewMode } ?: NodeViewMode.ALL)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // null = show all; "custom" = only personal; or subId = specific subscription
    var selectedFilterSubId by rememberSaveable { mutableStateOf(savedListPreferences.filterSubId) }
    var folderOrigin by rememberSaveable { mutableStateOf<NodeViewMode?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var showAddUnifiedSheet by remember { mutableStateOf(false) }
    var initialImportPayload by remember { mutableStateOf("") }
    var editingServer by remember { mutableStateOf<ServerNode?>(null) }
    var editingSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var subscriptionUpdateReport by remember { mutableStateOf<com.danieldk.splatevpn.data.SubscriptionUpdateReport?>(null) }
    val autoUpdateInterval by viewModel.autoUpdateInterval.collectAsState()
    var deletingSub by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val updatingSubIds by viewModel.updatingSubscriptionIds.collectAsState()
    var isUpdatingAllSubs by remember { mutableStateOf(false) }
    var sharingServer by remember { mutableStateOf<ServerNode?>(null) }
    var sharingSub by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var isGroupedBySub by rememberSaveable { mutableStateOf(savedListPreferences.grouped) }
    var expandedSubIds by rememberSaveable { mutableStateOf(savedListPreferences.expandedGroupIds) }
    var isPersonalSelectionMode by rememberSaveable { mutableStateOf(false) }
    var selectedPersonalIdsForDeletion by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    fun exitPersonalSelectionMode() {
        isPersonalSelectionMode = false
        selectedPersonalIdsForDeletion = emptySet()
        showBatchDeleteConfirm = false
    }
    fun enterPersonalSelectionMode(initialId: String? = null) {
        if (activeViewMode != NodeViewMode.PERSONAL) {
            activeViewMode = NodeViewMode.PERSONAL
            selectedFilterSubId = null
            folderOrigin = null
        }
        expandedSubIds = expandedSubIds + "personal"
        isPersonalSelectionMode = true
        selectedPersonalIdsForDeletion = if (initialId != null) setOf(initialId) else emptySet()
    }
    val hasExpandedGroups = isGroupedBySub && activeViewMode == NodeViewMode.ALL && selectedFilterSubId == null && expandedSubIds.isNotEmpty()
    val hasLocalBack = isPersonalSelectionMode || searchQuery.isNotEmpty() || selectedFilterSubId != null || hasExpandedGroups || activeViewMode != NodeViewMode.ALL
    fun closeFolder() {
        selectedFilterSubId = null
        folderOrigin?.let { activeViewMode = it }
        folderOrigin = null
    }
    fun navigateBack() {
        when {
            isPersonalSelectionMode -> exitPersonalSelectionMode()
            searchQuery.isNotEmpty() -> searchQuery = ""
            selectedFilterSubId != null -> closeFolder()
            hasExpandedGroups -> expandedSubIds = emptySet()
            activeViewMode != NodeViewMode.ALL -> activeViewMode = NodeViewMode.ALL
            else -> viewModel.selectTab(AppTab.HOME)
        }
    }
    BackHandler(enabled = hasLocalBack) { navigateBack() }
    LaunchedEffect(activeViewMode) {
        if (activeViewMode != NodeViewMode.PERSONAL && isPersonalSelectionMode) {
            exitPersonalSelectionMode()
        }
    }

    LaunchedEffect(activeViewMode, selectedFilterSubId, isGroupedBySub, expandedSubIds) {
        viewModel.saveServersListPreferences(
            com.danieldk.splatevpn.data.ServersListPreferences(
                activeViewMode.name, selectedFilterSubId, isGroupedBySub, expandedSubIds
            )
        )
    }
    val subscriptionsMap = remember(subscriptions) { subscriptions.associateBy { it.id } }

    // Distinct lists
    val realSubscriptions = remember(subscriptions) {
        subscriptions.filter { it.id != "builtin" }
            .sortedBy { it.id != com.danieldk.splatevpn.SplateConstants.PRESET_SUB_SPONSOR_ID }
    }
    val filteredSubscriptions = remember(realSubscriptions, searchQuery, strings) {
        realSubscriptions.filter { sub ->
            com.danieldk.splatevpn.data.filterSubscriptions(listOf(sub), searchQuery).isNotEmpty() ||
                subscriptionDisplayName(sub.id, sub.name, strings).contains(searchQuery.trim(), ignoreCase = true)
        }
    }
    val disabledNodeIds by viewModel.disabledServerIds.collectAsState()
    val disabledSubIds = remember(subscriptions) {
        subscriptions.filter { !it.enabled }.map { it.id }.toSet()
    }
    val enabledSubscriptions = remember(realSubscriptions) { realSubscriptions.filter { it.enabled } }
    LaunchedEffect(activeViewMode, selectedFilterSubId, disabledSubIds) {
        if (activeViewMode in setOf(NodeViewMode.ALL, NodeViewMode.PRESET) && selectedFilterSubId in disabledSubIds) {
            selectedFilterSubId = null
        }
    }
    val browserSnapshot by produceState(
        ServerBrowserSnapshot(), servers, activeViewMode, selectedFilterSubId, isSortByPing, searchQuery, disabledSubIds, disabledNodeIds
    ) {
        value = withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val activeCount = servers.count { it.subId !in disabledSubIds && viewModel.isNodeEnabled(it, disabledNodeIds, now) }
        val preset = ArrayList<ServerNode>()
        val personal = ArrayList<ServerNode>()
        val subs = ArrayList<ServerNode>()
        val presetIds = com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS
        for (s in servers) {
            when {
                s.subId in presetIds -> preset.add(s)
                s.subId.isBlank() || s.subId == "custom" || s.subId == "builtin" -> personal.add(s)
                else -> subs.add(s)
            }
        }
            val presetServers = preset
            val personalServers = personal
            val subscriptionServers = subs
            val baseList = when (activeViewMode) {
                NodeViewMode.ALL -> {
                    when (selectedFilterSubId) {
                        null -> servers
                        "custom" -> personalServers
                        "preset" -> presetServers
                        else -> servers.filter { it.subId == selectedFilterSubId }
                    }
                }
                NodeViewMode.PRESET -> {
                    if (selectedFilterSubId != null && selectedFilterSubId in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS) {
                        servers.filter { it.subId == selectedFilterSubId }
                    } else {
                        presetServers
                    }
                }
                NodeViewMode.SUBSCRIPTIONS -> {
                    if (selectedFilterSubId != null && selectedFilterSubId != "custom") {
                        servers.filter { it.subId == selectedFilterSubId }
                    } else {
                        subscriptionServers
                    }
                }
                NodeViewMode.PERSONAL -> {
                    personalServers
                }
            }

            val visibleNodes = if (activeViewMode == NodeViewMode.ALL || activeViewMode == NodeViewMode.PRESET) {
                baseList.filter { it.subId !in disabledSubIds }
            } else baseList

            val searched = if (searchQuery.isBlank()) {
                visibleNodes
            } else {
                val q = searchQuery.trim()
                visibleNodes.filter {
                    it.name.contains(q, ignoreCase = true) ||
                            it.address.contains(q, ignoreCase = true) ||
                            it.protocol.contains(q, ignoreCase = true) ||
                            it.subName.contains(q, ignoreCase = true)
                }
            }

            val sorted = if (isSortByPing) {
                searched.sortedWith(
                    compareBy<ServerNode> { if (it.ping > 0) 0 else 1 }
                        .thenBy { if (it.ping > 0) it.ping else Long.MAX_VALUE }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
            } else if (searchQuery.isNotBlank()) {
                searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            } else {
                searched
            }
            ServerBrowserSnapshot(preset, personal, subs, activeCount,
                sorted.sortedBy { it.subId != com.danieldk.splatevpn.SplateConstants.PRESET_SUB_SPONSOR_ID })
        }
    }
    val presetServers = browserSnapshot.presets
    val personalServers = browserSnapshot.personal
    val activeServersCount = browserSnapshot.activeCount
    val filteredServers = browserSnapshot.visible

    // Active sub entity for the banner
    val activeSubEntity = remember(realSubscriptions, selectedFilterSubId) {
        if (selectedFilterSubId != null && selectedFilterSubId != "custom") {
            realSubscriptions.firstOrNull { it.id == selectedFilterSubId }
        } else null
    }


    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .padding(top = 20.dp, start = 16.dp, end = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (searchQuery.isNotEmpty() || selectedFilterSubId != null) {
                IconButton(onClick = ::navigateBack, modifier = Modifier.size(44.dp).testTag("serversNavigateBack")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.server_back), tint = colors.textPrimary)
                }
                Spacer(Modifier.width(4.dp))
            }
            PageHeader(
                modifier = Modifier.weight(1f),
                title = when (activeViewMode) {
                    NodeViewMode.ALL -> strings.serversPageTitle
                    NodeViewMode.PRESET -> strings.presetsPageTitle
                    NodeViewMode.SUBSCRIPTIONS -> strings.subscriptionsTitle
                    NodeViewMode.PERSONAL -> strings.personalNodesLabel
                },
                subtitle = when (activeViewMode) {
                    NodeViewMode.SUBSCRIPTIONS -> strings.subscriptionsPageSubtitle
                    NodeViewMode.PERSONAL -> strings.personalPageSubtitle
                    else -> strings.serversPageSubtitle
                },
                count = when (activeViewMode) {
                    NodeViewMode.ALL, NodeViewMode.PRESET -> filteredServers.size.toString()
                    NodeViewMode.SUBSCRIPTIONS -> "${realSubscriptions.count { it.enabled }}/${realSubscriptions.size}"
                    NodeViewMode.PERSONAL -> personalServers.size.toString()
                },
                countColor = when (activeViewMode) {
                    NodeViewMode.SUBSCRIPTIONS -> colors.accentAmber
                    NodeViewMode.PERSONAL -> colors.accentEmerald
                    else -> colors.accentCyan
                },
                trailing = {
                    OutlinedIconButton(onClick = { initialImportPayload = ""; showAddUnifiedSheet = true }, modifier = Modifier.size(48.dp),
                        border = BorderStroke(1.dp, colors.borderSubtle), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Add, strings.unifiedAddTitle, Modifier.size(24.dp), tint = colors.accentCyan)
                    }
                }
            )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                // Right: Action buttons
                if (activeViewMode == NodeViewMode.SUBSCRIPTIONS) {
                    // Subscriptions Mode Dedicated Actions: Refresh All + Add Subscription
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Refresh all subscriptions button
                        val refreshSubRotation = if (isUpdatingAllSubs && LocalUiPerformanceSettings.current.animationsEnabled) {
                            val infiniteTransition = rememberInfiniteTransition(label = "update_all_subs_spin")
                            val spin by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "update_all_subs_rot"
                            )
                            spin
                        } else 0f

                        Box(
                            modifier = Modifier
                                .height(48.dp)
                                .liquidGlass(cornerRadius = 9, innerSheen = true, accentBorder = colors.accentCyan.copy(alpha = 0.35f))
                                .tactileClickable(targetScale = 0.92f, enabled = !isUpdatingAllSubs && updatingSubIds.isEmpty() && realSubscriptions.any { it.enabled && it.url.isNotBlank() }) {
                                    isUpdatingAllSubs = true
                                    viewModel.updateAllSubscriptions { report ->
                                        isUpdatingAllSubs = false
                                        subscriptionUpdateReport = report
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isUpdatingAllSubs) {
                                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = colors.accentCyan)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = strings.subscriptionUpdateAll,
                                        tint = colors.accentCyan,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .graphicsLayer { rotationZ = refreshSubRotation }
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isUpdatingAllSubs) strings.updating else strings.subscriptionUpdateAll,
                                    color = colors.textPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }


                    }
                } else {
                    val isAutoSelect by viewModel.isAutoSelect.collectAsState()
                    Row(
                        Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16, innerSheen = true).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ServerToolAction(
                            label = if (isGroupedBySub) strings.serverToolsFolders else strings.serverToolsList,
                            description = strings.serverToolsGrouping,
                            icon = if (isGroupedBySub) Icons.Default.Layers else Icons.AutoMirrored.Filled.FormatListBulleted,
                            accent = colors.accentAmber, selected = isGroupedBySub, modifier = Modifier.weight(1f)
                        ) {
                            isGroupedBySub = !isGroupedBySub
                            Toast.makeText(context, if (isGroupedBySub) strings.serverToolsGrouped else strings.serverToolsUngrouped, Toast.LENGTH_SHORT).show()
                        }
                        ServerToolAction(
                            label = strings.serverToolsSort, description = strings.serverToolsSortDescription,
                            icon = Icons.AutoMirrored.Filled.Sort, accent = colors.accentCyan,
                            selected = isSortByPing, modifier = Modifier.weight(1f)
                        ) { viewModel.toggleSortByPing() }
                        ServerToolAction(
                            label = strings.serverToolsAuto, description = strings.autoSelectTitle,
                            icon = Icons.Default.Bolt, accent = colors.accentCyan,
                            selected = isAutoSelect, enabled = !isPinging, modifier = Modifier.weight(1f)
                        ) {
                            viewModel.autoSelectFastestServer(enableAutoBalancer = true) { best ->
                                if (best != null) Toast.makeText(context, strings.autoSelectSelected.format(best.name), Toast.LENGTH_SHORT).show()
                            }
                        }
                        ServerToolAction(
                            label = if (isRealDelay) "REAL" else "TCP", description = strings.pingModeTitle,
                            icon = Icons.Default.SwapHoriz, accent = colors.accentEmerald,
                            selected = isRealDelay, modifier = Modifier.weight(1f)
                        ) {
                            viewModel.setPingMode(if (isRealDelay) "tcp" else "real_delay")
                            Toast.makeText(context, if (isRealDelay) strings.pingModeTcp else strings.pingModeRealDelay, Toast.LENGTH_SHORT).show()
                        }
                        ServerToolAction(
                            label = if (isPinging) LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.server_stop_checks_short) else strings.serverToolsTest,
                            description = LocalAppResources.current.getString(if (isPinging) com.danieldk.splatevpn.R.string.server_stop_checks else com.danieldk.splatevpn.R.string.server_check_servers),
                            icon = if (isPinging) Icons.Default.Stop else Icons.Default.NetworkCheck, accent = colors.accentCyan,
                            action = true, enabled = true, modifier = Modifier.weight(1f).testTag("serversPingToggle")
                        ) {
                            if (isPinging) {
                                viewModel.cancelPingChecks()
                            } else {
                                val showResult: (com.danieldk.splatevpn.data.ServerPingSummary) -> Unit = { summary ->
                                    val mode = when (pingMode.lowercase()) {
                                        "real_delay", "http", "real" -> "Real Delay"
                                        "icmp" -> "ICMP"
                                        else -> "TCP Ping"
                                    }
                                    Toast.makeText(context, pingResources.getString(com.danieldk.splatevpn.R.string.server_ping_result,
                                        mode, summary.checked, summary.available), Toast.LENGTH_SHORT).show()
                                }
                                if (activeViewMode == NodeViewMode.ALL && selectedFilterSubId == null) viewModel.testAllPings(showResult)
                                else viewModel.testServersPing(filteredServers, showResult)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Unified Compact Search Bar with Inline Quick Actions (Paste & QR/Add)
            SearchBarField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = when (activeViewMode) {
                    NodeViewMode.PRESET -> strings.uiPresetSearch
                    NodeViewMode.SUBSCRIPTIONS -> strings.subscriptionsSearch
                    NodeViewMode.PERSONAL -> strings.uiPersonalSearch
                    else -> strings.searchServers
                },
                colors = colors,
                onPasteClick = {
                    val clipText = clipboardManager.getText()?.text
                    initialImportPayload = clipText?.trim() ?: ""
                    showAddUnifiedSheet = true
                },
                onQrClick = {
                    initialImportPayload = ""
                    showAddUnifiedSheet = true
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            FloatingServerListPanel(
                modifier = Modifier.weight(1f),
                header = {
                    ServerCategoryTabs(
                        activeViewMode, activeServersCount,
                        realSubscriptions.count { it.enabled }, realSubscriptions.size, personalServers.size
                    ) { mode -> activeViewMode = mode; selectedFilterSubId = null; folderOrigin = null }
                }
            ) { listTopInset ->
            Column(Modifier.fillMaxSize()) {
            val commonListPreamble: @Composable () -> Unit = {
            // Secondary Subscription Isolation Strip in ALL Mode (when not grouped)
            if (activeViewMode == NodeViewMode.ALL && enabledSubscriptions.isNotEmpty() && !isGroupedBySub) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAllActiveSelected = selectedFilterSubId == null
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isAllActiveSelected) colors.accentCyan.copy(alpha = 0.18f) else colors.surface.copy(alpha = 0.4f))
                            .border(
                                width = 1.dp,
                                color = if (isAllActiveSelected) colors.accentCyan else colors.borderSubtle.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .tvFocusOutline().clickable { selectedFilterSubId = null }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = String.format(strings.uiAllActive, activeServersCount),
                            color = if (isAllActiveSelected) colors.accentCyan else colors.textSecondary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isAllActiveSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    enabledSubscriptions.forEach { sub ->
                        val isSubSelected = selectedFilterSubId == sub.id
                        val chipColor = if (sub.id in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS) colors.accentCyan else colors.accentAmber
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSubSelected -> chipColor.copy(alpha = 0.2f)
                                        else -> colors.surface.copy(alpha = 0.4f)
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        isSubSelected -> chipColor
                                        else -> colors.borderSubtle.copy(alpha = 0.5f)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .tvFocusOutline().clickable {
                                    selectedFilterSubId = if (selectedFilterSubId == sub.id) null else sub.id
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${subscriptionDisplayName(sub.id, sub.name, strings)} (${sub.nodeCount})",
                                color = when {
                                    isSubSelected -> chipColor
                                    else -> colors.textSecondary
                                },
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSubSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            }

            // Content Area depending on Active View Mode
            when (activeViewMode) {
                NodeViewMode.ALL -> {
                    val listPreamble: @Composable () -> Unit = {
                        commonListPreamble()
                    // Active Subscription Banner (when filtered to a specific subscription)
                    if (activeSubEntity != null) {
                        Spacer(modifier = Modifier.height(5.dp))
                        ActiveSubscriptionBanner(
                            sub = activeSubEntity,
                            isUpdating = activeSubEntity.id in updatingSubIds,
                            isPinging = isPinging,
                            onStopPing = viewModel::cancelPingChecks,
                            onToggleEnabled = { enabled ->
                                viewModel.toggleSubscriptionEnabled(activeSubEntity.id, enabled)
                            },
                            onPing = {
                                viewModel.testSubscriptionPings(activeSubEntity.id) { summary ->
                                    Toast.makeText(context, pingResources.getString(com.danieldk.splatevpn.R.string.server_ping_result, subscriptionDisplayName(activeSubEntity.id, activeSubEntity.name, strings), summary.checked, summary.available), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onUpdate = {
                                viewModel.updateSubscription(activeSubEntity.id) { res ->
                                    res.onSuccess { count ->
                                        Toast.makeText(context, String.format(strings.subUpdatedSuccess, count), Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        Toast.makeText(context, String.format(strings.errorPrefix, err.message ?: ""), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onShare = { if (!isProtectedWtpn(activeSubEntity.id, activeSubEntity.url)) sharingSub = activeSubEntity },
                            onDismiss = ::closeFolder
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    }

                    // Servers List
                    ServerListSection(
                        contentTopInset = listTopInset, topContent = listPreamble,
                        servers = filteredServers,
                        selectedServer = selectedServer,
                        isAutoBalancerVisible = searchQuery.isBlank() && selectedFilterSubId == null,
                        isPinging = isPinging,
                        isGrouped = isGroupedBySub && selectedFilterSubId == null && searchQuery.isBlank(),
                        expandedGroups = expandedSubIds,
                        onToggleGroupCollapse = { groupId ->
                            expandedSubIds = if (groupId in expandedSubIds) expandedSubIds - groupId else expandedSubIds + groupId
                        },
                        subscriptionsMap = subscriptionsMap,
                        onSubscriptionDetails = { editingSubscription = it },
                        onToggleSubEnabled = { subId, enabled ->
                            viewModel.toggleSubscriptionEnabled(subId, enabled)
                            val subName = subscriptions.firstOrNull { it.id == subId }?.name ?: ""
                            val msg = String.format(if (enabled) strings.subscriptionEnabledMessage else strings.subscriptionDisabledMessage, subName)
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        onSelectServer = { viewModel.selectServer(it) },
                        onEditServer = { if (!isProtectedWtpn(it.subId, subscriptionsMap[it.subId]?.url)) editingServer = it },
                        onDeleteServer = { viewModel.deleteServer(it) },
                        onShareServer = { if (!isProtectedWtpn(it.subId, subscriptionsMap[it.subId]?.url)) sharingServer = it },
                        viewModel = viewModel,
                        onEnterSelectionMode = ::enterPersonalSelectionMode,
                        onAddClick = {
                            initialImportPayload = ""
                            showAddUnifiedSheet = true
                        }
                    )
                }

                NodeViewMode.PRESET -> {
                    if (presetServers.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 32.dp + listTopInset, bottom = 90.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .liquidGlass(cornerRadius = 20, innerSheen = true, elevated = true)
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(58.dp)
                                        .liquidGlass(
                                            cornerRadius = 18,
                                            innerSheen = true,
                                            accentBorder = colors.accentCyan.copy(alpha = 0.5f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = colors.accentCyan,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }

                                Text(
                                    text = strings.presetServersTitle,
                                    color = colors.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                Text(
                                    text = strings.uiPresetsEmpty,
                                    color = colors.textSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(colors.accentCyan, colors.accentCyan.copy(alpha = 0.85f))
                                            )
                                        )
                                        .tactileClickable(targetScale = 0.95f) {
                                            viewModel.restorePresetSubscriptions { count ->
                                                Toast.makeText(
                                                    context,
                                                    String.format(strings.localized("Восстановлено %d серверов", "Restored %d servers"), count),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = LocalAppThemeColors.current.onAccent,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = strings.uiRestorePresets,
                                            color = LocalAppThemeColors.current.onAccent,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        val listPreamble: @Composable () -> Unit = {
                            commonListPreamble()
                        if (activeSubEntity != null) {
                            Spacer(modifier = Modifier.height(5.dp))
                            ActiveSubscriptionBanner(
                                sub = activeSubEntity,
                                isUpdating = activeSubEntity.id in updatingSubIds,
                                isPinging = isPinging,
                                onStopPing = viewModel::cancelPingChecks,
                                onToggleEnabled = { enabled ->
                                    viewModel.toggleSubscriptionEnabled(activeSubEntity.id, enabled)
                                },
                                onPing = {
                                    viewModel.testSubscriptionPings(activeSubEntity.id) { summary ->
                                        Toast.makeText(context, pingResources.getString(com.danieldk.splatevpn.R.string.server_ping_result, subscriptionDisplayName(activeSubEntity.id, activeSubEntity.name, strings), summary.checked, summary.available), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onUpdate = {
                                    viewModel.updateSubscription(activeSubEntity.id) { res ->
                                        res.onSuccess { count ->
                                            Toast.makeText(context, String.format(strings.subUpdatedSuccess, count), Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, String.format(strings.errorPrefix, err.message ?: ""), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onShare = { if (!isProtectedWtpn(activeSubEntity.id, activeSubEntity.url)) sharingSub = activeSubEntity },
                                onDismiss = ::closeFolder
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        }
                        ServerListSection(
                            contentTopInset = listTopInset, topContent = listPreamble,
                            servers = filteredServers,
                            selectedServer = selectedServer,
                            isAutoBalancerVisible = searchQuery.isBlank() && selectedFilterSubId == null,
                            isPinging = isPinging,
                            onSelectServer = { viewModel.selectServer(it) },
                            onEditServer = { if (!isProtectedWtpn(it.subId, subscriptionsMap[it.subId]?.url)) editingServer = it },
                            onDeleteServer = { viewModel.deleteServer(it) },
                            onShareServer = { if (!isProtectedWtpn(it.subId, subscriptionsMap[it.subId]?.url)) sharingServer = it },
                            viewModel = viewModel,
                            onAddClick = null
                        )
                    }
                }

                NodeViewMode.SUBSCRIPTIONS -> {
                    val subscriptionsPreamble: @Composable () -> Unit = {
                        commonListPreamble()
                        Spacer(modifier = Modifier.height(4.dp))

                        // Subscriptions Mode Header Toolbar: Stats & Preset Restore
                        val hasMissingPresets = remember(realSubscriptions) {
                            com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS.any { presetId ->
                                realSubscriptions.none { it.id == presetId }
                            }
                        }

                        if (hasMissingPresets) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .liquidGlass(cornerRadius = 10, innerSheen = true, accentBorder = colors.accentCyan.copy(alpha = 0.45f))
                                    .tactileClickable(targetScale = 0.97f) {
                                        viewModel.restorePresetSubscriptions { count ->
                                            Toast.makeText(
                                                context,
                                                String.format(strings.localized("Восстановлено %d предустановленных узлов", "Restored %d preset nodes"), count),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.SettingsBackupRestore, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = strings.uiRestorePresets,
                                    color = colors.accentCyan,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    val pullRefreshState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
                    val onPullRefresh: () -> Unit = {
                        if (!isUpdatingAllSubs && updatingSubIds.isEmpty() && realSubscriptions.any { it.enabled && it.url.isNotBlank() }) {
                            isUpdatingAllSubs = true
                            viewModel.updateAllSubscriptions { report ->
                                isUpdatingAllSubs = false
                                subscriptionUpdateReport = report
                            }
                        }
                    }

                    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                        isRefreshing = isUpdatingAllSubs,
                        onRefresh = onPullRefresh,
                        state = pullRefreshState,
                        modifier = Modifier.fillMaxSize(),
                        indicator = {
                            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                                state = pullRefreshState,
                                isRefreshing = isUpdatingAllSubs,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = listTopInset),
                                color = colors.accentCyan,
                                containerColor = colors.surface
                            )
                        }
                    ) {
                        if (realSubscriptions.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(top = listTopInset, bottom = 16.dp + LocalBottomDockInset.current),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                subscriptionsPreamble()
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .liquidGlass(cornerRadius = 16, innerSheen = true, accentBorder = colors.accentAmber.copy(alpha = 0.4f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(28.dp))
                                        }
                                        Text(
                                            text = strings.uiNoSubscriptions,
                                            color = colors.textPrimary,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = strings.uiSubscriptionHint,
                                            color = colors.textSecondary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 24.dp)
                                        )
                                        Button(
                                            onClick = {
                                                initialImportPayload = ""
                                                showAddUnifiedSheet = true
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentAmber),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(strings.uiAddSubscription, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        com.danieldk.splatevpn.ui.components.SourcePrivacyCard(viewModel, compact = true)
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(top = listTopInset, bottom = 16.dp + LocalBottomDockInset.current)
                            ) {
                                item(key = "subscriptions_preamble", contentType = "preamble") { subscriptionsPreamble() }
                                if (filteredSubscriptions.isEmpty()) {
                                    item { Text(strings.subscriptionsNoResults, color = colors.textSecondary, modifier = Modifier.padding(24.dp)) }
                                }
                                items(filteredSubscriptions, key = { it.id }) { sub ->
                                SubscriptionHubCard(
                                    sub = sub,
                                    isUpdating = sub.id in updatingSubIds,
                                    isPinging = isPinging,
                                    onStopPing = viewModel::cancelPingChecks,
                                    onEdit = { if (!isProtectedWtpn(sub.id, sub.url)) editingSubscription = sub },
                                    onToggleEnabled = { enabled ->
                                        viewModel.toggleSubscriptionEnabled(sub.id, enabled)
                                        val msg = String.format(if (enabled) strings.subscriptionEnabledMessage else strings.subscriptionDisabledMessage,
                                            subscriptionDisplayName(sub.id, sub.name, strings))
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    },
                                    onToggleAutoUpdate = { auto ->
                                        viewModel.toggleSubscriptionAutoUpdate(sub.id, auto)
                                        val msg = if (auto) strings.subscriptionAutoEnabledMessage else strings.subscriptionAutoDisabledMessage
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    },
                                    onViewNodes = {
                                        folderOrigin = NodeViewMode.SUBSCRIPTIONS
                                        selectedFilterSubId = sub.id
                                        activeViewMode = if (sub.id in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS) {
                                            NodeViewMode.PRESET
                                        } else {
                                            NodeViewMode.ALL
                                        }
                                    },
                                    onPing = {
                                        viewModel.testSubscriptionPings(sub.id) { summary ->
                                            Toast.makeText(context, pingResources.getString(com.danieldk.splatevpn.R.string.server_ping_result, subscriptionDisplayName(sub.id, sub.name, strings), summary.checked, summary.available), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onUpdate = {
                                        viewModel.updateSubscription(sub.id) { res ->
                                            res.onSuccess { count ->
                                                Toast.makeText(context, String.format(strings.subUpdatedSuccess, count), Toast.LENGTH_SHORT).show()
                                            }.onFailure { err ->
                                                Toast.makeText(context, String.format(strings.errorPrefix, err.message ?: ""), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onShare = { if (!isProtectedWtpn(sub.id, sub.url)) sharingSub = sub },
                                    onDelete = { deletingSub = sub }
                                )
                            }
                            item(key = "subscriptions_privacy_policy", contentType = "privacy") {
                                Spacer(modifier = Modifier.height(6.dp))
                                com.danieldk.splatevpn.ui.components.SourcePrivacyCard(viewModel, compact = true)
                            }
                        }
                    }
                }
            }

                NodeViewMode.PERSONAL -> {

                    if (personalServers.isEmpty()) {
                        Spacer(Modifier.height(listTopInset))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .liquidGlass(cornerRadius = 16, innerSheen = true, accentBorder = colors.accentEmerald.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Lock, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(28.dp))
                                }
                                Text(
                                    text = strings.uiNoPersonal,
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = strings.uiPersonalHint,
                                    color = colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                                Button(
                                    onClick = {
                                        initialImportPayload = ""
                                        showAddUnifiedSheet = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentEmerald),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.height(48.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.uiAddConfig, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        val validPersonalIds = remember(personalServers) { personalServers.mapTo(hashSetOf()) { it.id } }
                        LaunchedEffect(validPersonalIds) {
                            if (selectedPersonalIdsForDeletion.isNotEmpty()) {
                                val pruned = selectedPersonalIdsForDeletion.intersect(validPersonalIds)
                                if (pruned.size != selectedPersonalIdsForDeletion.size) {
                                    selectedPersonalIdsForDeletion = pruned
                                }
                            }
                            if (validPersonalIds.isEmpty() && isPersonalSelectionMode) {
                                exitPersonalSelectionMode()
                            }
                        }
                        val visiblePersonalIds = remember(filteredServers) { filteredServers.mapTo(linkedSetOf()) { it.id } }
                        val allVisibleSelected = visiblePersonalIds.isNotEmpty() && visiblePersonalIds.all { it in selectedPersonalIdsForDeletion }
                        val personalListPreamble: @Composable () -> Unit = {
                            commonListPreamble()
                            AnimatedVisibility(
                                visible = isPersonalSelectionMode && filteredServers.isNotEmpty(),
                                enter = expandVertically(tween(180)) + fadeIn(tween(150)),
                                exit = shrinkVertically(tween(150)) + fadeOut(tween(120))
                            ) {
                                Column {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    PersonalBatchDeleteBar(
                                        selectedCount = selectedPersonalIdsForDeletion.size,
                                        visibleCount = visiblePersonalIds.size,
                                        allVisibleSelected = allVisibleSelected,
                                        onToggleSelectAll = {
                                            selectedPersonalIdsForDeletion = if (allVisibleSelected) {
                                                selectedPersonalIdsForDeletion - visiblePersonalIds
                                            } else {
                                                selectedPersonalIdsForDeletion + visiblePersonalIds
                                            }
                                        },
                                        onDeleteSelected = {
                                            if (selectedPersonalIdsForDeletion.isNotEmpty()) {
                                                showBatchDeleteConfirm = true
                                            }
                                        },
                                        onCancel = ::exitPersonalSelectionMode
                                    )
                                }
                            }
                        }
                        ServerListSection(
                            contentTopInset = listTopInset, topContent = personalListPreamble,
                            servers = filteredServers,
                            selectedServer = selectedServer,
                            isAutoBalancerVisible = false,
                            isPinging = isPinging,
                            isGrouped = true,
                            expandedGroups = if (isPersonalSelectionMode) expandedSubIds + "personal" else expandedSubIds,
                            onToggleGroupCollapse = { id -> expandedSubIds = if (id in expandedSubIds) expandedSubIds - id else expandedSubIds + id },
                            onSelectServer = { viewModel.selectServer(it) },
                            onEditServer = { if (!isProtectedWtpn(it.subId, subscriptionsMap[it.subId]?.url)) editingServer = it },
                            onDeleteServer = { viewModel.deleteServer(it) },
                            onShareServer = { if (!isProtectedWtpn(it.subId, subscriptionsMap[it.subId]?.url)) sharingServer = it },
                            viewModel = viewModel,
                            selectionMode = isPersonalSelectionMode,
                            selectedIdsForDeletion = selectedPersonalIdsForDeletion,
                            onToggleSelection = { id ->
                                selectedPersonalIdsForDeletion = if (id in selectedPersonalIdsForDeletion) {
                                    selectedPersonalIdsForDeletion - id
                                } else {
                                    selectedPersonalIdsForDeletion + id
                                }
                            },
                            onEnterSelectionMode = ::enterPersonalSelectionMode,
                            onAddClick = {
                                initialImportPayload = ""
                                showAddUnifiedSheet = true
                            }
                        )
                    }
                }
            }
            }
            }
        }

        if (showBatchDeleteConfirm && selectedPersonalIdsForDeletion.isNotEmpty()) {
            val targets = selectedPersonalIdsForDeletion.toList()
            val count = targets.size
            AlertDialog(
                onDismissRequest = { showBatchDeleteConfirm = false },
                containerColor = colors.surfaceElevated,
                title = {
                    Text(
                        text = strings.localized("Удалить выбранные серверы?", "Delete selected servers?"),
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = strings.localized(
                            "Будет удалено личных серверов: $count. Это действие нельзя отменить.",
                            "$count personal servers will be deleted. This action cannot be undone."
                        ),
                        color = colors.textSecondary,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBatchDeleteConfirm = false
                            viewModel.deleteServers(targets) { deleted ->
                                if (deleted > 0) {
                                    Toast.makeText(
                                        context,
                                        strings.localized("Удалено серверов: $deleted", "Deleted servers: $deleted"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            exitPersonalSelectionMode()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentCrimson)
                    ) {
                        Text(
                            text = "${strings.delete} ($count)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDeleteConfirm = false }) {
                        Text(strings.cancel, color = colors.textSecondary)
                    }
                }
            )
        }

        // Add Config Dialog (Manual / QR)
        if (showAddDialog) {
            var inputLink by remember { mutableStateOf("") }
            val scaninputLink = com.danieldk.splatevpn.ui.components.rememberQrImporter { inputLink = it }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                containerColor = colors.surface,
                title = { Text(strings.addServerTitle, color = colors.textPrimary) },
                text = {
                    Column {
                        Text(strings.addServerPrompt, fontSize = 12.sp, color = colors.textSecondary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = inputLink,
                                onValueChange = { inputLink = it },
                                placeholder = { Text(strings.uiLinkHint, fontSize = 11.sp, color = colors.textSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.accentCyan,
                                    unfocusedBorderColor = colors.borderSubtle,
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    scaninputLink()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = strings.scanQr, tint = colors.accentCyan)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.addServersFromLinks(inputLink) { count ->
                                if (count > 0) {
                                    val msg = if (count == 1) strings.serverAddedSuccess else String.format(strings.serversAddedCount, count)
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    showAddDialog = false
                                } else {
                                    Toast.makeText(context, strings.invalidLinkFormat, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentCyan)
                    ) {
                        Text(strings.add, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text(strings.cancel, color = colors.textSecondary)
                    }
                }
            )
        }

        // Add Subscription Dialog / Bottom Sheet
        if (showAddSubDialog) {
            var newSubUrl by remember { mutableStateOf("") }
            var userAgent by remember(newSubUrl) { mutableStateOf(viewModel.getSubscriptionUserAgent(newSubUrl)) }
            val hwidEligible = remember(newSubUrl) { viewModel.canSendSubscriptionHwid(newSubUrl) }
            var sendHwid by remember(newSubUrl) { mutableStateOf(viewModel.isSubscriptionHwidEnabled(newSubUrl)) }
            val scannewSubUrl = com.danieldk.splatevpn.ui.components.rememberQrImporter { newSubUrl = it }
            var newSubName by remember { mutableStateOf("") }
            var isOperating by remember { mutableStateOf(false) }
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = { if (!isOperating) showAddSubDialog = false },
                sheetState = sheetState,
                containerColor = colors.background,
                dragHandle = null
            ) {
                com.danieldk.splatevpn.ui.components.rememberAndApplyPopupWindowBlur(blurBehind = true, backgroundBlur = false)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.borderMedium)
                            .align(Alignment.CenterHorizontally)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = strings.addNewSubHeader,
                                color = colors.textPrimary,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = strings.uiSubscriptionHint,
                                color = colors.textSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .liquidGlass(cornerRadius = 10, innerSheen = true)
                                .tactileClickable(targetScale = 0.88f) { showAddSubDialog = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .liquidGlass(cornerRadius = 18, innerSheen = true)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = newSubName,
                            onValueChange = { newSubName = it },
                            placeholder = { Text(strings.subNamePlaceholder, fontSize = 11.sp, color = colors.textSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentAmber,
                                unfocusedBorderColor = colors.borderSubtle,
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary
                            ),
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(16.dp))
                            }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newSubUrl,
                                onValueChange = { newSubUrl = it },
                                placeholder = { Text(strings.subUrlPlaceholder, fontSize = 11.sp, color = colors.textSecondary) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = colors.accentAmber,
                                    unfocusedBorderColor = colors.borderSubtle,
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary
                                ),
                                leadingIcon = {
                                    Icon(Icons.Default.Link, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(16.dp))
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    scannewSubUrl()
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = strings.scanQr, tint = colors.accentAmber)
                            }
                        }

                        if (hwidEligible) com.danieldk.splatevpn.ui.components.SubscriptionHwidSetting(
                            sendHwid, { sendHwid = it }, enabled = !isOperating)
                        userAgent?.let {
                            com.danieldk.splatevpn.ui.components.SubscriptionUserAgentSetting(it, { userAgent = it }, !isOperating)
                        }

                        if (isOperating) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accentAmber)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(strings.subLoading, fontSize = 12.sp, color = colors.accentAmber)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .liquidGlass(
                                    cornerRadius = 12,
                                    accentBorder = if (newSubUrl.isNotBlank() && !isOperating) colors.accentAmber else null,
                                    innerSheen = true
                                )
                                .tactileClickable(enabled = newSubUrl.isNotBlank() && !isOperating) {
                                    if (newSubUrl.isNotBlank()) {
                                        isOperating = true
                                        viewModel.importSubscription(newSubUrl, newSubName.takeIf { it.isNotBlank() }, sendHwid.takeIf { hwidEligible }, userAgent) { result ->
                                            isOperating = false
                                            result.onSuccess { count ->
                                                Toast.makeText(context, String.format(strings.subAddedSuccess, count), Toast.LENGTH_SHORT).show()
                                                showAddSubDialog = false
                                                newSubUrl = ""
                                                newSubName = ""
                                            }.onFailure { err ->
                                                Toast.makeText(context, String.format(strings.errorPrefix, err.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = if (newSubUrl.isNotBlank() && !isOperating) colors.accentAmber else colors.textTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = strings.importAction,
                                color = if (newSubUrl.isNotBlank() && !isOperating) colors.accentAmber else colors.textTertiary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        editingSubscription?.let { editing ->
            val sub = subscriptions.firstOrNull { it.id == editing.id } ?: editing
            val history by remember(sub.id) { viewModel.subscriptionUpdateHistory(sub.id) }.collectAsState(initial = emptyList())
            SubscriptionDetailsDialog(
                sub = sub,
                autoUpdateInterval = autoUpdateInterval,
                onDismiss = { editingSubscription = null },
                onSave = { name, url, callback -> viewModel.editSubscription(sub.id, name, url, callback) },
                history = history,
                onRetention = { enabled, callback -> viewModel.setSubscriptionRetention(sub.id, enabled, callback) },
                onRules = { enabled, expected, callback -> viewModel.setSubscriptionRulesEnabled(sub.id, enabled, expected, callback) },
                onPurge = { callback -> viewModel.purgeRetainedNodes(sub.id, callback) },
                hwidEnabled = { url -> viewModel.isSubscriptionHwidEnabled(url).takeIf { viewModel.canSendSubscriptionHwid(url) } },
                onHwidChange = viewModel::setSubscriptionHwidEnabled,
                getUserAgent = viewModel::getSubscriptionUserAgent,
                onUserAgentChange = viewModel::setSubscriptionUserAgent
            )
        }
        subscriptionUpdateReport?.let { report ->
            AlertDialog(
                onDismissRequest = { subscriptionUpdateReport = null },
                containerColor = colors.surface,
                title = { Text(strings.subscriptionUpdateAll) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(if (report.updated == 0 && report.failures.isEmpty()) strings.subscriptionNoUpdates
                            else String.format(strings.subscriptionRefreshSummary, report.updated, report.failures.size))
                        report.failures.forEach { (name, error) ->
                            Text("$name: ${com.danieldk.splatevpn.ui.subscriptionErrorText(com.danieldk.splatevpn.ui.theme.LocalAppResources.current, error)}", color = colors.accentCrimson, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { subscriptionUpdateReport = null }) { Text(strings.close) } }
            )
        }

        // Delete Subscription Confirmation Dialog
        if (deletingSub != null) {
            val targetSub = deletingSub!!
            AlertDialog(
                onDismissRequest = { deletingSub = null },
                containerColor = colors.surface,
                title = { Text(strings.subscriptionDeleteTitle, color = colors.textPrimary) },
                text = {
                    Text(
                        text = String.format(strings.subscriptionDeleteMessage, targetSub.name, targetSub.nodeCount),
                        color = colors.textSecondary,
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.deleteSubscription(targetSub.id)
                            if (selectedFilterSubId == targetSub.id) closeFolder()
                            deletingSub = null
                            Toast.makeText(context, strings.subDeletedSuccess, Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentCrimson)
                    ) {
                        Text(strings.delete, color = contentColorOn(colors.accentCrimson), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletingSub = null }) {
                        Text(strings.cancel, color = colors.textSecondary)
                    }
                }
            )
        }

        // Edit Server Dialog
        if (editingServer != null) {
            EditServerDialog(
                server = editingServer!!,
                onDismiss = { editingServer = null },
                onSave = { updated, onResult ->
                    viewModel.updateServer(updated) { result ->
                        result.onSuccess { Toast.makeText(context, strings.serverUpdatedSuccess, Toast.LENGTH_SHORT).show() }
                        onResult(result)
                    }
                }
            )
        }

        // Server Share & QR Export Dialog
        if (sharingServer != null) {
            ServerShareDialog(
                server = sharingServer!!,
                onDismiss = { sharingServer = null }
            )
        }

        // Subscription Share & QR Export Dialog
        if (sharingSub != null) {
            SubscriptionShareDialog(
                sub = sharingSub!!,
                onDismiss = { sharingSub = null }
            )
        }

        // Unified Add Config / Subscription Bottom Sheet
        if (showAddUnifiedSheet) {
            AddConfigBottomSheet(
                viewModel = viewModel,
                initialText = initialImportPayload,
                onDismissRequest = {
                    showAddUnifiedSheet = false
                    initialImportPayload = ""
                }
            )
        }


    }
}


@Composable
private fun ServerToolAction(
    label: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    action: Boolean = false,
    enabled: Boolean = true,
    rotation: Float = 0f,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val highlighted = selected || action
    val foreground = when {
        !enabled && rotation == 0f -> colors.textTertiary
        highlighted || rotation != 0f -> accent
        else -> colors.textSecondary
    }
    Column(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) accent.copy(alpha = if (action) 0.13f else 0.16f) else Color.Transparent)
            .semantics { contentDescription = description; semanticsSelected = selected }
            .tactileClickable(enabled = enabled, targetScale = 0.94f, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Icon(icon, null, Modifier.size(17.dp).graphicsLayer { rotationZ = rotation }, tint = foreground)
        Text(label, color = foreground, fontSize = 10.5.sp, lineHeight = 13.sp,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ServerCategoryTabs(
    mode: NodeViewMode,
    nodeCount: Int,
    enabledSubscriptionCount: Int,
    subscriptionCount: Int,
    personalCount: Int,
    onSelect: (NodeViewMode) -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val labels = listOf(strings.subscriptionNodesLabel, strings.subscriptionsLabel, strings.personalNodesLabel)
    val labelStyle = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Account for the strip's outer padding and the two gaps between segments.
        val slotWidth = ((maxWidth - 16.dp) / 3).coerceAtLeast(1.dp)
        val widestLabel = labels.maxOf { textMeasurer.measure(it, style = labelStyle,
            softWrap = false, maxLines = 1).size.width }
        val showIcons = with(density) { widestLabel.toDp() + 36.dp <= slotWidth }
        val textWidth = (slotWidth - 12.dp - if (showIcons) 24.dp else 0.dp).coerceAtLeast(1.dp)
        // Leave a rounding guard for the Row's integer-pixel weight distribution.
        val textWidthPx = with(density) { (textWidth.roundToPx() - 2).coerceAtLeast(1) }
        val titleHeight = with(density) {
            labels.maxOf { textMeasurer.measure(it, style = labelStyle, maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = textWidthPx)).size.height }.toDp()
        }
        val requiredHeight = titleHeight + with(density) { 12.sp.toDp() } + 10.dp
        SlidingTabStrip(
            selectedIndex = when (mode) { NodeViewMode.SUBSCRIPTIONS -> 1; NodeViewMode.PERSONAL -> 2; else -> 0 },
            accents = listOf(colors.accentCyan, colors.accentAmber, colors.accentEmerald),
            onSelect = { onSelect(when (it) { 1 -> NodeViewMode.SUBSCRIPTIONS; 2 -> NodeViewMode.PERSONAL; else -> NodeViewMode.ALL }) },
            modifier = Modifier.testTag("serverCategoryTabs"),
            tabHeight = maxOf(48.dp * maxOf(1f, density.fontScale), requiredHeight)
        ) { highlighted ->
            ServerCategorySegment(labels[0], nodeCount.toString(), Icons.Default.Dns, showIcons, titleHeight, labelStyle,
                highlighted == 0, colors.accentCyan, Modifier.weight(1f).testTag("serverCategory:ALL")) { onSelect(NodeViewMode.ALL) }
            ServerCategorySegment(labels[1], "$enabledSubscriptionCount/$subscriptionCount", Icons.Default.CloudDownload, showIcons, titleHeight, labelStyle,
                highlighted == 1, colors.accentAmber, Modifier.weight(1f).testTag("serverCategory:SUBSCRIPTIONS")) { onSelect(NodeViewMode.SUBSCRIPTIONS) }
            ServerCategorySegment(labels[2], personalCount.toString(), Icons.Default.Lock, showIcons, titleHeight, labelStyle,
                highlighted == 2, colors.accentEmerald, Modifier.weight(1f).testTag("serverCategory:PERSONAL")) { onSelect(NodeViewMode.PERSONAL) }
        }
    }
}

@Composable
private fun ServerCategorySegment(
    label: String,
    count: String,
    icon: ImageVector,
    showIcon: Boolean,
    titleHeight: Dp,
    labelStyle: TextStyle,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    Column(
        modifier = modifier.fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .semantics { semanticsSelected = selected; contentDescription = "$label, $count" }
            .tactileClickable(onClick = onClick).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.testTag("serverCategoryContent:$label"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (showIcon) {
                Icon(icon, contentDescription = null, tint = if (selected) accent else colors.textSecondary,
                    modifier = Modifier.size(18.dp).testTag("serverCategoryIcon:$label"))
            }
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.height(titleHeight), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) accent else colors.textSecondary,
                        style = labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(2.dp))
                Text(count, color = if (selected) accent else colors.textSecondary,
                    fontSize = 10.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * Filter Pill Chip for the horizontal filter carousel in ALL mode
 */
@Composable
private fun FilterPillChip(
    selected: Boolean,
    label: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current

    Box(
        modifier = Modifier
            .liquidGlass(
                cornerRadius = 10,
                accentBorder = if (selected) accentColor else null,
                innerSheen = true
            )
            .background(
                if (selected) accentColor.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(10.dp)
            )
            .tactileClickable(targetScale = 0.92f) { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accentColor else colors.textTertiary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = if (selected) accentColor else colors.textSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

/**
 * Active Subscription Banner when user filters by a specific subscription in ALL mode
 */
@Composable
private fun ActiveSubscriptionBanner(
    sub: SubscriptionEntity,
    isUpdating: Boolean,
    isPinging: Boolean = false,
    onToggleEnabled: ((Boolean) -> Unit)? = null,
    onPing: () -> Unit = {},
    onUpdate: () -> Unit,
    onShare: () -> Unit = {},
    onDismiss: () -> Unit,
    onStopPing: () -> Unit = {}
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val resources = LocalAppResources.current
    val bannerAccent = if (sub.enabled) colors.accentAmber else colors.textTertiary
    var menuOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 12,
                elevated = true,
                innerSheen = true,
                accentBorder = bannerAccent.copy(alpha = 0.45f)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(bannerAccent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = bannerAccent, modifier = Modifier.size(15.dp))
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subscriptionDisplayName(sub.id, sub.name, strings),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (sub.enabled) "${String.format(strings.subscriptionNodes, sub.nodeCount)} • ${formatSubUpdated(sub.lastUpdated, strings)}" else strings.uiDisabledNodesHidden,
                color = if (sub.enabled) colors.accentAmber else colors.accentCrimson.copy(alpha = 0.85f),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        IconButton(
            onClick = if (isPinging) onStopPing else onPing,
            enabled = isPinging || sub.enabled,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(if (isPinging) Icons.Default.Stop else Icons.Default.NetworkCheck,
                contentDescription = resources.getString(if (isPinging) com.danieldk.splatevpn.R.string.server_stop_checks else com.danieldk.splatevpn.R.string.server_check_servers),
                tint = if (isPinging || sub.enabled) colors.accentEmerald else colors.textTertiary, modifier = Modifier.size(20.dp))
        }
        IconButton(
            onClick = onUpdate,
            enabled = !isUpdating,
            modifier = Modifier.size(44.dp)
        ) {
            if (isUpdating) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accentAmber)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = strings.refresh, tint = colors.accentAmber, modifier = Modifier.size(20.dp))
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.MoreVert, resources.getString(com.danieldk.splatevpn.R.string.server_folder_options),
                    tint = colors.textSecondary, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, modifier = Modifier.background(colors.surfaceElevated)) {
                if (onToggleEnabled != null) DropdownMenuItem(
                    text = { Text(resources.getString(if (sub.enabled) com.danieldk.splatevpn.R.string.folder_disable else com.danieldk.splatevpn.R.string.folder_enable)) },
                    onClick = { menuOpen = false; onToggleEnabled(!sub.enabled) })
                if (sub.url.isNotBlank() && !isProtectedWtpn(sub.id, sub.url)) DropdownMenuItem(text = { Text(strings.shareSubscriptionTitle) },
                    onClick = { menuOpen = false; onShare() })
                DropdownMenuItem(text = { Text(resources.getString(com.danieldk.splatevpn.R.string.server_back)) },
                    onClick = { menuOpen = false; onDismiss() })
            }
        }
    }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 12,
        backgroundColor = colors.surface.copy(alpha = .97f), innerSheen = false).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SubscriptionUsageSummary(sub)
        com.danieldk.splatevpn.ui.components.SubscriptionPublisherDetails(sub.publisherMetadataJson, compact = true, framed = false)
    }
    }
}

/**
 * Dedicated Card for each Subscription inside the SUBSCRIPTIONS mode Hub
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionHubCard(
    sub: SubscriptionEntity,
    isUpdating: Boolean,
    isPinging: Boolean = false,
    onToggleEnabled: (Boolean) -> Unit = {},
    onToggleAutoUpdate: (Boolean) -> Unit = {},
    onViewNodes: () -> Unit,
    onPing: () -> Unit,
    onUpdate: () -> Unit,
    onShare: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onStopPing: () -> Unit = {}
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val isPreset = sub.id in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS
    val isEnabled = sub.enabled
    val baseAccent = if (isPreset) colors.accentCyan else colors.accentAmber
    val cardAccent = if (isEnabled) baseAccent else colors.textTertiary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isEnabled) 1f else 0.72f }
            .liquidGlass(
                cornerRadius = 14,
                elevated = isEnabled,
                innerSheen = isEnabled,
                accentBorder = cardAccent.copy(alpha = if (isEnabled) 0.35f else 0.15f)
            )
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .liquidGlass(
                        cornerRadius = 9,
                        accentBorder = cardAccent.copy(alpha = if (isEnabled) 0.5f else 0.2f),
                        innerSheen = isEnabled
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = cardAccent,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f).tvFocusOutline().clickable(enabled = !isUpdating && !isProtectedWtpn(sub.id, sub.url), onClickLabel = strings.edit, onClick = onEdit)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subscriptionDisplayName(sub.id, sub.name, strings),
                        color = if (isEnabled) colors.textPrimary else colors.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isProtectedWtpn(sub.id, sub.url)) Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = colors.textSecondary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                }

                Spacer(modifier = Modifier.height(2.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(cardAccent.copy(alpha = 0.15f))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Text(
                            text = when {
                                !isEnabled -> "${strings.subscriptionDisabled} • ${sub.nodeCount}"
                                isPreset -> "${strings.subscriptionBuiltIn} • ${sub.nodeCount}"
                                else -> String.format(strings.subscriptionNodes, sub.nodeCount)
                            },
                            color = cardAccent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = formatSubUpdated(sub.lastUpdated, strings),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    if (sub.lastAttemptAt > 0) {
                        val ru = strings is com.danieldk.splatevpn.ui.theme.RussianAppStrings
                        val delta = sub.nodeCount - sub.previousNodeCount
                        Text(text = (if (ru) "Попытка: " else "Attempt: ") + formatSubUpdated(sub.lastAttemptAt, strings) +
                            if (sub.lastError.isNotBlank()) " • ${com.danieldk.splatevpn.ui.subscriptionErrorText(com.danieldk.splatevpn.ui.theme.LocalAppResources.current, sub.lastError)}" else when {
                                delta > 0 -> " • " + String.format(strings.localized("Серверов: +%d", "Servers: +%d"), delta)
                                delta < 0 -> " • " + String.format(strings.localized("Серверов: %d", "Servers: %d"), delta)
                                else -> " • " + strings.localized("Без изменений", "No changes")
                            },
                            color = if (sub.lastError.isNotBlank()) colors.accentAmber else colors.textSecondary,
                            fontSize = 10.sp)
                    }

                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Inline enable/disable toggle
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.accentCyan,
                    checkedTrackColor = colors.accentCyan.copy(alpha = 0.35f),
                    uncheckedThumbColor = colors.textTertiary,
                    uncheckedTrackColor = colors.surface.copy(alpha = 0.5f)
                ),
                modifier = Modifier.scale(0.70f)
            )


        }

        com.danieldk.splatevpn.ui.components.SubscriptionPublisherDetails(sub.publisherMetadataJson)

        // Quota & Expiration Section (Subscription-Userinfo)
        val usedBytes = sub.uploadBytes + sub.downloadBytes
        val hasQuota = sub.totalBytes > 0L
        val hasExpire = sub.expireTimestamp > 0L

        if (hasQuota || hasExpire) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 8, innerSheen = false)
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                if (hasQuota) {
                    val progress = (usedBytes.toFloat() / sub.totalBytes.toFloat()).coerceIn(0f, 1f)
                    val remainingBytes = (sub.totalBytes - usedBytes).coerceAtLeast(0L)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = String.format(strings.subQuotaRemaining, formatBytes(remainingBytes), formatBytes(sub.totalBytes)),
                            color = colors.textPrimary,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            color = if (progress > 0.9f) colors.accentCrimson else colors.accentAmber,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = if (progress > 0.9f) colors.accentCrimson else colors.accentAmber,
                        trackColor = colors.surface.copy(alpha = 0.5f)
                    )
                }

                if (hasExpire) {
                    if (hasQuota) Spacer(modifier = Modifier.height(3.dp))
                    val isExpired = System.currentTimeMillis() / 1000 > sub.expireTimestamp
                    val expireDateStr = remember(sub.expireTimestamp) {
                        val sdf = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                        sdf.format(Date(sub.expireTimestamp * 1000))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Event,
                                contentDescription = null,
                                tint = if (isExpired) colors.accentCrimson else colors.textTertiary,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (isExpired) strings.subExpired else String.format(strings.subExpireDate, expireDateStr),
                                color = if (isExpired) colors.accentCrimson else colors.textSecondary,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Main actions form a stable row; secondary actions share a compact footer.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubscriptionActionButton(
                label = String.format(strings.subscriptionNodes, sub.nodeCount),
                icon = Icons.Default.Visibility, accent = colors.accentCyan,
                modifier = Modifier.weight(1f), onClick = onViewNodes
            )
            SubscriptionActionButton(
                label = if (isPinging) LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.server_stop_checks_short) else strings.ping,
                icon = if (isPinging) Icons.Default.Stop else Icons.Default.NetworkCheck, accent = colors.accentEmerald,
                modifier = Modifier.weight(1f), enabled = isPinging || isEnabled,
                onClick = if (isPinging) onStopPing else onPing
            )
            SubscriptionActionButton(
                label = strings.refresh, icon = Icons.Default.Refresh, accent = colors.accentAmber,
                modifier = Modifier.weight(1f), busy = isUpdating, onClick = onUpdate
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubscriptionActionButton(
                label = if (sub.autoUpdate) strings.subscriptionAutoUpdate else strings.subscriptionManual,
                icon = if (sub.autoUpdate) Icons.Default.CheckCircle else Icons.Default.SyncDisabled,
                accent = if (sub.autoUpdate) colors.accentCyan else colors.textSecondary,
                modifier = Modifier.weight(1f), onClick = { onToggleAutoUpdate(!sub.autoUpdate) }
            )
            if (sub.url.isNotBlank() && !isProtectedWtpn(sub.id, sub.url)) {
                SubscriptionIconAction(Icons.Default.QrCode2, "QR", colors.accentAmber, onClick = onShare)
            }
            SubscriptionIconAction(Icons.Default.DeleteOutline, strings.delete, colors.accentCrimson,
                enabled = !isUpdating, onClick = onDelete)
        }
    }
}

@Composable
private fun SubscriptionActionButton(
    label: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val foreground = if (enabled) accent else LocalAppThemeColors.current.textTertiary
    Box(
        modifier = modifier.heightIn(min = 48.dp).tactileClickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.fillMaxWidth()
                .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = accent.copy(alpha = 0.35f))
                .heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(13.dp), color = accent, strokeWidth = 1.5.dp)
            else Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = foreground, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SubscriptionIconAction(
    icon: ImageVector,
    description: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        Modifier.size(48.dp).tactileClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(32.dp)
                .liquidGlass(cornerRadius = 8, innerSheen = true, accentBorder = accent.copy(alpha = 0.3f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = accent, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Unified Compact Search Bar Field with optional inline quick action buttons
 */
@Composable
private fun SearchBarField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    colors: AppThemeColors,
    onPasteClick: (() -> Unit)? = null,
    onQrClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(cornerRadius = 14, innerSheen = true)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontFamily = FontFamily.Monospace
            ),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp),
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = colors.textSecondary,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                innerTextField()
            }
        )
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            if (onPasteClick != null) {
                IconButton(
                    onClick = onPasteClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste Link",
                        tint = colors.accentCyan,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            if (onQrClick != null) {
                IconButton(
                    onClick = onQrClick,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = "Scan QR / Add",
                        tint = colors.accentAmber,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

internal data class ServerGroup(
    val id: String,
    val title: String,
    val isPreset: Boolean,
    val isPersonal: Boolean,
    val subId: String?,
    val servers: List<ServerNode>
)

@Composable
private fun SubscriptionUsageSummary(sub: SubscriptionEntity) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val used = sub.uploadBytes.coerceAtLeast(0).let { upload ->
        val download = sub.downloadBytes.coerceAtLeast(0)
        if (Long.MAX_VALUE - upload < download) Long.MAX_VALUE else upload + download
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (sub.totalBytes > 0) {
            val fraction = (used.toDouble() / sub.totalBytes).coerceIn(0.0, 1.0).toFloat()
            Text(String.format(strings.subQuotaRemaining, formatBytes((sub.totalBytes - used).coerceAtLeast(0)), formatBytes(sub.totalBytes)),
                color = colors.textSecondary, fontSize = 12.sp)
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
                color = if (fraction >= .9f) colors.accentCrimson else colors.accentAmber)
        }
        if (sub.expireTimestamp > 0) {
            val expired = System.currentTimeMillis() / 1000 >= sub.expireTimestamp
            val date = remember(sub.expireTimestamp) { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(sub.expireTimestamp * 1000)) }
            Text(if (expired) strings.subExpired else String.format(strings.subExpireDate, date),
                color = if (expired) colors.accentCrimson else colors.textSecondary, fontSize = 12.sp)
        }
        if (sub.totalBytes <= 0 && sub.expireTimestamp <= 0) {
            Text(LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.node_limits_unknown), color = colors.textSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PersonalBatchDeleteBar(
    selectedCount: Int,
    visibleCount: Int,
    allVisibleSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 14,
                accentBorder = colors.accentCrimson.copy(alpha = 0.5f),
                innerSheen = true
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("personalSelectionActionBar"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Checklist,
                    contentDescription = null,
                    tint = colors.accentCrimson,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = strings.localized(
                        "Выбрано: $selectedCount из $visibleCount",
                        "Selected: $selectedCount of $visibleCount"
                    ),
                    color = colors.textPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(32.dp).testTag("personalSelectionCancel")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = strings.cancel,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .liquidGlass(cornerRadius = 10, innerSheen = true)
                    .tvFocusOutline()
                    .clickable(onClick = onToggleSelectAll)
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .testTag("personalSelectionToggleAll"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (allVisibleSelected) {
                        strings.localized("Снять все", "Clear all")
                    } else {
                        strings.localized("Выбрать все", "Select all")
                    },
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onDeleteSelected,
                enabled = selectedCount > 0,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentCrimson),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .testTag("personalSelectionDelete")
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (selectedCount > 0) "${strings.delete} ($selectedCount)" else strings.delete,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun SubscriptionGroupHeader(
    group: ServerGroup, isCollapsed: Boolean, isEnabled: Boolean,
    isPinging: Boolean, pingEnabled: Boolean, onPing: () -> Unit,
    onToggleCollapse: () -> Unit, onToggleEnabled: ((Boolean) -> Unit)? = null,
    subscription: SubscriptionEntity? = null, onDetails: () -> Unit = {},
    onRefresh: () -> Unit = {}, isUpdating: Boolean = false,
    onStopPing: () -> Unit = {},
    onStartBatchDelete: (() -> Unit)? = null,
    inGroupFrame: Boolean = false
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val resources = LocalAppResources.current
    val animationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val accent = if (!isEnabled) colors.textTertiary else if (group.isPersonal) colors.accentEmerald else if (group.isPreset) colors.accentCyan else colors.accentAmber
    val containerModifier = if (inGroupFrame) {
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    } else {
        Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14, innerSheen = true).padding(horizontal = 12.dp, vertical = 6.dp)
    }
    Column(containerModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (group.isPersonal) Icons.Default.Lock else Icons.Default.CloudDownload, null,
                tint = accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            val chevronRotation by animateFloatAsState(
                targetValue = if (isCollapsed) 0f else 180f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "folderChevron_${group.id}"
            )
            Row(Modifier.weight(1f).heightIn(min = 48.dp).tvFocusOutline()
                .clickable(
                    onClickLabel = if (isCollapsed) strings.uiExpand else strings.uiCollapse,
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onToggleCollapse()
                    }
                )
                .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.title, color = if (isEnabled) colors.textPrimary else colors.textSecondary,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(if (isEnabled) String.format(strings.subscriptionNodes, group.servers.size) else strings.subscriptionDisabled,
                        color = colors.textTertiary, fontSize = 11.sp)
                }
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isCollapsed) strings.uiExpand else strings.uiCollapse,
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            rotationZ = if (animationsEnabled) chevronRotation else if (isCollapsed) 0f else 180f
                        }
                        .testTag("folderExpand:${group.id}")
                )
            }
            IconButton(onClick = if (isPinging) onStopPing else onPing,
                enabled = isPinging || (pingEnabled && isEnabled),
                modifier = Modifier.size(48.dp).testTag("folderPing:${group.id}")) {
                Icon(if (isPinging) Icons.Default.Stop else Icons.Default.NetworkCheck,
                    resources.getString(if (isPinging) com.danieldk.splatevpn.R.string.server_stop_checks else com.danieldk.splatevpn.R.string.server_check_servers),
                    tint = if (isPinging || (pingEnabled && isEnabled)) colors.accentEmerald else colors.textTertiary,
                    modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).semantics {
                    contentDescription = resources.getString(com.danieldk.splatevpn.R.string.server_folder_options)
                }) {
                    if (isUpdating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = accent)
                    else Icon(Icons.Default.MoreVert, contentDescription = null,
                        tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.background(colors.surfaceElevated)) {
                    if (subscription != null) {
                        DropdownMenuItem(text = { Text(strings.refresh) }, enabled = !isUpdating,
                            onClick = { menu = false; onRefresh() })
                        DropdownMenuItem(text = { Text(strings.edit) }, onClick = { menu = false; onDetails() })
                    }
                    if (onStartBatchDelete != null) DropdownMenuItem(
                        text = { Text(strings.localized("Выбрать для удаления", "Select to delete")) },
                        onClick = { menu = false; onStartBatchDelete() })
                    if (onToggleEnabled != null) DropdownMenuItem(
                        text = { Text(resources.getString(if (isEnabled) com.danieldk.splatevpn.R.string.folder_disable else com.danieldk.splatevpn.R.string.folder_enable)) },
                        onClick = { menu = false; onToggleEnabled(!isEnabled) })
                }
            }
        }
        subscription?.let { sub ->
            val parts = buildList {
                if (sub.totalBytes > 0) {
                    val remaining = (sub.totalBytes.toDouble() - sub.uploadBytes.coerceAtLeast(0) - sub.downloadBytes.coerceAtLeast(0)).coerceAtLeast(0.0).toLong()
                    add(String.format(strings.subQuotaRemaining, formatBytes(remaining), formatBytes(sub.totalBytes)))
                }
                if (sub.expireTimestamp > 0) add(if (System.currentTimeMillis()/1000 >= sub.expireTimestamp) strings.subExpired else
                    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(sub.expireTimestamp * 1000)))
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = colors.textSecondary, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 4.dp))
            AnimatedVisibility(
                visible = !isCollapsed,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(tween(if (animationsEnabled) 200 else 0)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(tween(if (animationsEnabled) 140 else 0)),
                label = "folderPublisher_${group.id}"
            ) {
                com.danieldk.splatevpn.ui.components.SubscriptionPublisherDetails(sub.publisherMetadataJson, compact = true)
            }
        }
    }
}

/** One coherent result prevents filters from seeing partitions from an older server list. */
private data class ServerBrowserSnapshot(
    val presets: List<ServerNode> = emptyList(), val personal: List<ServerNode> = emptyList(),
    val subscriptions: List<ServerNode> = emptyList(), val activeCount: Int = 0,
    val visible: List<ServerNode> = emptyList()
)
