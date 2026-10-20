package com.danieldk.splatevpn.ui.screens

import android.widget.Toast
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected as semanticsSelected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val colors = LocalAppThemeColors.current

    val savedListPreferences = remember { viewModel.getServersListPreferences() }
    var activeViewMode by rememberSaveable {
        mutableStateOf(NodeViewMode.entries.firstOrNull { it.name == savedListPreferences.viewMode } ?: NodeViewMode.ALL)
    }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // null = show all; "custom" = only personal; or subId = specific subscription
    var selectedFilterSubId by rememberSaveable { mutableStateOf(savedListPreferences.filterSubId) }

    var showAddDialog by remember { mutableStateOf(false) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var showAddUnifiedSheet by remember { mutableStateOf(false) }
    var initialImportPayload by remember { mutableStateOf("") }
    var editingServer by remember { mutableStateOf<ServerNode?>(null) }
    var editingSubscription by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var subscriptionUpdateReport by remember { mutableStateOf<com.danieldk.splatevpn.data.SubscriptionUpdateReport?>(null) }
    val autoUpdateInterval by viewModel.autoUpdateInterval.collectAsState()
    var deletingSub by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var updatingSubIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pingingSubIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isUpdatingAllSubs by remember { mutableStateOf(false) }
    var sharingServer by remember { mutableStateOf<ServerNode?>(null) }
    var sharingSub by remember { mutableStateOf<SubscriptionEntity?>(null) }
    var isGroupedBySub by rememberSaveable { mutableStateOf(savedListPreferences.grouped) }
    var expandedSubIds by rememberSaveable { mutableStateOf(savedListPreferences.expandedGroupIds) }

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
    }
    val filteredSubscriptions = remember(realSubscriptions, searchQuery, strings) {
        realSubscriptions.filter { sub ->
            com.danieldk.splatevpn.data.filterSubscriptions(listOf(sub), searchQuery).isNotEmpty() ||
                subscriptionDisplayName(sub.id, sub.name, strings).contains(searchQuery.trim(), ignoreCase = true)
        }
    }
    val disabledSubIds = remember(subscriptions) {
        subscriptions.filter { !it.enabled }.map { it.id }.toSet()
    }
    val activeServersCount = remember(servers, disabledSubIds) {
        servers.count { it.subId !in disabledSubIds }
    }
    // High-performance single-pass partitioning of server nodes (O(N) instead of 3x full list traversals)
    val (presetServers, personalServers, subscriptionServers) = remember(servers) {
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
        Triple(preset, personal, subs)
    }

    // Active sub entity for the banner
    val activeSubEntity = remember(realSubscriptions, selectedFilterSubId) {
        if (selectedFilterSubId != null && selectedFilterSubId != "custom") {
            realSubscriptions.firstOrNull { it.id == selectedFilterSubId }
        } else null
    }

    val filteredServers = remember(servers, activeViewMode, selectedFilterSubId, isSortByPing, searchQuery, disabledSubIds, isGroupedBySub) {
        val baseList = when (activeViewMode) {
            NodeViewMode.ALL -> {
                when (selectedFilterSubId) {
                    null -> if (isGroupedBySub) servers else servers.filter { it.subId !in disabledSubIds }
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

        val searched = if (searchQuery.isBlank()) {
            baseList
        } else {
            val q = searchQuery.trim()
            baseList.filter {
                it.name.contains(q, ignoreCase = true) ||
                        it.address.contains(q, ignoreCase = true) ||
                        it.protocol.contains(q, ignoreCase = true) ||
                        it.subName.contains(q, ignoreCase = true)
            }
        }

        if (isSortByPing) {
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
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .padding(top = 20.dp, start = 16.dp, end = 16.dp)
        ) {
            PageHeader(
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
                        val refreshSubRotation = if (isUpdatingAllSubs) {
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
                    val refreshRotation = if (isPinging) {
                        val transition = rememberInfiniteTransition(label = "toolbarPing")
                        val rotation by transition.animateFloat(0f, 360f,
                            infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "toolbarPingRotation")
                        rotation
                    } else 0f
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
                            label = strings.serverToolsTest, description = strings.pingAll,
                            icon = Icons.Default.Refresh, accent = colors.accentCyan,
                            action = true, enabled = !isPinging, rotation = refreshRotation, modifier = Modifier.weight(1f)
                        ) {
                            if (activeViewMode == NodeViewMode.ALL && selectedFilterSubId == null) viewModel.testAllPings()
                            else viewModel.testServersPing(filteredServers) { count ->
                                Toast.makeText(context, strings.uiPingSummary.format(if (isRealDelay) "Real Delay" else "TCP Ping", count), Toast.LENGTH_SHORT).show()
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

            // The capsule follows a horizontal drag just like the bottom dock.
            SlidingTabStrip(
                selectedIndex = when (activeViewMode) {
                    NodeViewMode.SUBSCRIPTIONS -> 1
                    NodeViewMode.PERSONAL -> 2
                    else -> 0
                },
                accents = listOf(colors.accentCyan, colors.accentAmber, colors.accentEmerald),
                onSelect = { index ->
                    activeViewMode = when (index) {
                        1 -> NodeViewMode.SUBSCRIPTIONS
                        2 -> NodeViewMode.PERSONAL
                        else -> NodeViewMode.ALL
                    }
                    selectedFilterSubId = null
                }
            ) { highlighted ->
                ServerCategorySegment(
                    label = strings.subscriptionNodesLabel,
                    count = activeServersCount.toString(),
                    icon = Icons.Default.Dns,
                    selected = highlighted == 0,
                    accent = colors.accentCyan,
                    modifier = Modifier.weight(1f)
                ) {
                    activeViewMode = NodeViewMode.ALL
                    selectedFilterSubId = null
                }
                ServerCategorySegment(
                    label = strings.subscriptionsLabel,
                    count = "${realSubscriptions.count { it.enabled }}/${realSubscriptions.size}",
                    icon = Icons.Default.CloudDownload,
                    selected = highlighted == 1,
                    accent = colors.accentAmber,
                    modifier = Modifier.weight(1f)
                ) {
                    activeViewMode = NodeViewMode.SUBSCRIPTIONS
                    selectedFilterSubId = null
                }
                ServerCategorySegment(
                    label = strings.personalNodesLabel,
                    count = personalServers.size.toString(),
                    icon = Icons.Default.Lock,
                    selected = highlighted == 2,
                    accent = colors.accentEmerald,
                    modifier = Modifier.weight(1f)
                ) {
                    activeViewMode = NodeViewMode.PERSONAL
                    selectedFilterSubId = null
                }
            }

            // Secondary Subscription Isolation Strip in ALL Mode (when not grouped)
            if (activeViewMode == NodeViewMode.ALL && realSubscriptions.isNotEmpty() && !isGroupedBySub) {
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
                            .clickable { selectedFilterSubId = null }
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

                    realSubscriptions.forEach { sub ->
                        val isSubSelected = selectedFilterSubId == sub.id
                        val isSubDisabled = !sub.enabled
                        val chipColor = if (sub.id in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS) colors.accentCyan else colors.accentAmber
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    when {
                                        isSubSelected -> chipColor.copy(alpha = 0.2f)
                                        isSubDisabled -> colors.surface.copy(alpha = 0.25f)
                                        else -> colors.surface.copy(alpha = 0.4f)
                                    }
                                )
                                .border(
                                    width = 1.dp,
                                    color = when {
                                        isSubSelected -> chipColor
                                        isSubDisabled -> colors.textTertiary.copy(alpha = 0.2f)
                                        else -> colors.borderSubtle.copy(alpha = 0.5f)
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    selectedFilterSubId = if (selectedFilterSubId == sub.id) null else sub.id
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${subscriptionDisplayName(sub.id, sub.name, strings)} (${sub.nodeCount})",
                                color = when {
                                    isSubSelected -> chipColor
                                    isSubDisabled -> colors.textSecondary
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

            // Content Area depending on Active View Mode
            when (activeViewMode) {
                NodeViewMode.ALL -> {
                    // Active Subscription Banner (when filtered to a specific subscription)
                    if (activeSubEntity != null) {
                        Spacer(modifier = Modifier.height(5.dp))
                        ActiveSubscriptionBanner(
                            sub = activeSubEntity,
                            isUpdating = isUpdatingAllSubs || activeSubEntity.id in updatingSubIds,
                            isPinging = activeSubEntity.id in pingingSubIds,
                            onToggleEnabled = { enabled ->
                                viewModel.toggleSubscriptionEnabled(activeSubEntity.id, enabled)
                            },
                            onPing = {
                                pingingSubIds = pingingSubIds + activeSubEntity.id
                                viewModel.testSubscriptionPings(activeSubEntity.id) { count ->
                                    pingingSubIds = pingingSubIds - activeSubEntity.id
                                    val modeDesc = if (isRealDelay) "Real Delay" else "TCP Ping"
                                    Toast.makeText(context, String.format(strings.uiPingSummary, subscriptionDisplayName(activeSubEntity.id, activeSubEntity.name, strings), count), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onUpdate = {
                                updatingSubIds = updatingSubIds + activeSubEntity.id
                                viewModel.updateSubscription(activeSubEntity.id) { res ->
                                    updatingSubIds = updatingSubIds - activeSubEntity.id
                                    res.onSuccess { count ->
                                        Toast.makeText(context, String.format(strings.subUpdatedSuccess, count), Toast.LENGTH_SHORT).show()
                                    }.onFailure { err ->
                                        Toast.makeText(context, String.format(strings.errorPrefix, err.message ?: ""), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onShare = { sharingSub = activeSubEntity },
                            onDismiss = { selectedFilterSubId = null }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Servers List
                    ServerListSection(
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
                        onToggleSubEnabled = { subId, enabled ->
                            viewModel.toggleSubscriptionEnabled(subId, enabled)
                            val subName = subscriptions.firstOrNull { it.id == subId }?.name ?: ""
                            val msg = String.format(if (enabled) strings.subscriptionEnabledMessage else strings.subscriptionDisabledMessage, subName)
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        onSelectServer = { viewModel.selectServer(it) },
                        onEditServer = { editingServer = it },
                        onDeleteServer = { viewModel.deleteServer(it) },
                        onShareServer = { sharingServer = it },
                        viewModel = viewModel,
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
                                .padding(top = 32.dp, bottom = 90.dp),
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
                                        imageVector = Icons.Default.Bolt,
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
                                                Toast.makeText(context, "Восстановлено $count серверов", Toast.LENGTH_SHORT).show()
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
                        if (activeSubEntity != null) {
                            Spacer(modifier = Modifier.height(5.dp))
                            ActiveSubscriptionBanner(
                                sub = activeSubEntity,
                                isUpdating = isUpdatingAllSubs || activeSubEntity.id in updatingSubIds,
                                isPinging = activeSubEntity.id in pingingSubIds,
                                onToggleEnabled = { enabled ->
                                    viewModel.toggleSubscriptionEnabled(activeSubEntity.id, enabled)
                                },
                                onPing = {
                                    pingingSubIds = pingingSubIds + activeSubEntity.id
                                    viewModel.testSubscriptionPings(activeSubEntity.id) { count ->
                                        pingingSubIds = pingingSubIds - activeSubEntity.id
                                        val modeDesc = if (isRealDelay) "Real Delay" else "TCP Ping"
                                        Toast.makeText(context, String.format(strings.uiPingSummary, subscriptionDisplayName(activeSubEntity.id, activeSubEntity.name, strings), count), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onUpdate = {
                                    updatingSubIds = updatingSubIds + activeSubEntity.id
                                    viewModel.updateSubscription(activeSubEntity.id) { res ->
                                        updatingSubIds = updatingSubIds - activeSubEntity.id
                                        res.onSuccess { count ->
                                            Toast.makeText(context, String.format(strings.subUpdatedSuccess, count), Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            Toast.makeText(context, String.format(strings.errorPrefix, err.message ?: ""), Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onDismiss = { selectedFilterSubId = null }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        ServerListSection(
                            servers = filteredServers,
                            selectedServer = selectedServer,
                            isAutoBalancerVisible = searchQuery.isBlank() && selectedFilterSubId == null,
                            isPinging = isPinging,
                            onSelectServer = { viewModel.selectServer(it) },
                            onEditServer = { editingServer = it },
                            onDeleteServer = { viewModel.deleteServer(it) },
                            onShareServer = { sharingServer = it },
                            viewModel = viewModel,
                            onAddClick = null
                        )
                    }
                }

                NodeViewMode.SUBSCRIPTIONS -> {
                    Spacer(modifier = Modifier.height(6.dp))

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
                                        Toast.makeText(context, "Восстановлено $count предустановленных узлов", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(15.dp))
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

                    if (realSubscriptions.isEmpty()) {
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
                                    Icon(Icons.Default.Add, contentDescription = null, tint = LocalAppThemeColors.current.onAccent, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.uiAddSubscription, color = LocalAppThemeColors.current.onAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(bottom = 16.dp + LocalBottomDockInset.current)
                        ) {
                            if (filteredSubscriptions.isEmpty()) {
                                item { Text(strings.subscriptionsNoResults, color = colors.textSecondary, modifier = Modifier.padding(24.dp)) }
                            }
                            items(filteredSubscriptions, key = { it.id }) { sub ->
                                SubscriptionHubCard(
                                    sub = sub,
                                    isUpdating = isUpdatingAllSubs || sub.id in updatingSubIds,
                                    isPinging = sub.id in pingingSubIds,
                                    onEdit = { editingSubscription = sub },
                                    onToggleEnabled = { enabled ->
                                        viewModel.toggleSubscriptionEnabled(sub.id, enabled)
                                        val msg = String.format(if (enabled) strings.subscriptionEnabledMessage else strings.subscriptionDisabledMessage, sub.name)
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    },
                                    onToggleAutoUpdate = { auto ->
                                        viewModel.toggleSubscriptionAutoUpdate(sub.id, auto)
                                        val msg = if (auto) strings.subscriptionAutoEnabledMessage else strings.subscriptionAutoDisabledMessage
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                    },
                                    onViewNodes = {
                                        selectedFilterSubId = sub.id
                                        activeViewMode = if (sub.id in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS) {
                                            NodeViewMode.PRESET
                                        } else {
                                            NodeViewMode.ALL
                                        }
                                    },
                                    onPing = {
                                        pingingSubIds = pingingSubIds + sub.id
                                        viewModel.testSubscriptionPings(sub.id) { count ->
                                            pingingSubIds = pingingSubIds - sub.id
                                            val modeDesc = if (isRealDelay) "Real Delay" else "TCP Ping"
                                            Toast.makeText(context, String.format(strings.uiPingSummary, subscriptionDisplayName(sub.id, sub.name, strings), count), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onUpdate = {
                                        updatingSubIds = updatingSubIds + sub.id
                                        viewModel.updateSubscription(sub.id) { res ->
                                            updatingSubIds = updatingSubIds - sub.id
                                            res.onSuccess { count ->
                                                Toast.makeText(context, String.format(strings.subUpdatedSuccess, count), Toast.LENGTH_SHORT).show()
                                            }.onFailure { err ->
                                                Toast.makeText(context, String.format(strings.errorPrefix, err.message ?: ""), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onShare = { sharingSub = sub },
                                    onDelete = { deletingSub = sub }
                                )
                            }
                        }
                    }
                }

                NodeViewMode.PERSONAL -> {
                    Spacer(modifier = Modifier.height(6.dp))

                    if (personalServers.isEmpty()) {
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
                                    Icon(Icons.Default.Add, contentDescription = null, tint = LocalAppThemeColors.current.onAccent, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(strings.uiAddConfig, color = LocalAppThemeColors.current.onAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        ServerListSection(
                            servers = filteredServers,
                            selectedServer = selectedServer,
                            isAutoBalancerVisible = false,
                            isPinging = isPinging,
                            onSelectServer = { viewModel.selectServer(it) },
                            onEditServer = { editingServer = it },
                            onDeleteServer = { viewModel.deleteServer(it) },
                            onShareServer = { sharingServer = it },
                            viewModel = viewModel,
                            onAddClick = {
                                initialImportPayload = ""
                                showAddUnifiedSheet = true
                            }
                        )
                    }
                }
            }
        }

        // Add Config Dialog (Manual / QR)
        if (showAddDialog) {
            var inputLink by remember { mutableStateOf("") }
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
                                    val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                                        .build()
                                    val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
                                    scanner.startScan()
                                        .addOnSuccessListener { barcode ->
                                            val rawValue = barcode.rawValue
                                            if (rawValue != null) inputLink = rawValue
                                        }
                                        .addOnFailureListener { e ->
                                            Toast.makeText(context, String.format(strings.qrScanError, e.message ?: ""), Toast.LENGTH_SHORT).show()
                                        }
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
                        Text(strings.add, color = LocalAppThemeColors.current.onAccent, fontWeight = FontWeight.Bold)
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
            var newSubName by remember { mutableStateOf("") }
            var isOperating by remember { mutableStateOf(false) }
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

            ModalBottomSheet(
                onDismissRequest = { if (!isOperating) showAddSubDialog = false },
                sheetState = sheetState,
                containerColor = colors.background,
                dragHandle = null
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
                                    val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                                        .build()
                                    val scanner = com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
                                    scanner.startScan()
                                        .addOnSuccessListener { barcode ->
                                            val rawValue = barcode.rawValue
                                            if (rawValue != null) newSubUrl = rawValue
                                        }
                                        .addOnFailureListener { e ->
                                            Toast.makeText(context, String.format(strings.qrScanError, e.message ?: ""), Toast.LENGTH_SHORT).show()
                                        }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = strings.scanQr, tint = colors.accentAmber)
                            }
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
                                        viewModel.importSubscription(newSubUrl, newSubName.takeIf { it.isNotBlank() }) { result ->
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

        editingSubscription?.let { sub ->
            SubscriptionDetailsDialog(
                sub = sub,
                autoUpdateInterval = autoUpdateInterval,
                onDismiss = { editingSubscription = null },
                onSave = { name, url, callback -> viewModel.editSubscription(sub.id, name, url, callback) }
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
                            Text("$name: $error", color = colors.accentCrimson, modifier = Modifier.padding(top = 8.dp))
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
                            if (selectedFilterSubId == targetSub.id) selectedFilterSubId = null
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
                onSave = { updated ->
                    viewModel.updateServer(updated)
                    editingServer = null
                    Toast.makeText(context, strings.serverUpdatedSuccess, Toast.LENGTH_SHORT).show()
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
private fun ServerCategorySegment(
    label: String,
    count: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    Column(
        modifier = modifier.height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .tactileClickable(onClick = onClick).padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = if (selected) accent else colors.textTertiary,
                    modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(label, color = if (selected) accent else colors.textSecondary,
                fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(2.dp))
        Text(count, color = if (selected) accent else colors.textSecondary,
            fontSize = 10.sp, lineHeight = 12.sp, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium, maxLines = 1)
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
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val bannerAccent = if (sub.enabled) colors.accentAmber else colors.textTertiary

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
                text = String.format(strings.uiSubscriptionPrefix, subscriptionDisplayName(sub.id, sub.name, strings)),
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (sub.enabled) "${String.format(strings.subscriptionNodes, sub.nodeCount)} • ${formatSubUpdated(sub.lastUpdated, strings)}" else strings.uiDisabledNodesHidden,
                color = if (sub.enabled) colors.accentAmber else colors.accentCrimson.copy(alpha = 0.85f),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        if (onToggleEnabled != null) {
            Switch(
                checked = sub.enabled,
                onCheckedChange = { onToggleEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.accentAmber,
                    uncheckedThumbColor = colors.textTertiary,
                    uncheckedTrackColor = colors.surface.copy(alpha = 0.5f),
                    uncheckedBorderColor = colors.textTertiary.copy(alpha = 0.3f)
                ),
                modifier = Modifier.graphicsLayer { scaleX = 0.68f; scaleY = 0.68f }
            )
            Spacer(modifier = Modifier.width(2.dp))
        }

        // Quick Ping button for this subscription
        IconButton(
            onClick = onPing,
            enabled = !isPinging,
            modifier = Modifier.size(26.dp)
        ) {
            if (isPinging) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.accentEmerald)
            } else {
                Icon(Icons.Default.Bolt, contentDescription = "Ping Subscription", tint = colors.accentEmerald, modifier = Modifier.size(15.dp))
            }
        }

        Spacer(modifier = Modifier.width(2.dp))

        // Quick Update button
        IconButton(
            onClick = onUpdate,
            enabled = !isUpdating,
            modifier = Modifier.size(26.dp)
        ) {
            if (isUpdating) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.accentAmber)
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Update", tint = colors.accentAmber, modifier = Modifier.size(15.dp))
            }
        }

        Spacer(modifier = Modifier.width(2.dp))

        // Quick Share / QR button for subscription
        if (sub.url.isNotBlank()) {
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(Icons.Default.QrCode2, contentDescription = "Share Subscription", tint = bannerAccent, modifier = Modifier.size(15.dp))
            }
            Spacer(modifier = Modifier.width(2.dp))
        }

        // Reset Filter button
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = colors.textTertiary, modifier = Modifier.size(15.dp))
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
    onDelete: () -> Unit
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
                    imageVector = if (isPreset) Icons.Default.Bolt else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = cardAccent,
                    modifier = Modifier.size(17.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f).clickable(enabled = !isUpdating, onClickLabel = strings.edit, onClick = onEdit)) {
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
                    Icon(Icons.Default.Edit, contentDescription = strings.edit, tint = colors.textSecondary, modifier = Modifier.padding(start = 4.dp).size(16.dp))
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
                label = strings.ping, icon = Icons.Default.Bolt, accent = colors.accentEmerald,
                modifier = Modifier.weight(1f), busy = isPinging, onClick = onPing
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
            if (sub.url.isNotBlank()) {
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
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.heightIn(min = 48.dp).tactileClickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            Modifier.fillMaxWidth()
                .liquidGlass(cornerRadius = 8, innerSheen = true, accentBorder = accent.copy(alpha = 0.35f))
                .heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(13.dp), color = accent, strokeWidth = 1.5.dp)
            else Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, color = accent, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private data class ServerGroup(
    val id: String,
    val title: String,
    val isPreset: Boolean,
    val isPersonal: Boolean,
    val subId: String?,
    val servers: List<ServerNode>
)

@Composable
private fun SubscriptionGroupHeader(
    group: ServerGroup,
    isCollapsed: Boolean,
    isEnabled: Boolean,
    isPinging: Boolean,
    pingEnabled: Boolean,
    onPing: () -> Unit,
    onToggleCollapse: () -> Unit,
    onToggleEnabled: ((Boolean) -> Unit)? = null
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val accent = when {
        !isEnabled -> colors.textSecondary
        group.isPersonal -> colors.accentEmerald
        group.isPreset -> colors.accentCyan
        else -> colors.accentAmber
    }
    Row(
        Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14, innerSheen = true,
            accentBorder = accent.copy(alpha = if (isEnabled) 0.35f else 0.15f))
            .tactileClickable(targetScale = 0.98f, onClick = onToggleCollapse)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(32.dp).liquidGlass(cornerRadius = 9,
            accentBorder = accent.copy(alpha = 0.4f), innerSheen = true),
            contentAlignment = Alignment.Center) {
            Icon(when {
                group.isPersonal -> Icons.Default.Lock
                group.isPreset -> Icons.Default.Bolt
                else -> Icons.Default.CloudDownload
            }, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(group.title, color = if (isEnabled) colors.textPrimary else colors.textSecondary,
                fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(if (isEnabled) String.format(strings.subscriptionNodes, group.servers.size)
                else strings.subscriptionDisabled,
                color = colors.textSecondary, fontSize = 10.sp, lineHeight = 13.sp)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(enabled = pingEnabled, onClick = onPing, modifier = Modifier.size(36.dp)) {
            if (isPinging) CircularProgressIndicator(Modifier.size(16.dp), color = colors.accentEmerald, strokeWidth = 2.dp)
            else Icon(Icons.Default.Bolt, contentDescription = strings.ping,
                tint = if (pingEnabled) colors.accentEmerald else colors.textSecondary,
                modifier = Modifier.size(18.dp))
        }
        if (onToggleEnabled != null && group.subId != null) {
            Switch(checked = isEnabled, onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = colors.accentCyan,
                    checkedTrackColor = colors.accentCyan.copy(alpha = 0.35f)),
                modifier = Modifier.scale(0.75f))
        }
        IconButton(onClick = onToggleCollapse, modifier = Modifier.size(28.dp)) {
            Icon(if (isCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                contentDescription = if (isCollapsed) strings.uiExpand else strings.uiCollapse,
                tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Server list section rendering cards with animated placement and optional subscription grouping
 */
@Composable
private fun ServerListSection(
    servers: List<ServerNode>,
    selectedServer: ServerNode?,
    isAutoBalancerVisible: Boolean,
    isPinging: Boolean,
    isGrouped: Boolean = false,
    expandedGroups: Set<String> = emptySet(),
    onToggleGroupCollapse: (String) -> Unit = {},
    subscriptionsMap: Map<String, SubscriptionEntity> = emptyMap(),
    onToggleSubEnabled: ((String, Boolean) -> Unit)? = null,
    onSelectServer: (String) -> Unit,
    onEditServer: (ServerNode) -> Unit,
    onDeleteServer: (String) -> Unit,
    onShareServer: (ServerNode) -> Unit = {},
    viewModel: MainViewModel,
    onAddClick: (() -> Unit)? = null
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val pingMode by viewModel.pingMode.collectAsState()
    val isRealDelay = pingMode.lowercase() in listOf("real_delay", "http", "real")

    if (servers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp, bottom = 90.dp),
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
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = colors.accentCyan,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Text(
                    text = strings.noServersFound,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Text(
                    text = strings.emptyServersHint,
                    color = colors.textSecondary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                if (onAddClick != null) {
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
                                onAddClick()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = LocalAppThemeColors.current.onAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.emptyServersCta,
                                color = LocalAppThemeColors.current.onAccent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    } else {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val activePingIds by viewModel.activePingIds.collectAsState()

        val groupedServers = remember(servers, subscriptionsMap, isGrouped, strings) {
            if (!isGrouped) emptyList()
            else {
                servers.groupBy { server ->
                    when {
                        server.subId.isBlank() || server.subId == "custom" || server.subId == "builtin" -> "personal"
                        else -> server.subId
                    }
                }.map { (groupId, groupNodes) ->
                    val isPersonal = groupId == "personal"
                    val isPreset = groupId in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS
                    val title = when {
                        isPersonal -> strings.uiPersonalServers
                        else -> subscriptionsMap[groupId]?.let { subscriptionDisplayName(it.id, it.name, strings) } ?: if (isPreset) strings.subscriptionBuiltIn else strings.subscriptionsLabel
                    }
                    ServerGroup(
                        id = groupId,
                        title = title,
                        isPreset = isPreset,
                        isPersonal = isPersonal,
                        subId = if (isPersonal) null else groupId,
                        servers = groupNodes
                    )
                }.sortedWith(
                    compareBy(
                        { if (it.isPersonal) 0 else if (!it.isPreset) 1 else 2 },
                        { it.title }
                    )
                )
            }
        }

        var menuServer by remember { mutableStateOf<ServerNode?>(null) }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp + LocalBottomDockInset.current)
        ) {
            if (isAutoBalancerVisible) {
                item(key = "auto_balancer_item", contentType = "balancer") {
                    val isAutoSelect by viewModel.isAutoSelect.collectAsState()
                    AutoBalancerCard(
                        isAutoSelect = isAutoSelect,
                        isPinging = isPinging,
                        activeServerName = selectedServer?.name,
                        activeServerPing = selectedServer?.ping ?: 0L,
                        onToggle = {
                            viewModel.toggleAutoSelectServer { best ->
                                if (best != null) {
                                    Toast.makeText(context, String.format(strings.autoSelectSelected, best.name), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                }
            }

            if (isGrouped) {
                groupedServers.forEach { group ->
                    val isCollapsed = group.id !in expandedGroups
                    val subEntity = group.subId?.let { subscriptionsMap[it] }
                    val isSubEnabled = subEntity?.enabled ?: true

                    item(key = "group_header_${group.id}", contentType = "header") {
                        SubscriptionGroupHeader(
                            group = group,
                            isCollapsed = isCollapsed,
                            isEnabled = isSubEnabled,
                            isPinging = group.servers.any { it.id in activePingIds },
                            pingEnabled = !isPinging,
                            onPing = {
                                viewModel.testServersPing(group.servers) { count ->
                                    Toast.makeText(context, String.format(strings.uiPingSummary, group.title, count), Toast.LENGTH_SHORT).show()
                                }
                            },
                            onToggleCollapse = { onToggleGroupCollapse(group.id) },
                            onToggleEnabled = if (group.subId != null && onToggleSubEnabled != null) {
                                { enabled -> onToggleSubEnabled(group.subId, enabled) }
                            } else null
                        )
                    }

                    if (!isCollapsed && isSubEnabled) {
                        items(
                            items = group.servers,
                            key = { it.id },
                            contentType = { "server_node" }
                        ) { server ->
                            ServerCard(
                                server = server,
                                isSelected = selectedServer?.id == server.id,
                                isTestingPing = server.id in activePingIds,
                                onTestPing = {

                                    viewModel.testServerPing(server) { delay ->

                                        val modeDesc = if (isRealDelay) "Real Delay (HTTP 204)" else "TCP Ping"
                                        if (delay > 0) {
                                            Toast.makeText(context, "«${server.name}»: $modeDesc $delay ms", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "«${server.name}»: Ошибка ($modeDesc недоступен)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                onSelect = { onSelectServer(server.id) },
                                onEdit = { onEditServer(server) },
                                onMoreMenu = { menuServer = server }
                            )
                        }
                    }
                }
            } else {
                items(
                    items = servers,
                    key = { it.id },
                    contentType = { "server_node" }
                ) { server ->
                    ServerCard(
                        server = server,
                        isSelected = selectedServer?.id == server.id,
                        isTestingPing = server.id in activePingIds,
                        onTestPing = {

                            viewModel.testServerPing(server) { delay ->

                                val modeDesc = if (isRealDelay) "Real Delay (HTTP 204)" else "TCP Ping"
                                if (delay > 0) {
                                    Toast.makeText(context, "«${server.name}»: $modeDesc $delay ms", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "«${server.name}»: Ошибка ($modeDesc недоступен)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onSelect = { onSelectServer(server.id) },
                        onEdit = { onEditServer(server) },
                        onMoreMenu = { menuServer = server }
                    )
                }
            }
        }

        // Hoisted Single Action Dialog for selected server (eliminates 200 embedded DropdownMenus)
        if (menuServer != null) {
            val currentServer = menuServer!!
            AlertDialog(
                onDismissRequest = { menuServer = null },
                title = {
                    Text(
                        text = currentServer.name,
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val link = currentServer.rawLink.ifBlank { LinkParser.toShareLink(currentServer) }
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(link))
                                    Toast.makeText(context, strings.linkCopied, Toast.LENGTH_SHORT).show()
                                    menuServer = null
                                }
                                .padding(vertical = 11.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(strings.copyLink, color = colors.textPrimary, fontSize = 13.sp)
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val s = currentServer
                                    menuServer = null
                                    onShareServer(s)
                                }
                                .padding(vertical = 11.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.QrCode2, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(strings.shareAction, color = colors.textPrimary, fontSize = 13.sp)
                        }

                        HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val id = currentServer.id
                                    menuServer = null
                                    onDeleteServer(id)
                                }
                                .padding(vertical = 11.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = colors.accentCrimson, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(strings.delete, color = colors.accentCrimson, fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { menuServer = null }) {
                        Text(strings.cancel, color = colors.textSecondary)
                    }
                },
                containerColor = colors.surfaceElevated,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

/**
 * Server Card with optimized lightweight drawing for high-performance 200+ list scrolling
 */
@Composable
fun ServerCard(
    modifier: Modifier = Modifier,
    server: ServerNode,
    isSelected: Boolean,
    isTestingPing: Boolean = false,
    onTestPing: () -> Unit = {},
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onMoreMenu: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val protoColor = remember(server.protocol, colors) { getProtocolColor(server.protocol, colors) }

    // True classification: is it from a subscription or a standalone manual config?
    val isPreset = server.subId in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS
    val isFromSubscription = server.subId.isNotBlank() &&
            server.subId != "custom" &&
            server.subId != "builtin"

    val cardAccentBorder = remember(isSelected, isPreset, isFromSubscription, isTestingPing, colors) {
        if (isTestingPing) colors.accentAmber
        else if (isSelected) colors.accentCyan
        else if (isPreset) colors.accentCyan.copy(alpha = 0.25f)
        else if (isFromSubscription) colors.accentAmber.copy(alpha = 0.25f)
        else colors.accentEmerald.copy(alpha = 0.20f)
    }

    val ping = server.ping
    val (pingText, pingColor) = when {
        ping in 1..120 -> "$ping ms" to colors.accentEmerald
        ping in 121..250 -> "$ping ms" to colors.accentAmber
        ping > 250 -> "$ping ms" to colors.accentCrimson
        ping < 0 -> "" to colors.accentCrimson
        else -> "—" to colors.textTertiary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 14,
                accentBorder = cardAccentBorder,
                innerSheen = true
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection Checkmark Dot
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (isSelected) colors.accentCyan else colors.borderMedium, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.accentCyan)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Main Content Column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Row 1: Badges (Origin) + Ping Indicator + Edit + More Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Origin badge
                if (isPreset) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.accentCyan.copy(alpha = 0.15f))
                            .border(0.5.dp, colors.accentCyan.copy(alpha = 0.40f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = colors.accentCyan,
                                modifier = Modifier.size(9.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = String.format(strings.uiPresetTag, subscriptionDisplayName(server.subId, server.subName, strings).ifBlank { "VPN" }),
                                color = colors.accentCyan,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else if (isFromSubscription) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.accentAmber.copy(alpha = 0.15f))
                            .border(0.5.dp, colors.accentAmber.copy(alpha = 0.40f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                tint = colors.accentAmber,
                                modifier = Modifier.size(9.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = String.format(strings.uiSubscriptionTag, subscriptionDisplayName(server.subId, server.subName, strings).ifBlank { "PRO" }),
                                color = colors.accentAmber,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.accentEmerald.copy(alpha = 0.12f))
                            .border(0.5.dp, colors.accentEmerald.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
                            .padding(horizontal = 5.dp, vertical = 1.5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = colors.accentEmerald,
                                modifier = Modifier.size(9.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = strings.uiPersonalTag,
                                color = colors.accentEmerald,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Ping Indicator (clickable to test latency)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (ping != 0L) pingColor.copy(alpha = 0.12f) else Color.Transparent)
                        .border(
                            0.5.dp,
                            if (ping != 0L) pingColor.copy(alpha = 0.35f) else colors.borderMedium.copy(alpha = 0.3f),
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(enabled = !isTestingPing) { onTestPing() }
                        .padding(horizontal = 6.dp, vertical = 2.5.dp)
                ) {
                    if (isTestingPing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(9.dp),
                            strokeWidth = 1.5.dp,
                            color = colors.accentCyan
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = strings.pinging,
                            color = colors.accentCyan,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    } else if (ping < 0) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = strings.pingNoResponse,
                            tint = colors.accentCrimson,
                            modifier = Modifier.size(14.dp)
                        )
                    } else {
                        if (ping > 0) {
                            ServerSignalBars(pingMs = ping)
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Text(
                            text = pingText,
                            color = pingColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Edit Icon Button
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = strings.edit,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }

                // More Menu Button (3-dots)
                IconButton(
                    onClick = onMoreMenu,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = strings.uiActions,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Row 2: Server Name (takes full width)
            Text(
                text = server.name,
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Row 3: Protocol Tags (VLESS, REALITY, etc.) + Address
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    TagPill(text = server.protocol.uppercase(), color = protoColor)
                    if (server.security.isNotBlank() && server.security != "none") {
                        TagPill(text = server.security.uppercase(), color = colors.accentEmerald)
                    }
                    if (server.network.isNotBlank()) {
                        TagPill(text = server.network.uppercase(), color = colors.accentViolet)
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "${server.address}:${server.port}",
                    color = colors.textSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
    }
}

@Composable
private fun ServerSignalBars(pingMs: Long) {
    val colors = LocalAppThemeColors.current
    val activeBars = when {
        pingMs <= 0 -> 0
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
        modifier = Modifier.height(10.dp)
    ) {
        val heights = listOf(3.5.dp, 6.5.dp, 9.5.dp)
        heights.forEachIndexed { index, h ->
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(h)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (index < activeBars) barColor else colors.borderMedium.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
fun TagPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .border(0.5.dp, color.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun AutoBalancerCard(
    isAutoSelect: Boolean,
    isPinging: Boolean,
    activeServerName: String?,
    activeServerPing: Long,
    onToggle: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val boltRotation = if (isPinging) {
        val infiniteTransition = rememberInfiniteTransition(label = "balancer_anim")
        val spin by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = LinearEasing),
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
            .fillMaxWidth()
            .liquidGlass(
                cornerRadius = 12,
                accentBorder = if (isAutoSelect) colors.accentCyan.copy(alpha = 0.55f) else null,
                innerSheen = true
            )
            .tactileClickable(targetScale = 0.98f) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Bolt,
            contentDescription = null,
            tint = if (isAutoSelect) colors.accentCyan else colors.textTertiary,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer {
                    if (isPinging) rotationZ = boltRotation
                }
        )

        Spacer(modifier = Modifier.width(8.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strings.uiAutoSelect,
                color = colors.textPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isAutoSelect && !activeServerName.isNullOrBlank()) {
                    "• $activeServerName" + if (activeServerPing > 0) " (${activeServerPing}ms)" else ""
                } else {
                    "• ${if (isAutoSelect) strings.uiFastest else strings.uiOff}"
                },
                color = if (isAutoSelect) colors.accentCyan else colors.textSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        Box(
            modifier = Modifier
                .liquidGlass(
                    cornerRadius = 6,
                    accentBorder = if (isAutoSelect) colors.accentEmerald.copy(alpha = 0.5f) else null,
                    innerSheen = true
                )
                .background(
                    if (isAutoSelect) colors.accentEmerald.copy(alpha = 0.15f) else Color.Transparent,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (isAutoSelect) strings.onUpper else strings.offUpper,
                color = if (isAutoSelect) colors.accentEmerald else colors.textTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Dialog for sharing a server node via QR Code and System Share Sheet
 */
@Composable
private fun ServerShareDialog(
    server: ServerNode,
    onDismiss: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val shareLink = remember(server) {
        LinkParser.toShareLink(server)
    }

    val qrBitmap = remember(shareLink) {
        QrCodeGenerator.generateQrBitmap(shareLink, sizePx = 640)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strings.shareServerTitle,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = colors.textSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = server.name,
                    color = colors.accentCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${server.protocol.uppercase()} • ${server.address}:${server.port}",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(14.dp))

                // High contrast QR Container (white background guarantees instant camera readability)
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Server QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(34.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = String.format(strings.sharePayloadTooLarge, shareLink.toByteArray(Charsets.UTF_8).size),
                                color = Color(0xFF1E293B),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = strings.shareQrPrompt,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons: Copy & System Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Copy Link Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentCyan.copy(alpha = 0.4f))
                            .tactileClickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(shareLink))
                                Toast.makeText(context, strings.linkCopied, Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareCopyLink,
                            color = colors.accentCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // System Share Intent Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentEmerald.copy(alpha = 0.4f))
                            .tactileClickable {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, server.name)
                                    putExtra(Intent.EXTRA_TEXT, shareLink)
                                }
                                val shareIntent = Intent.createChooser(sendIntent, strings.shareServerTitle)
                                context.startActivity(shareIntent)
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareSend,
                            color = colors.accentEmerald,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * Dialog for sharing a subscription via QR Code and System Share Sheet
 */
@Composable
private fun SubscriptionShareDialog(
    sub: SubscriptionEntity,
    onDismiss: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val shareLink = sub.url

    val qrBitmap = remember(shareLink) {
        QrCodeGenerator.generateQrBitmap(shareLink, sizePx = 640)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strings.shareSubscriptionTitle,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = colors.textSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = subscriptionDisplayName(sub.id, sub.name, strings),
                    color = colors.accentAmber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = String.format(strings.subscriptionNodes, sub.nodeCount),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Subscription QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(34.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = String.format(strings.sharePayloadTooLarge, shareLink.toByteArray(Charsets.UTF_8).size),
                                color = Color(0xFF1E293B),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = strings.shareSubQrPrompt,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Copy Link Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentAmber.copy(alpha = 0.4f))
                            .tactileClickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(shareLink))
                                Toast.makeText(context, strings.linkCopied, Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareCopyLink,
                            color = colors.accentAmber,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // System Share Intent Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentEmerald.copy(alpha = 0.4f))
                            .tactileClickable {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, sub.name)
                                    putExtra(Intent.EXTRA_TEXT, shareLink)
                                }
                                val shareIntent = Intent.createChooser(sendIntent, strings.shareSubscriptionTitle)
                                context.startActivity(shareIntent)
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareSend,
                            color = colors.accentEmerald,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}
