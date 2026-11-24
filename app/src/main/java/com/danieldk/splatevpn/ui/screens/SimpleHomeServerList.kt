package com.danieldk.splatevpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.QuickServerSelection
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton
import com.danieldk.splatevpn.ui.components.SplateTextButton
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings
import com.danieldk.splatevpn.ui.theme.localized
import kotlinx.coroutines.launch

private const val SIMPLE_FOLDER_PAGE_SIZE = 20
private const val PERSONAL_FOLDER_KEY = "__personal__"

private data class SimpleServerFolder(
    val key: String,
    val title: String,
    val subId: String?,
    val isPersonal: Boolean,
    val servers: List<ServerNode>
)

@Composable
internal fun SimpleHomeServerList(
    viewModel: MainViewModel,
    selectedServerId: String?,
    modifier: Modifier = Modifier
) {
    val allServers by viewModel.allServers.collectAsState()
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val disabledIds by viewModel.disabledServerIds.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val activePingIds by viewModel.activePingIds.collectAsState()
    val torSettings by viewModel.torSettings.collectAsState()

    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val resources = com.danieldk.splatevpn.ui.theme.LocalAppResources.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var selectingId by remember { mutableStateOf<String?>(null) }
    var showAddConfig by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val updatingIds by viewModel.updatingSubscriptionIds.collectAsState()
    var serverToDelete by remember { mutableStateOf<ServerNode?>(null) }

    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() }
    val folderLimits = remember { mutableStateMapOf<String, Int>() }

    val personalLabel = strings.localized("Личные серверы", "Personal servers")
    val folders = remember(allServers, subscriptions, disabledIds, query, selectedServerId, personalLabel, strings) {
        val needle = query.trim()
        val enabledSubs = subscriptions.filter { it.enabled }
        val enabledSubIds = enabledSubs.mapTo(hashSetOf()) { it.id }

        fun sortNodes(list: List<ServerNode>): List<ServerNode> = list.sortedWith(
            compareByDescending<ServerNode> { it.id == selectedServerId }
                .thenBy { if (it.ping > 0) 0 else if (it.ping == 0L) 1 else 2 }
                .thenBy { if (it.ping > 0) it.ping else Long.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )

        val visibleNodes = allServers.filter { node ->
            viewModel.isNodeEnabled(node, disabledIds) &&
                (QuickServerSelection.isPersonal(node) || node.subId in enabledSubIds) &&
                (needle.isEmpty() || node.name.contains(needle, ignoreCase = true))
        }

        val personalNodes = sortNodes(visibleNodes.filter { QuickServerSelection.isPersonal(it) })
        val bySubId = visibleNodes.filterNot { QuickServerSelection.isPersonal(it) }.groupBy { it.subId }

        buildList {
            if (personalNodes.isNotEmpty()) {
                add(
                    SimpleServerFolder(
                        key = PERSONAL_FOLDER_KEY,
                        title = personalLabel,
                        subId = null,
                        isPersonal = true,
                        servers = personalNodes
                    )
                )
            }
            enabledSubs.forEach { sub ->
                val nodes = bySubId[sub.id].orEmpty()
                if (nodes.isNotEmpty() || needle.isEmpty()) {
                    add(
                        SimpleServerFolder(
                            key = "sub:${sub.id}",
                            title = subscriptionDisplayName(sub.id, sub.name, strings).ifBlank { strings.localized("Подписка", "Subscription") },
                            subId = sub.id,
                            isPersonal = false,
                            servers = sortNodes(nodes)
                        )
                    )
                }
            }
        }
    }

    val totalCount = remember(folders) { folders.sumOf { it.servers.size } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("simpleHomeServerList"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = String.format(
                    strings.localized("СЕРВЕРЫ (%d)", "SERVERS (%d)"),
                    totalCount
                ),
                color = colors.textTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (allServers.size > 8) {
                    Row(
                        modifier = Modifier
                            .liquidGlass(
                                cornerRadius = 10,
                                accentBorder = if (searchExpanded || query.isNotEmpty()) colors.accentCyan.copy(alpha = 0.55f) else null,
                                backgroundColor = if (searchExpanded || query.isNotEmpty()) colors.accentCyan.copy(alpha = 0.12f) else null,
                                innerSheen = true
                            )
                            .tactileClickable(targetScale = 0.92f) {
                                if (searchExpanded) {
                                    searchExpanded = false
                                    query = ""
                                } else {
                                    searchExpanded = true
                                }
                            }
                            .testTag("simpleServersSearchToggle")
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = strings.localized("Поиск сервера", "Search server"),
                            tint = if (searchExpanded || query.isNotEmpty()) colors.accentCyan else colors.textSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .liquidGlass(
                            cornerRadius = 10,
                            accentBorder = colors.accentEmerald.copy(alpha = 0.45f),
                            backgroundColor = colors.accentEmerald.copy(alpha = 0.10f),
                            innerSheen = true
                        )
                        .tactileClickable(targetScale = 0.92f) { showAddConfig = true }
                        .testTag("simpleServersAdd")
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = colors.accentEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = strings.localized("Добавить", "Add"),
                        color = colors.accentEmerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    modifier = Modifier
                        .liquidGlass(cornerRadius = 10, innerSheen = true)
                        .tactileClickable(targetScale = 0.92f) {
                            if (isPinging) viewModel.cancelPingChecks() else viewModel.testAllPings()
                        }
                        .testTag("simpleServersPing")
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (isPinging) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.8.dp,
                            color = colors.accentCyan
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = colors.accentCyan,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Text(
                        text = if (isPinging) {
                            strings.localized("Стоп", "Stop")
                        } else {
                            strings.localized("Пинг", "Ping")
                        },
                        color = colors.accentCyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        updateError?.let { Text(it, color = colors.accentCrimson, fontSize = 12.sp) }

        val searchAnimationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled
        AnimatedVisibility(
            visible = allServers.size > 8 && (searchExpanded || query.isNotEmpty()),
            enter = if (searchAnimationsEnabled) expandVertically(tween(120, easing = FastOutSlowInEasing)) + fadeIn(tween(110)) else expandVertically(tween(0)),
            exit = if (searchAnimationsEnabled) shrinkVertically(tween(110, easing = FastOutSlowInEasing)) + fadeOut(tween(90)) else shrinkVertically(tween(0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 14, innerSheen = true)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .testTag("simpleServersSearch"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accentCyan),
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 13.5.sp
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text(
                                text = strings.localized("Поиск сервера…", "Search server…"),
                                color = colors.textSecondary,
                                fontSize = 13.sp
                            )
                        }
                        innerTextField()
                    }
                )
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = { query = "" },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (folders.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 18, innerSheen = true)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = if (query.isNotBlank()) {
                        strings.localized("Серверы по запросу не найдены", "No matching servers found")
                    } else {
                        strings.localized("Список серверов пока пуст", "No servers available yet")
                    },
                    color = colors.textSecondary,
                    fontSize = 13.sp
                )
                if (query.isBlank()) {
                    SplateOutlinedButton(onClick = { showAddConfig = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings.localized("Добавить сервер или подписку", "Add server or subscription"))
                    }
                }
            }
        } else {
            folders.forEach { folder ->
                val containsSelected = !torSettings.enabled && folder.servers.any { it.id == selectedServerId }
                val isExpanded = if (query.isNotBlank()) {
                    true
                } else {
                    expandedFolders[folder.key] ?: false
                }
                val limit = folderLimits[folder.key] ?: SIMPLE_FOLDER_PAGE_SIZE
                val visibleNodes = folder.servers.take(limit)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .liquidGlass(
                            cornerRadius = 20,
                            accentBorder = if (containsSelected) colors.accentCyan.copy(alpha = 0.45f) else null,
                            innerSheen = true
                        )
                        .testTag("simpleFolder:${folder.key}")
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tactileClickable(targetScale = 0.98f) {
                                expandedFolders[folder.key] = !isExpanded
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (folder.isPersonal) Icons.Default.Folder else Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = if (containsSelected) colors.accentCyan else colors.accentEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = folder.title,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = String.format(
                                    strings.localized("Узлы (%d)", "Nodes (%d)"),
                                    folder.servers.size
                                ),
                                color = colors.textSecondary,
                                fontSize = 12.sp
                            )
                        }
                        if (folder.subId != null) {
                            IconButton(
                                onClick = {
                                    updateError = null
                                    viewModel.updateSubscription(folder.subId) { result ->
                                        updateError = result.exceptionOrNull()?.message
                                    }
                                },
                                enabled = folder.subId !in updatingIds,
                                modifier = Modifier.size(36.dp).testTag("simpleFolderRefresh:${folder.key}")
                            ) {
                                if (folder.subId in updatingIds) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accentEmerald)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = resources.getString(com.danieldk.splatevpn.R.string.folder_update),
                                        tint = colors.accentEmerald,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }
                        }
                        val isFolderPinging = isPinging && folder.servers.any { it.id in activePingIds }
                        IconButton(
                            enabled = folder.servers.isNotEmpty() && (!isPinging || isFolderPinging),
                            onClick = {
                                if (isFolderPinging) viewModel.cancelPingChecks()
                                else if (folder.subId != null) viewModel.testSubscriptionPings(folder.subId)
                                else viewModel.testServersPing(folder.servers)
                            },
                            modifier = Modifier.size(36.dp).testTag("simpleFolderPing:${folder.key}")
                        ) {
                            Icon(
                                imageVector = if (isFolderPinging) Icons.Default.Close else Icons.Default.Wifi,
                                contentDescription = if (isFolderPinging) strings.localized("Остановить проверку", "Stop check")
                                    else resources.getString(com.danieldk.splatevpn.R.string.folder_ping),
                                tint = colors.accentEmerald,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        val animationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled
                        val chevronRotation = animateFloatAsState(
                            targetValue = if (isExpanded) 180f else 0f,
                            animationSpec = if (animationsEnabled) {
                                spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMedium)
                            } else {
                                tween(0)
                            },
                            label = "folder_chevron"
                        )
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier
                                .size(22.dp)
                                .graphicsLayer { rotationZ = chevronRotation.value }
                        )
                    }

                    val animationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled
                    AnimatedVisibility(
                        visible = isExpanded,
                        enter = if (animationsEnabled) {
                            expandVertically(
                                animationSpec = spring(
                                    dampingRatio = 0.86f,
                                    stiffness = Spring.StiffnessMedium
                                )
                            ) + fadeIn(tween(115, easing = FastOutSlowInEasing))
                        } else {
                            expandVertically(tween(0))
                        },
                        exit = if (animationsEnabled) {
                            shrinkVertically(tween(110, easing = FastOutSlowInEasing)) + fadeOut(tween(90))
                        } else {
                            shrinkVertically(tween(0))
                        }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            visibleNodes.forEach { server ->
                                val isSelected = !torSettings.enabled && server.id == selectedServerId
                                val isSelecting = selectingId == server.id
                                val ping = server.ping
                                val pingColor = when {
                                    ping in 1..120 -> colors.accentEmerald
                                    ping in 121..250 -> colors.accentAmber
                                    ping > 250 -> colors.accentCrimson
                                    else -> colors.textTertiary
                                }
                                val displayName = if (server.address.isBlank()) {
                                    server.name
                                } else {
                                    server.name.replace(server.address, "••••")
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .liquidGlass(
                                            cornerRadius = 14,
                                            accentBorder = if (isSelected) colors.accentCyan.copy(alpha = 0.65f) else null,
                                            backgroundColor = if (isSelected) colors.accentCyan.copy(alpha = 0.08f) else null,
                                            innerSheen = false
                                        )
                                        .tactileClickable(targetScale = 0.98f) {
                                            if (selectingId != null) return@tactileClickable
                                            if (isSelected) {
                                                viewModel.setAutoSelect(false)
                                                return@tactileClickable
                                            }
                                            scope.launch {
                                                selectingId = server.id
                                                try {
                                                    updateError = viewModel.selectVpnServer(server.id).exceptionOrNull()?.message
                                                } finally {
                                                    selectingId = null
                                                }
                                            }
                                        }
                                        .testTag("simpleServerItem:${server.id}")
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSelected) colors.accentCyan else colors.textTertiary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = displayName,
                                        color = colors.textPrimary,
                                        fontSize = 13.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    if (isSelecting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = colors.accentCyan
                                        )
                                    } else {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(pingColor.copy(alpha = 0.12f))
                                                .border(1.dp, pingColor.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = if (ping > 0) "$ping ms" else "---",
                                                color = pingColor,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { serverToDelete = server },
                                        enabled = selectingId == null,
                                        modifier = Modifier.testTag("simpleServerDelete:${server.id}")
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, strings.delete, tint = colors.textSecondary)
                                    }
                                }
                            }

                            if (folder.servers.size > visibleNodes.size) {
                                SplateTextButton(
                                    onClick = { folderLimits[folder.key] = limit + SIMPLE_FOLDER_PAGE_SIZE },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("simpleFolderShowMore:${folder.key}")
                                ) {
                                    Text(
                                        text = String.format(
                                            strings.localized("Показать ещё (%d)", "Show more (%d)"),
                                            folder.servers.size - visibleNodes.size
                                        ),
                                        color = colors.accentCyan,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    serverToDelete?.let { server ->
        com.danieldk.splatevpn.ui.components.SplateAlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text(strings.localized("Удалить сервер?", "Delete server?")) },
            text = {
                if (!QuickServerSelection.isPersonal(server)) {
                    Text(strings.localized("Сервер может вернуться при обновлении подписки.",
                        "The server may return when the subscription is refreshed."))
                }
            },
            confirmButton = {
                SplateTextButton(onClick = {
                    viewModel.deleteServer(server.id)
                    serverToDelete = null
                }) { Text(strings.delete) }
            },
            dismissButton = {
                SplateTextButton(onClick = { serverToDelete = null }) { Text(strings.cancel) }
            }
        )
    }

    if (showAddConfig) {
        AddConfigBottomSheet(
            viewModel = viewModel,
            onDismissRequest = { showAddConfig = false }
        )
    }
}
