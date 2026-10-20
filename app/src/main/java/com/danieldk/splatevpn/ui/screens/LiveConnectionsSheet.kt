package com.danieldk.splatevpn.ui.screens

import android.text.format.DateFormat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.vpn.ConnectionStatus
import com.danieldk.splatevpn.vpn.LiveConnection
import com.danieldk.splatevpn.vpn.OutboundRuleType
import java.util.Date

@Composable
fun LiveConnectionsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current

    val connections by viewModel.liveConnections.collectAsState()
    val totalCount by viewModel.liveConnectionsTotalCount.collectAsState()
    val totalHits by viewModel.liveConnectionsTotalHits.collectAsState()
    val proxyCount by viewModel.liveConnectionsProxyCount.collectAsState()
    val directCount by viewModel.liveConnectionsDirectCount.collectAsState()
    val blockCount by viewModel.liveConnectionsBlockCount.collectAsState()
    val dnsCount by viewModel.liveConnectionsDnsCount.collectAsState()
    val isPaused by viewModel.isLiveConnectionsPaused.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<OutboundRuleType?>(null) }

    val filteredConnections = remember(connections, searchQuery, selectedFilter) {
        val query = searchQuery.trim()
        connections.filter { conn ->
            val matchesFilter = selectedFilter == null || conn.ruleType == selectedFilter
            val matchesSearch = if (query.isEmpty()) {
                true
            } else {
                conn.destination.contains(query, ignoreCase = true) ||
                        conn.port.toString().contains(query) ||
                        conn.network.contains(query, ignoreCase = true) ||
                        conn.outboundTag.contains(query, ignoreCase = true)
            }
            matchesFilter && matchesSearch
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                .padding(horizontal = 14.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .heightIn(max = if (connections.isEmpty()) 560.dp else 760.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(colors.surfaceElevated.copy(alpha = 0.94f))
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                colors.accentCyan.copy(alpha = 0.50f),
                                colors.borderSubtle.copy(alpha = 0.30f)
                            )
                        ),
                        RoundedCornerShape(26.dp)
                    )
                    .padding(18.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(strings.liveConnectionsTitle, color = colors.textPrimary,
                            fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(strings.liveConnectionsSubtitle, color = colors.textSecondary,
                            fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = strings.close, tint = colors.textPrimary)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape)
                        .background(if (isPaused) colors.accentAmber else colors.accentEmerald))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPaused) strings.uiPaused else strings.uiMonitoring,
                        color = colors.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.togglePauseLiveConnections() }) {
                        Icon(if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (isPaused) strings.uiResume else strings.uiPause)
                    }
                    IconButton(onClick = { viewModel.clearLiveConnections() }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = strings.uiClear, tint = colors.textSecondary)
                    }
                }

                // Keep the footer fixed while controls can scroll on short
                // screens or when the keyboard reduces the available height.
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    item(key = "monitor_controls", contentType = "controls") {
                        Column {
                            Spacer(modifier = Modifier.height(14.dp))

                            // Stats Metrics Pill Row
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

                            Spacer(modifier = Modifier.height(12.dp))

                            // Search Bar
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
                                    .heightIn(min = 56.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Filter Chips Row
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

                            Spacer(modifier = Modifier.height(12.dp))
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
                            ConnectionCard(connection = conn)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Footer Info & Close
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
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentCyan),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = strings.close,
                            color = LocalAppThemeColors.current.onAccent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.5.sp
                        )
                    }
                }
            }
        }
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textCol,
            fontSize = 10.5.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ConnectionCard(connection: LiveConnection) {
    val colors = LocalAppThemeColors.current

    val ruleColor = when (connection.ruleType) {
        OutboundRuleType.PROXY -> colors.accentCyan
        OutboundRuleType.DIRECT -> colors.accentEmerald
        OutboundRuleType.BLOCK -> colors.accentCrimson
        OutboundRuleType.DNS -> colors.accentViolet
        OutboundRuleType.OTHER -> colors.textSecondary
    }

    val ruleBg = ruleColor.copy(alpha = 0.12f)
    val ruleBorder = ruleColor.copy(alpha = 0.35f)

    val timeFormatted = remember(connection.timestamp) {
        DateFormat.format("HH:mm:ss", Date(connection.timestamp)).toString()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface.copy(alpha = 0.50f))
            .border(1.dp, ruleBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // Top Row: Destination + Rule Badge
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
                    Icon(
                        imageVector = if (connection.isDomain) Icons.Default.Public else Icons.Default.Dns,
                        contentDescription = null,
                        tint = if (connection.isDomain) colors.accentCyan else colors.textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = connection.destination,
                        color = colors.textPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = ":${connection.port}",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
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

                Spacer(modifier = Modifier.width(8.dp))

                // Outbound Tag Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ruleBg)
                        .border(1.dp, ruleBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = connection.ruleType.label().uppercase(),
                        color = ruleColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Bottom Row: Network Protocol, Route Path, Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Protocol badge (TCP / UDP)
                    Text(
                        text = connection.network,
                        color = if (connection.network == "UDP") colors.accentAmber else colors.accentEmerald,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )

                    Text(
                        text = "•",
                        color = colors.textSecondary,
                        fontSize = 10.sp
                    )

                    // Inbound -> Outbound routing
                    val routeDesc = if (connection.inboundTag.isNotBlank()) {
                        "${connection.inboundTag} ➔ ${connection.outboundTag}"
                    } else {
                        connection.outboundTag
                    }
                    Text(
                        text = routeDesc,
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = timeFormatted,
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
