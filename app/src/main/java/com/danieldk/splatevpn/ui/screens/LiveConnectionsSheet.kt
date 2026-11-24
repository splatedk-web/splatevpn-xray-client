package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton
import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.tvFocusOutline

import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.ToolDialog
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.vpn.ConnectionStatus
import com.danieldk.splatevpn.vpn.LiveConnection
import com.danieldk.splatevpn.vpn.OutboundRuleType
import java.util.Date
import java.util.Locale

private enum class ConnectionActivityFilter { ALL, ACTIVE, HISTORY }

@Composable
fun LiveConnectionsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current

    val connections by viewModel.liveConnections.collectAsState()
    val totalCount by viewModel.liveConnectionsTotalCount.collectAsState()
    val activeCount by viewModel.liveConnectionsActiveCount.collectAsState()
    val historyCount by viewModel.liveConnectionsHistoryCount.collectAsState()
    val totalHits by viewModel.liveConnectionsTotalHits.collectAsState()
    val proxyCount by viewModel.liveConnectionsProxyCount.collectAsState()
    val directCount by viewModel.liveConnectionsDirectCount.collectAsState()
    val blockCount by viewModel.liveConnectionsBlockCount.collectAsState()
    val dnsCount by viewModel.liveConnectionsDnsCount.collectAsState()
    val isPaused by viewModel.isLiveConnectionsPaused.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<OutboundRuleType?>(null) }
    var activityFilter by remember { mutableStateOf(ConnectionActivityFilter.ALL) }
    var inspectedConnection by remember { mutableStateOf<LiveConnection?>(null) }

    val filteredConnections = remember(connections, searchQuery, selectedFilter, activityFilter) {
        val query = searchQuery.trim()
        connections.filter { conn ->
            val matchesActivity = when (activityFilter) {
                ConnectionActivityFilter.ALL -> true
                ConnectionActivityFilter.ACTIVE -> conn.isActive
                ConnectionActivityFilter.HISTORY -> !conn.isActive
            }
            val matchesFilter = selectedFilter == null || conn.ruleType == selectedFilter
            val matchesSearch = if (query.isEmpty()) {
                true
            } else {
                conn.destination.contains(query, ignoreCase = true) ||
                    conn.port.toString().contains(query) ||
                    conn.network.contains(query, ignoreCase = true) ||
                    conn.protocolHint.contains(query, ignoreCase = true) ||
                    conn.source.contains(query, ignoreCase = true) ||
                    conn.outboundTag.contains(query, ignoreCase = true) ||
                    conn.serverName.contains(query, ignoreCase = true)
            }
            matchesActivity && matchesFilter && matchesSearch
        }
    }

    ToolDialog(
        onDismiss,
        Modifier.testTag("liveConnectionsDialogPanel"),
        maxHeight = if (connections.isEmpty()) 560.dp else 760.dp,
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        strings.liveConnectionsTitle,
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        strings.liveConnectionsSubtitle,
                        color = colors.textSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = strings.close, tint = colors.textPrimary)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) colors.accentAmber else colors.accentEmerald)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (isPaused) strings.uiPaused else strings.uiMonitoring,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { viewModel.togglePauseLiveConnections() }) {
                    Icon(
                        if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (isPaused) strings.uiResume else strings.uiPause)
                }
                IconButton(onClick = { viewModel.clearLiveConnections() }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = strings.uiClear, tint = colors.textSecondary)
                }
            }

            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
            ) {
                item(key = "monitor_controls", contentType = "controls") {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChipButton(
                                label = String.format(strings.localized("Все (%d)", "All (%d)"), totalCount),
                                isSelected = activityFilter == ConnectionActivityFilter.ALL,
                                color = colors.textPrimary,
                                onClick = { activityFilter = ConnectionActivityFilter.ALL },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChipButton(
                                label = String.format(strings.localized("Активные (%d)", "Active (%d)"), activeCount),
                                isSelected = activityFilter == ConnectionActivityFilter.ACTIVE,
                                color = colors.accentEmerald,
                                onClick = { activityFilter = ConnectionActivityFilter.ACTIVE },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChipButton(
                                label = String.format(strings.localized("История (%d)", "History (%d)"), historyCount),
                                isSelected = activityFilter == ConnectionActivityFilter.HISTORY,
                                color = colors.accentAmber,
                                onClick = { activityFilter = ConnectionActivityFilter.HISTORY },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MetricCounterBadge(
                                label = strings.filterAll,
                                count = totalCount,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            MetricCounterBadge(
                                label = strings.filterProxy,
                                count = proxyCount,
                                color = colors.accentCyan,
                                modifier = Modifier.weight(1f)
                            )
                            MetricCounterBadge(
                                label = strings.filterDirect,
                                count = directCount,
                                color = colors.accentEmerald,
                                modifier = Modifier.weight(1f)
                            )
                            if (blockCount > 0) {
                                MetricCounterBadge(
                                    label = strings.filterBlock,
                                    count = blockCount,
                                    color = colors.accentCrimson,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            MetricCounterBadge(
                                label = strings.filterDns,
                                count = dnsCount,
                                color = colors.accentViolet,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = strings.liveConnectionsSearchPlaceholder,
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = strings.uiClear,
                                            tint = colors.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentCyan,
                                unfocusedBorderColor = colors.borderSubtle.copy(alpha = 0.5f),
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedContainerColor = colors.surface.copy(alpha = 0.5f),
                                unfocusedContainerColor = colors.surface.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChipButton(
                                label = strings.filterAll,
                                isSelected = selectedFilter == null,
                                color = colors.textPrimary,
                                onClick = { selectedFilter = null },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChipButton(
                                label = strings.filterProxy,
                                isSelected = selectedFilter == OutboundRuleType.PROXY,
                                color = colors.accentCyan,
                                onClick = { selectedFilter = OutboundRuleType.PROXY },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChipButton(
                                label = strings.filterDirect,
                                isSelected = selectedFilter == OutboundRuleType.DIRECT,
                                color = colors.accentEmerald,
                                onClick = { selectedFilter = OutboundRuleType.DIRECT },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChipButton(
                                label = strings.filterDns,
                                isSelected = selectedFilter == OutboundRuleType.DNS,
                                color = colors.accentViolet,
                                onClick = { selectedFilter = OutboundRuleType.DNS },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChipButton(
                                label = strings.filterBlock,
                                isSelected = selectedFilter == OutboundRuleType.BLOCK,
                                color = colors.accentCrimson,
                                onClick = { selectedFilter = OutboundRuleType.BLOCK },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }

                if (filteredConnections.isEmpty()) {
                    item(key = "monitor_empty", contentType = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 140.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surface.copy(alpha = 0.35f))
                                .border(1.dp, colors.borderSubtle.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert,
                                    contentDescription = null,
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(38.dp)
                                )
                                Text(
                                    text = strings.noConnectionsYet,
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = strings.noConnectionsDesc,
                                    color = colors.textSecondary,
                                    fontSize = 11.5.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = filteredConnections,
                        key = { it.endpointKey },
                        contentType = { "connection" }
                    ) { conn ->
                        ConnectionCard(
                            connection = conn,
                            onClick = { inspectedConnection = conn }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(strings.liveConnectionsSummary, filteredConnections.size, totalHits),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    modifier = Modifier.heightIn(min = 48.dp).testTag("liveConnectionsClose"),
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentCyan),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = strings.close,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                }
            }
        }
    }

    inspectedConnection?.let { conn ->
        ConnectionDetailsDialog(
            connection = conn,
            viewModel = viewModel,
            onDismiss = { inspectedConnection = null }
        )
    }
}

@Composable
private fun MetricCounterBadge(
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
            .padding(vertical = 5.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = count.toString(),
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            color = color.copy(alpha = 0.85f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FilterChipButton(
    label: String,
    isSelected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (isSelected) color.copy(alpha = 0.22f) else Color.Transparent
    val border = if (isSelected) color.copy(alpha = 0.80f) else color.copy(alpha = 0.25f)
    val textCol = if (isSelected) color else LocalAppThemeColors.current.textSecondary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .tvFocusOutline().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .heightIn(min = 40.dp)
            .padding(vertical = 5.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textCol,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatConnectionDuration(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return String.format(Locale.US, "%d:%02d:%02d", h, m, s)
}

@Composable
private fun ConnectionCard(
    connection: LiveConnection,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current

    val ruleColor = when (connection.ruleType) {
        OutboundRuleType.PROXY -> colors.accentCyan
        OutboundRuleType.DIRECT -> colors.accentEmerald
        OutboundRuleType.BLOCK -> colors.accentCrimson
        OutboundRuleType.DNS -> colors.accentViolet
        OutboundRuleType.OTHER -> colors.textSecondary
    }

    val ruleBg = ruleColor.copy(alpha = 0.12f)
    val ruleBorder = ruleColor.copy(alpha = if (connection.isActive) 0.42f else 0.20f)

    val timeLabel = remember(connection.timestamp, connection.lastSeen) {
        if (connection.durationMs >= 1000L) {
            formatConnectionDuration(connection.durationMs)
        } else {
            DateFormat.format("HH:mm:ss", Date(connection.lastSeen)).toString()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = if (connection.isActive) 0.56f else 0.34f))
            .border(1.dp, ruleBorder, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (connection.isActive) colors.accentEmerald else colors.textTertiary)
                    )
                    if (connection.sequenceNumber > 0) {
                        Text(
                            text = "#${connection.sequenceNumber}",
                            color = colors.textTertiary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = connection.destination,
                        color = if (connection.isActive) colors.textPrimary else colors.textSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        text = ":${connection.port}",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = timeLabel,
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            // Source + network + protocol hint + hits
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = connection.source,
                    color = colors.textTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = "•",
                    color = colors.textTertiary,
                    fontSize = 10.sp
                )
                Text(
                    text = connection.network,
                    color = if (connection.network == "UDP") colors.accentAmber else colors.accentEmerald,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (connection.protocolHint.isNotBlank() && !connection.protocolHint.equals(connection.network, ignoreCase = true)) {
                    Text(
                        text = connection.protocolHint,
                        color = colors.accentCyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (connection.hitCount > 1) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.accentAmber.copy(alpha = 0.16f))
                            .border(1.dp, colors.accentAmber.copy(alpha = 0.40f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "×${connection.hitCount}",
                            color = colors.accentAmber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Route + active server name + rule badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val routeDesc = buildString {
                    if (connection.inboundTag.isNotBlank()) {
                        append(connection.inboundTag).append(" ➔ ")
                    }
                    append(connection.outboundTag)
                    if (connection.serverName.isNotBlank() && connection.ruleType == OutboundRuleType.PROXY) {
                        append(" · ").append(connection.serverName)
                    }
                }
                Text(
                    text = routeDesc,
                    color = colors.textSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ruleBg)
                        .border(1.dp, ruleBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = connection.ruleType.label(),
                        color = ruleColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionDetailsDialog(
    connection: LiveConnection,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val firstSeenText = remember(connection.timestamp) {
        DateFormat.format("HH:mm:ss", Date(connection.timestamp)).toString()
    }
    val lastSeenText = remember(connection.lastSeen) {
        DateFormat.format("HH:mm:ss", Date(connection.lastSeen)).toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (connection.isActive) colors.accentEmerald else colors.textTertiary)
                )
                Text(
                    text = "${connection.destination}:${connection.port}",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailRow(
                    strings.localized("Состояние", "State"),
                    buildString {
                        append(if (connection.isActive) strings.localized("Активно", "Active") else strings.localized("Завершено", "Closed"))
                        append(" · ")
                        append(if (connection.status == ConnectionStatus.ACCEPTED) "ACCEPTED" else "REJECTED")
                    }
                )
                DetailRow(
                    strings.localized("Транспорт", "Transport"),
                    "${connection.network}${if (connection.protocolHint.isNotBlank()) " / ${connection.protocolHint}" else ""}"
                )
                DetailRow(strings.localized("Источник", "Source"), connection.source)
                DetailRow(
                    strings.localized("Маршрут", "Route"),
                    if (connection.inboundTag.isNotBlank()) "${connection.inboundTag} ➔ ${connection.outboundTag}" else connection.outboundTag
                )
                if (connection.serverName.isNotBlank() && connection.ruleType == OutboundRuleType.PROXY) {
                    DetailRow(strings.localized("Сервер", "Server"), connection.serverName)
                }
                DetailRow(
                    strings.localized("Запросы", "Requests"),
                    "${connection.hitCount} · ${formatConnectionDuration(connection.durationMs)}"
                )
                DetailRow(
                    strings.localized("Время", "Time"),
                    "$firstSeenText — $lastSeenText"
                )

                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(connection.destination))
                        Toast.makeText(
                            context,
                            String.format(strings.localized("Скопировано: %s", "Copied: %s"), connection.destination),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(strings.localized("Копировать адрес", "Copy address"), fontSize = 12.sp)
                }

                if (connection.isDomain) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.addCustomRoute(connection.destination, "direct")
                                Toast.makeText(
                                    context,
                                    String.format(strings.localized("Добавлено в Direct: %s", "Added to Direct: %s"), connection.destination),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text("Direct", fontSize = 11.sp, color = colors.accentEmerald)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.addCustomRoute(connection.destination, "proxy")
                                Toast.makeText(
                                    context,
                                    String.format(strings.localized("Добавлено в Proxy: %s", "Added to Proxy: %s"), connection.destination),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text("Proxy", fontSize = 11.sp, color = colors.accentCyan)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.addCustomRoute(connection.destination, "block")
                                Toast.makeText(
                                    context,
                                    String.format(strings.localized("Заблокировано: %s", "Blocked: %s"), connection.destination),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text("Block", fontSize = 11.sp, color = colors.accentCrimson)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.close)
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalAppThemeColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = colors.textTertiary,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 10.dp)
        )
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
