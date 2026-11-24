package com.danieldk.splatevpn.ui.screens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.danieldk.splatevpn.ui.components.tvFocusOutline
import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog
import com.danieldk.splatevpn.ui.components.SplateCheckbox as Checkbox
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.alpha
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import com.danieldk.splatevpn.ui.components.sampleGlassBackdrop
import com.danieldk.splatevpn.ui.components.LocalGlassTransparency
import com.danieldk.splatevpn.ui.components.LocalGlassBlurEnabled
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.*
import com.danieldk.splatevpn.data.LinkParser
import com.danieldk.splatevpn.data.isProtectedWtpn

enum class SubscriptionFramePosition {
    HEADER,
    MIDDLE_NODE,
    LAST_NODE
}

@Composable
internal fun Modifier.subscriptionGroupFrame(
    position: SubscriptionFramePosition,
    accentColor: Color,
    cornerRadius: Dp = 16.dp,
    strokeWidth: Dp = 1.dp
): Modifier {
    val colors = LocalAppThemeColors.current
    val transparency = LocalGlassTransparency.current
    val blurEnabled = LocalGlassBlurEnabled.current
    val opacityScale = if (android.os.Build.VERSION.SDK_INT >= 31) (1f - transparency * 0.92f).coerceIn(0.12f, 1f) else (1f - transparency * 0.63f).coerceIn(0.25f, 1f)
    val baseSurface = colors.glassSurface.copy(alpha = (colors.glassSurface.alpha * opacityScale).coerceIn(0.08f, 0.95f))

    val shape: Shape = when (position) {
        SubscriptionFramePosition.HEADER -> RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius, bottomStart = 0.dp, bottomEnd = 0.dp)
        SubscriptionFramePosition.MIDDLE_NODE -> RectangleShape
        SubscriptionFramePosition.LAST_NODE -> RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp, bottomStart = cornerRadius, bottomEnd = cornerRadius)
    }

    val baseBorder = if (colors.isDark) colors.borderSubtle else colors.glassBorderDark
    val frameBorderColor: Color = androidx.compose.ui.graphics.lerp(baseBorder, accentColor, 0.20f).copy(alpha = 0.42f)
    val dividerColor: Color = baseBorder.copy(alpha = 0.20f)

    return this
        .clip(shape)
        .then(if (blurEnabled && (transparency > 0.001f || android.os.Build.VERSION.SDK_INT >= 31)) Modifier.sampleGlassBackdrop() else Modifier)
        .background(baseSurface)
        .drawWithCache {
            val r = cornerRadius.toPx()
            val strokePx = strokeWidth.toPx()
            val halfStroke = strokePx / 2f
            val w = size.width
            val h = size.height

            val left = halfStroke
            val top = halfStroke
            val right = (w - halfStroke).coerceAtLeast(left)
            val bottom = (h - halfStroke).coerceAtLeast(top)

            val strokePath = Path()

            when (position) {
                SubscriptionFramePosition.HEADER -> {
                    strokePath.moveTo(left, h)
                    strokePath.lineTo(left, top + r)
                    strokePath.arcTo(
                        rect = Rect(left, top, left + 2 * r, top + 2 * r),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    strokePath.lineTo(right - r, top)
                    strokePath.arcTo(
                        rect = Rect(right - 2 * r, top, right, top + 2 * r),
                        startAngleDegrees = 270f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false
                    )
                    strokePath.lineTo(right, h)
                }
                SubscriptionFramePosition.MIDDLE_NODE -> {
                    strokePath.moveTo(left, 0f)
                    strokePath.lineTo(left, h)
                    strokePath.moveTo(right, 0f)
                    strokePath.lineTo(right, h)
                }
                SubscriptionFramePosition.LAST_NODE -> {
                    strokePath.moveTo(left, 0f)
                    strokePath.lineTo(left, bottom - r)
                    strokePath.arcTo(
                        rect = Rect(left, bottom - 2 * r, left + 2 * r, bottom),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false
                    )
                    strokePath.lineTo(right - r, bottom)
                    strokePath.arcTo(
                        rect = Rect(right - 2 * r, bottom - 2 * r, right, bottom),
                        startAngleDegrees = 90f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false
                    )
                    strokePath.lineTo(right, 0f)
                }
            }

            // Delicate specular light matching liquidGlass in GlassEffect
            val specularLight = androidx.compose.ui.graphics.lerp(Color.White, colors.glassSpecular, 0.06f)

            // Subtle surface reflection over the header
            val headerSurfaceSheen = Brush.verticalGradient(
                0f to specularLight.copy(alpha = if (colors.isDark) 0.08f else 0.14f),
                0.24f to specularLight.copy(alpha = if (colors.isDark) 0.02f else 0.04f),
                0.55f to Color.Transparent
            )

            // Delicate top-left corner radial blik (блик) matching collapsed card
            val cornerBlik = Brush.radialGradient(
                listOf(
                    specularLight.copy(alpha = if (colors.isDark) 0.08f else 0.12f),
                    Color.Transparent
                ),
                center = Offset(w * 0.12f, -h * 0.32f),
                radius = minOf(w * 0.85f, h * 1.7f).coerceAtLeast(1f)
            )

            // Delicate hairline edge gleam
            val rimGleam = Brush.linearGradient(
                listOf(
                    specularLight.copy(alpha = 0.09f),
                    specularLight.copy(alpha = 0.02f),
                    specularLight.copy(alpha = 0.06f)
                ),
                start = Offset.Zero,
                end = Offset(w, h)
            )

            // Restrained horizontal top sheen
            val topSheenBrush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.20f to specularLight.copy(alpha = 0.16f),
                0.50f to specularLight.copy(alpha = 0.10f),
                0.80f to specularLight.copy(alpha = 0.03f),
                1f to Color.Transparent
            )

            val topSheenPath = Path().apply {
                moveTo(left + r, top)
                lineTo(right - r, top)
            }

            val strokeStyle = Stroke(width = strokePx)

            onDrawWithContent {
                if (position == SubscriptionFramePosition.HEADER) {
                    drawRect(headerSurfaceSheen)
                    drawRect(cornerBlik)
                }

                drawContent()

                when (position) {
                    SubscriptionFramePosition.HEADER -> {
                        drawPath(strokePath, brush = rimGleam, style = Stroke(width = strokePx * 1.5f))
                        drawPath(strokePath, color = frameBorderColor, style = strokeStyle)
                        drawPath(topSheenPath, brush = topSheenBrush, style = Stroke(width = strokePx * 1.2f))
                        drawLine(
                            color = dividerColor,
                            start = Offset(left + 12.dp.toPx(), h - halfStroke),
                            end = Offset(right - 12.dp.toPx(), h - halfStroke),
                            strokeWidth = 0.8.dp.toPx()
                        )
                    }
                    SubscriptionFramePosition.MIDDLE_NODE -> {
                        drawPath(strokePath, color = frameBorderColor, style = strokeStyle)
                    }
                    SubscriptionFramePosition.LAST_NODE -> {
                        drawPath(strokePath, brush = rimGleam, style = Stroke(width = strokePx * 1.3f))
                        drawPath(strokePath, color = frameBorderColor, style = strokeStyle)
                    }
                }
            }
        }
}

/**
 * Server list section rendering cards with animated placement and optional subscription grouping
 */
@Composable
internal fun ServerListSection(
    servers: List<ServerNode>,
    selectedServer: ServerNode?,
    isAutoBalancerVisible: Boolean,
    isPinging: Boolean,
    isGrouped: Boolean = false,
    expandedGroups: Set<String> = emptySet(),
    onToggleGroupCollapse: (String) -> Unit = {},
    subscriptionsMap: Map<String, SubscriptionEntity> = emptyMap(),
    onToggleSubEnabled: ((String, Boolean) -> Unit)? = null,
    onSubscriptionDetails: (SubscriptionEntity) -> Unit = {},
    onSelectServer: (String) -> Unit,
    onEditServer: (ServerNode) -> Unit,
    onDeleteServer: (String) -> Unit,
    onShareServer: (ServerNode) -> Unit = {},
    viewModel: MainViewModel,
    onAddClick: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selectedIdsForDeletion: Set<String> = emptySet(),
    onToggleSelection: (String) -> Unit = {},
    onEnterSelectionMode: ((String?) -> Unit)? = null,
    contentTopInset: androidx.compose.ui.unit.Dp = 0.dp,
    topContent: @Composable () -> Unit = {}
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val pingResources = com.danieldk.splatevpn.ui.theme.LocalAppResources.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val disabledNodeIds by viewModel.disabledServerIds.collectAsState()
    val updatingIds by viewModel.updatingSubscriptionIds.collectAsState()
    var pingingGroupIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(isPinging) { if (!isPinging) pingingGroupIds = emptySet() }
    val pingMode by viewModel.pingMode.collectAsState()
    val isRealDelay = pingMode.lowercase() in listOf("real_delay", "http", "real")

    if (servers.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = contentTopInset)) {
        topContent()
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
        }
    } else {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val activePingIds by viewModel.activePingIds.collectAsState()

        val groupedServers by produceState<List<ServerGroup>>(emptyList(), servers, subscriptionsMap, isGrouped, strings) {
            value = withContext(Dispatchers.Default) {
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
                        { it.id != com.danieldk.splatevpn.SplateConstants.PRESET_SUB_SPONSOR_ID },
                        { if (it.isPersonal) 0 else if (!it.isPreset) 1 else 2 },
                        { it.title }
                    )
                )
            }
            }
        }

        var menuServer by remember { mutableStateOf<ServerNode?>(null) }
        val animationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled
        val itemSpring = remember {
            spring<IntOffset>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        }
        val fadeSpring = remember {
            spring<Float>(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        }

        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(if (isGrouped) 0.dp else 8.dp),
            contentPadding = PaddingValues(top = contentTopInset, bottom = 16.dp + LocalBottomDockInset.current)
        ) {
            item(key = "server_list_preamble", contentType = "preamble") {
                topContent()
                if (isGrouped) Spacer(Modifier.height(8.dp))
            }
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
                    if (isGrouped) Spacer(Modifier.height(8.dp))
                }
            }

            if (isGrouped) {
                groupedServers.forEach { group ->
                    val isCollapsed = group.id !in expandedGroups
                    val subEntity = group.subId?.let { subscriptionsMap[it] }
                    val isSubEnabled = if (group.isPersonal) viewModel.isPersonalFolderEnabled(disabledNodeIds) else subEntity?.enabled ?: true
                    val hasVisibleServers = !isCollapsed && isSubEnabled && group.servers.isNotEmpty()
                    val groupAccent = if (!isSubEnabled) colors.textTertiary
                        else if (group.isPersonal) colors.accentEmerald
                        else if (group.isPreset) colors.accentCyan
                        else colors.accentAmber

                    if (!hasVisibleServers) {
                        item(key = "group_header_${group.id}", contentType = "header") {
                            val itemModifier = if (animationsEnabled) {
                                Modifier.animateItem(
                                    fadeInSpec = fadeSpring,
                                    placementSpec = itemSpring,
                                    fadeOutSpec = fadeSpring
                                )
                            } else Modifier
                            Box(modifier = itemModifier.fillMaxWidth()) {
                                SubscriptionGroupHeader(
                                    group = group,
                                    subscription = subEntity,
                                    onDetails = { subEntity?.let(onSubscriptionDetails) },
                                    isUpdating = group.subId in updatingIds,
                                    onRefresh = { group.subId?.let { id -> viewModel.updateSubscription(id) { result ->
                                        Toast.makeText(context, result.exceptionOrNull()?.localizedMessage ?: strings.refresh, Toast.LENGTH_SHORT).show()
                                    } } },
                                    isCollapsed = true,
                                    isEnabled = isSubEnabled,
                                    isPinging = isPinging && (group.id in pingingGroupIds || group.servers.any { it.id in activePingIds }),
                                    pingEnabled = !isPinging,
                                    onPing = {
                                        pingingGroupIds = pingingGroupIds + group.id
                                        viewModel.testServersPing(group.servers.filter { it.id !in disabledNodeIds }) { summary ->
                                            pingingGroupIds = pingingGroupIds - group.id
                                            Toast.makeText(context, pingResources.getString(com.danieldk.splatevpn.R.string.server_ping_result, group.title, summary.checked, summary.available), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onStopPing = { viewModel.cancelPingChecks(); pingingGroupIds = emptySet() },
                                    onToggleCollapse = { onToggleGroupCollapse(group.id) },
                                    onToggleEnabled = if (group.subId != null && onToggleSubEnabled != null) {
                                        { enabled -> onToggleSubEnabled(group.subId, enabled) }
                                    } else if (group.isPersonal) { enabled -> viewModel.setPersonalFolderEnabled(enabled) } else null,
                                    onStartBatchDelete = if (group.isPersonal && onEnterSelectionMode != null && !selectionMode) {
                                        { onEnterSelectionMode(null) }
                                    } else null,
                                    inGroupFrame = false
                                )
                            }
                            Spacer(itemModifier.height(8.dp))
                        }
                    } else {
                        item(key = "group_header_${group.id}", contentType = "header") {
                            val itemModifier = if (animationsEnabled) {
                                Modifier.animateItem(
                                    fadeInSpec = fadeSpring,
                                    placementSpec = itemSpring,
                                    fadeOutSpec = fadeSpring
                                )
                            } else Modifier
                            Box(
                                modifier = itemModifier
                                    .fillMaxWidth()
                                    .subscriptionGroupFrame(
                                        position = SubscriptionFramePosition.HEADER,
                                        accentColor = groupAccent
                                    )
                            ) {
                                SubscriptionGroupHeader(
                                    group = group,
                                    subscription = subEntity,
                                    onDetails = { subEntity?.let(onSubscriptionDetails) },
                                    isUpdating = group.subId in updatingIds,
                                    onRefresh = { group.subId?.let { id -> viewModel.updateSubscription(id) { result ->
                                        Toast.makeText(context, result.exceptionOrNull()?.localizedMessage ?: strings.refresh, Toast.LENGTH_SHORT).show()
                                    } } },
                                    isCollapsed = false,
                                    isEnabled = isSubEnabled,
                                    isPinging = isPinging && (group.id in pingingGroupIds || group.servers.any { it.id in activePingIds }),
                                    pingEnabled = !isPinging,
                                    onPing = {
                                        pingingGroupIds = pingingGroupIds + group.id
                                        viewModel.testServersPing(group.servers.filter { it.id !in disabledNodeIds }) { summary ->
                                            pingingGroupIds = pingingGroupIds - group.id
                                            Toast.makeText(context, pingResources.getString(com.danieldk.splatevpn.R.string.server_ping_result, group.title, summary.checked, summary.available), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onStopPing = { viewModel.cancelPingChecks(); pingingGroupIds = emptySet() },
                                    onToggleCollapse = { onToggleGroupCollapse(group.id) },
                                    onToggleEnabled = if (group.subId != null && onToggleSubEnabled != null) {
                                        { enabled -> onToggleSubEnabled(group.subId, enabled) }
                                    } else if (group.isPersonal) { enabled -> viewModel.setPersonalFolderEnabled(enabled) } else null,
                                    onStartBatchDelete = if (group.isPersonal && onEnterSelectionMode != null && !selectionMode) {
                                        { onEnterSelectionMode(null) }
                                    } else null,
                                    inGroupFrame = true
                                )
                            }
                        }

                        itemsIndexed(
                            items = group.servers,
                            key = { _, server -> server.id },
                            contentType = { _, _ -> "server_node" }
                        ) { index, server ->
                            val isLast = index == group.servers.lastIndex
                            val framePos = if (isLast) SubscriptionFramePosition.LAST_NODE else SubscriptionFramePosition.MIDDLE_NODE
                            val topPad = if (index == 0) 6.dp else 3.dp
                            val bottomPad = if (isLast) 8.dp else 3.dp
                            val itemModifier = if (animationsEnabled) {
                                Modifier.animateItem(
                                    fadeInSpec = fadeSpring,
                                    placementSpec = itemSpring,
                                    fadeOutSpec = fadeSpring
                                )
                            } else Modifier
                            Box(
                                modifier = itemModifier
                                    .fillMaxWidth()
                                    .subscriptionGroupFrame(
                                        position = framePos,
                                        accentColor = groupAccent
                                    )
                                    .padding(
                                        start = 8.dp,
                                        end = 8.dp,
                                        top = topPad,
                                        bottom = bottomPad
                                    )
                            ) {
                                ServerCard(
                                    server = server,
                                    nodeEnabled = viewModel.isNodeEnabled(server),
                                    isSelected = selectedServer?.id == server.id,
                                    isTestingPing = server.id in activePingIds,
                                    onTestPing = {
                                        viewModel.testServerPing(server) { delay ->
                                            val modeDesc = if (isRealDelay) "Real Delay (HTTP 204)" else "TCP Ping"
                                            if (delay > 0) {
                                                Toast.makeText(context, "«${server.name}»: $modeDesc $delay ms", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    String.format(
                                                        strings.localized("«%s»: Ошибка (%s недоступен)", "\"%s\": Error (%s unavailable)"),
                                                        server.name,
                                                        modeDesc
                                                    ),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    },
                                    onSelect = { onSelectServer(server.id) },
                                    onEdit = { onEditServer(server) },
                                    protectedSource = isProtectedWtpn(server.subId, subscriptionsMap[server.subId]?.url),
                                    onMoreMenu = { menuServer = server },
                                    inGroup = true,
                                    selectionMode = selectionMode,
                                    isMarkedForDeletion = server.id in selectedIdsForDeletion,
                                    onToggleSelection = { onToggleSelection(server.id) },
                                    onLongClick = if (group.isPersonal && onEnterSelectionMode != null && !selectionMode) {
                                        { onEnterSelectionMode(server.id) }
                                    } else null
                                )
                            }
                        }

                        item(key = "group_spacer_${group.id}", contentType = "spacer") {
                            val itemModifier = if (animationsEnabled) {
                                Modifier.animateItem(
                                    fadeInSpec = fadeSpring,
                                    placementSpec = itemSpring,
                                    fadeOutSpec = fadeSpring
                                )
                            } else Modifier
                            Spacer(itemModifier.height(10.dp))
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
                        nodeEnabled = viewModel.isNodeEnabled(server),

                        isSelected = selectedServer?.id == server.id,
                        isTestingPing = server.id in activePingIds,
                        onTestPing = {

                            viewModel.testServerPing(server) { delay ->

                                val modeDesc = if (isRealDelay) "Real Delay (HTTP 204)" else "TCP Ping"
                                if (delay > 0) {
                                    Toast.makeText(context, "«${server.name}»: $modeDesc $delay ms", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        String.format(
                                            strings.localized("«%s»: Ошибка (%s недоступен)", "\"%s\": Error (%s unavailable)"),
                                            server.name,
                                            modeDesc
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        },
                        onSelect = { onSelectServer(server.id) },
                        onEdit = { onEditServer(server) },
                                protectedSource = isProtectedWtpn(server.subId, subscriptionsMap[server.subId]?.url),
                        onMoreMenu = { menuServer = server },
                        selectionMode = selectionMode,
                        isMarkedForDeletion = server.id in selectedIdsForDeletion,
                        onToggleSelection = { onToggleSelection(server.id) },
                        onLongClick = if (
                            (server.subId.isBlank() || server.subId == "custom" || server.subId == "builtin") &&
                            onEnterSelectionMode != null &&
                            !selectionMode
                        ) {
                            { onEnterSelectionMode(server.id) }
                        } else null
                    )
                }
            }
        }

        // Hoisted Single Action Dialog for selected server (eliminates 200 embedded DropdownMenus)
        val currentServer = menuServer
        if (currentServer != null) {
            val protected = isProtectedWtpn(currentServer.subId, subscriptionsMap[currentServer.subId]?.url)
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
                        if (!protected) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .tvFocusOutline().clickable {
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
                                    .tvFocusOutline().clickable {
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

                        }
                        val isPersonalServer = currentServer.subId.isBlank() || currentServer.subId == "custom" || currentServer.subId == "builtin"
                        if (isPersonalServer && onEnterSelectionMode != null && !selectionMode) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .tvFocusOutline().clickable {
                                        val id = currentServer.id
                                        menuServer = null
                                        onEnterSelectionMode(id)
                                    }
                                    .padding(vertical = 11.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Checklist, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    strings.localized("Выбрать для удаления", "Select to delete"),
                                    color = colors.textPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .tvFocusOutline().clickable {
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

/** Compact identity and metadata rows; long names can use a second line. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ServerCard(
    modifier: Modifier = Modifier,
    server: ServerNode,
    isSelected: Boolean,
    isTestingPing: Boolean = false,
    onTestPing: () -> Unit = {},
    onSelect: () -> Unit,
    nodeEnabled: Boolean = true,
    onEdit: () -> Unit,
    onMoreMenu: () -> Unit,
    protectedSource: Boolean = isProtectedWtpn(server.subId),
    inGroup: Boolean = false,
    selectionMode: Boolean = false,
    isMarkedForDeletion: Boolean = false,
    onToggleSelection: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val haptic = LocalHapticFeedback.current
    val (displayName, description) = remember(server.protocol, server.name, server.rawLink, server.fullConfigJson) {
        if (server.protocol.equals("vless", ignoreCase = true)) {
            val (name, legacyDescription) = LinkParser.splitVlessLabel(server.name)
            name to (LinkParser.vlessServerDescription(server.rawLink) ?: legacyDescription
                ?: LinkParser.jsonServerDescription(server.fullConfigJson))
        } else server.name to LinkParser.jsonServerDescription(server.fullConfigJson)
    }
    val protoColor = getProtocolColor(server.protocol, colors)
    val isPreset = server.subId in com.danieldk.splatevpn.SplateConstants.PRESET_SUB_IDS
    val isFromSubscription = server.subId.isNotBlank() && server.subId !in setOf("custom", "builtin")
    val origin = when {
        isPreset -> String.format(strings.uiPresetTag, subscriptionDisplayName(server.subId, server.subName, strings).ifBlank { "VPN" })
        isFromSubscription -> String.format(strings.uiSubscriptionTag, subscriptionDisplayName(server.subId, server.subName, strings).ifBlank { "PRO" })
        else -> strings.uiPersonalTag
    }
    val cardRadius = if (inGroup) 14 else 18
    val cardBorder = when {
        selectionMode && isMarkedForDeletion -> colors.accentCrimson.copy(alpha = 0.75f)
        isTestingPing -> colors.accentAmber
        isSelected -> colors.accentCyan
        else -> null
    }
    val cardModifier = modifier
        .fillMaxWidth()
        .liquidGlass(
            cornerRadius = cardRadius,
            accentBorder = cardBorder,
            innerSheen = true
        )
        .alpha(if (nodeEnabled || selectionMode) 1f else .55f)
        .tvFocusOutline()
        .then(
            when {
                selectionMode -> Modifier.clickable(role = Role.Checkbox, onClick = onToggleSelection)
                onLongClick != null -> Modifier.combinedClickable(
                    onClick = { if (nodeEnabled) onSelect() },
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
                else -> Modifier.clickable(enabled = nodeEnabled, onClick = onSelect)
            }
        )
        .padding(horizontal = 12.dp, vertical = 8.dp)
    Column(cardModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(origin, color = colors.textSecondary, fontSize = 9.sp, lineHeight = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            if (!selectionMode) {
                ServerPingBadge(server.ping, isTestingPing, onTestPing)
                if (!protectedSource) IconButton(onClick = onEdit, modifier = Modifier.size(28.dp).testTag("serverEdit:${server.id}")) {
                    Icon(Icons.Default.Edit, strings.edit, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
                }
                IconButton(onClick = onMoreMenu, modifier = Modifier.size(28.dp).testTag("serverMore:${server.id}")) {
                    Icon(Icons.Default.MoreVert, strings.uiActions, tint = colors.textSecondary, modifier = Modifier.size(17.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectionMode) {
                Checkbox(
                    checked = isMarkedForDeletion,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.accentCrimson,
                        uncheckedColor = colors.borderMedium,
                        checkmarkColor = contentColorOn(colors.accentCrimson)
                    ),
                    modifier = Modifier.size(20.dp).testTag("serverSelectCheckbox:${server.id}")
                )
            } else {
                Box(Modifier.size(18.dp).clip(CircleShape)
                    .border(1.5.dp, if (isSelected) colors.accentCyan else colors.borderMedium, CircleShape), contentAlignment = Alignment.Center) {
                    if (isSelected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accentCyan))
                }
            }
            Text(if (protectedSource && server.address.isNotBlank()) displayName.replace(server.address, "••••", ignoreCase = true) else displayName, color = colors.textPrimary, fontSize = 13.5.sp, lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (server.isRetained) Text(strings.localized("Резерв · нет в текущей подписке", "Reserved · missing from current subscription"),
            color = colors.accentAmber, style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TagPill(server.protocol.uppercase(), protoColor)
            if (server.security.isNotBlank() && server.security != "none") TagPill(server.security.uppercase(), colors.accentEmerald)
            if (server.network.isNotBlank()) TagPill(server.network.uppercase(), colors.accentViolet)
            if (description != null) TagPill(description.take(32), colors.textSecondary)
            if (!protectedSource) Text("${server.address}:${server.port}", color = colors.textSecondary, fontSize = 10.sp,
                lineHeight = 16.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ServerPingBadge(ping: Long, testing: Boolean, onTest: () -> Unit) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val tint = when {
        testing -> colors.accentCyan
        ping in 1..120 -> colors.accentEmerald
        ping in 121..250 -> colors.accentAmber
        ping != 0L -> colors.accentCrimson
        else -> colors.textTertiary
    }
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.heightIn(min = 30.dp).clip(shape)
        .background(colors.glassBorderLight.copy(alpha = 0.10f))
        .border(0.5.dp, colors.glassBorderLight.copy(alpha = 0.30f), shape)
        .tvFocusOutline().clickable(enabled = !testing, onClick = onTest)
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            testing -> {
                CircularProgressIndicator(Modifier.size(12.dp), color = tint, strokeWidth = 1.5.dp)
                Text(strings.pinging, color = colors.textPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            ping < 0 -> Icon(Icons.Default.ErrorOutline, strings.pingNoResponse, tint = tint, modifier = Modifier.size(18.dp))
            else -> {
                if (ping > 0) ServerSignalBars(ping)
                Text(if (ping > 0) "$ping ms" else "—", color = tint, fontSize = 12.sp, lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
            lineHeight = 12.sp,
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
    val boltRotation = if (isPinging && LocalUiPerformanceSettings.current.animationsEnabled) {
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
