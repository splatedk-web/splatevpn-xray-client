package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import com.danieldk.splatevpn.R
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.LiquidAmbientBackground
import com.danieldk.splatevpn.ui.components.frostedGlass
import com.danieldk.splatevpn.ui.components.LocalGlassBackdrop
import com.danieldk.splatevpn.ui.components.LocalGlassTransparency
import com.danieldk.splatevpn.ui.components.rememberDockBackdrop
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.*

/**
 * Высота нижней плавающей панели вместе с системным inset-ом навигации.
 * Контент рисуется под панелью с мягким затемнением, а прокручиваемые списки
 * используют это значение как нижний отступ, чтобы последний элемент можно было поднять выше панели.
 */
val LocalBottomDockInset = compositionLocalOf { 0.dp }

enum class AppTab(val icon: ImageVector) {
    HOME(Icons.Default.Shield),
    SERVERS(Icons.Default.Dns),
    APPS(Icons.Default.Apps),
    SETTINGS(Icons.Default.Tune);

    fun getTitle(strings: AppStrings): String = when (this) {
        HOME -> strings.tabControl
        SERVERS -> strings.tabNodes
        APPS -> strings.tabRouting
        SETTINGS -> strings.tabConfig
    }
}

internal fun visibleAppTabs(mode: com.danieldk.splatevpn.data.AppUiMode): List<AppTab> =
    if (mode.isSimple) listOf(AppTab.HOME, AppTab.SETTINGS) else AppTab.entries

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onToggleConnect: () -> Unit
) {
    val currentTab by viewModel.selectedTab.collectAsState()
    val uiMode by viewModel.uiMode.collectAsState()
    val visibleTabs = remember(uiMode) { visibleAppTabs(uiMode) }
    var settingsOpenRequest by remember { mutableIntStateOf(0) }
    val isStartupLoading by viewModel.isStartupLoading.collectAsState()
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    com.danieldk.splatevpn.ui.components.SettingsRecoveryDialog(viewModel)
    val glassTransparency by viewModel.glassTransparency.collectAsState()
    val glassBlurEnabled by viewModel.glassBlurEnabled.collectAsState()
    val vpnState by viewModel.vpnState.collectAsState()
    com.danieldk.splatevpn.ui.components.DeviceTransferImportDialog(viewModel.deviceTransferManager,
        vpnState !in setOf(com.danieldk.splatevpn.vpn.VpnState.DISCONNECTED, com.danieldk.splatevpn.vpn.VpnState.FAILED))
    val uiConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasLeanback = remember(context) { context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) }
    if (isTelevisionUi(uiConfiguration.uiMode, hasLeanback)) {
        CompositionLocalProvider(com.danieldk.splatevpn.ui.components.LocalTvControls provides true) {
            TvMainScreen(viewModel, onToggleConnect)
        }
        return
    }

    val ambientGlowColor = when (vpnState) {
        com.danieldk.splatevpn.vpn.VpnState.CONNECTED -> colors.accentConnected
        com.danieldk.splatevpn.vpn.VpnState.FAILED -> colors.accentCrimson
        com.danieldk.splatevpn.vpn.VpnState.SELECTING_SERVER,
        com.danieldk.splatevpn.vpn.VpnState.STARTING_CORE,
        com.danieldk.splatevpn.vpn.VpnState.CONNECTING,
        com.danieldk.splatevpn.vpn.VpnState.HEALTH_CHECKING,
        com.danieldk.splatevpn.vpn.VpnState.RECONNECTING -> colors.accentAmber
        else -> colors.accentCyan
    }

    val isConnected = vpnState == com.danieldk.splatevpn.vpn.VpnState.CONNECTED
    val isConnecting = vpnState == com.danieldk.splatevpn.vpn.VpnState.SELECTING_SERVER ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.STARTING_CORE ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.CONNECTING ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.HEALTH_CHECKING ||
            vpnState == com.danieldk.splatevpn.vpn.VpnState.RECONNECTING

    LiquidAmbientBackground(
        glassTransparency = glassTransparency,
        glassBlurEnabled = glassBlurEnabled,
        accentGlow = ambientGlowColor,
        isConnected = isConnected,
        isConnecting = isConnecting
    ) {
        val (dockBackdrop, captureDockContent) = rememberDockBackdrop()
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                bottomBar = {
                    AnimatedVisibility(
                        visible = !isStartupLoading,
                        enter = fadeIn(tween(250, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(150))
                    ) {
                        CompositionLocalProvider(LocalGlassBackdrop provides dockBackdrop) {
                        FloatingLiquidDock(
                            currentTab = currentTab,
                            tabs = visibleTabs,
                            onSelectTab = { tab ->
                                if (tab == AppTab.SETTINGS) settingsOpenRequest++
                                viewModel.selectTab(tab)
                            },
                            colors = colors,
                            strings = strings
                        )
                        }
                    }
                },
                containerColor = Color.Transparent
            ) { paddingValues ->
                // Контент занимает весь экран и рисуется под плавающей панелью и её градиентом.
                // Нижний отступ для списков передаётся через LocalBottomDockInset и масштабируется под крупный шрифт.
                val fontScaleExtra = ((androidx.compose.ui.platform.LocalDensity.current.fontScale - 1f).coerceAtLeast(0f) * 20f).dp
                val dockBottomInset = maxOf(paddingValues.calculateBottomPadding(), 84.dp) + 12.dp + fontScaleExtra
                CompositionLocalProvider(
                    LocalBottomDockInset provides dockBottomInset
                ) {
                    // Save state without keeping hidden screens and their animations running.
                    val tabStateHolder = rememberSaveableStateHolder()
                    val tabsToShow = listOf(currentTab)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(captureDockContent)
                            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    ) {
                        tabsToShow.forEach { tab ->
                            key(tab) {
                                tabStateHolder.SaveableStateProvider(tab) {
                                    TabHostScreen(
                                        tab = tab,
                                        isSelected = tab == currentTab,
                                        viewModel = viewModel,
                                        onToggleConnect = onToggleConnect,
                                        settingsOpenRequest = settingsOpenRequest
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Полноэкранный брендовый экран предзагрузки поверх Scaffold (закрывает навигационный док)
            AnimatedVisibility(
                visible = isStartupLoading,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(50f),
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(350, easing = FastOutSlowInEasing)) + scaleOut(
                    targetScale = 1.05f,
                    animationSpec = tween(350, easing = FastOutSlowInEasing)
                )
            ) {
                StartupLoadingOverlay(
                    title = "SPLATE VPN",
                    subtitle = strings.splashSubtitle,
                    statusText = strings.splashInitializing,
                    colors = colors
                )
            }
        }
    }
}

@Composable
private fun StartupLoadingOverlay(
    title: String,
    subtitle: String,
    statusText: String,
    colors: AppThemeColors
) {
    val transition = if (LocalUiPerformanceSettings.current.animationsEnabled) rememberInfiniteTransition(label = "startup_loading") else null
    val orbitRotation by transition?.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing)
        ),
        label = "startup_ring_rotation"
    ) ?: rememberUpdatedState(0f)
    val glowPulse by transition?.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "startup_glow_pulse"
    ) ?: rememberUpdatedState(1f)
    val shieldPulse by transition?.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "startup_shield_pulse"
    ) ?: rememberUpdatedState(1f)
    val shimmerProgress by transition?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing)
        ),
        label = "startup_shimmer"
    ) ?: rememberUpdatedState(0f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        colors.accentCyan.copy(alpha = if (colors.isDark) 0.18f else 0.12f),
                        colors.backgroundDeep.copy(alpha = 0.98f),
                        colors.backgroundDeep
                    ),
                    radius = 1100f
                )
            )
            .tvFocusOutline().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            // Эмблема со щитом и анимированной неоновой орбитой
            Box(
                modifier = Modifier.size(118.dp),
                contentAlignment = Alignment.Center
            ) {
                // Мягкое фоновое неоновое свечение (Glow Aura)
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .graphicsLayer {
                            scaleX = glowPulse
                            scaleY = glowPulse
                            alpha = 0.45f
                        }
                        .background(
                            Brush.radialGradient(
                                colors = listOf(colors.accentCyan, Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )

                // Вращающаяся неоновая орбитальная дуга
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = orbitRotation }
                ) {
                    val strokeWidth = 2.5.dp.toPx()
                    // Фоновая направляющая окружность
                    drawCircle(
                        color = colors.accentCyan.copy(alpha = 0.15f),
                        style = Stroke(width = strokeWidth)
                    )
                    // Акцентная световая дуга
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                colors.accentCyan.copy(alpha = 0.05f),
                                colors.accentCyan,
                                colors.accentSecondary
                            )
                        ),
                        startAngle = 0f,
                        sweepAngle = 120f,
                        useCenter = false,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                // Центральная подложка неонового щита под тему
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(
                            color = colors.surface.copy(alpha = 0.85f),
                            shape = CircleShape
                        )
                        .border(
                            width = 1.2.dp,
                            brush = Brush.linearGradient(
                                listOf(
                                    colors.accentCyan.copy(alpha = 0.6f),
                                    colors.accentSecondary.copy(alpha = 0.35f)
                                )
                            ),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = colors.accentCyan,
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer {
                                scaleX = shieldPulse
                                scaleY = shieldPulse
                            }
                    )
                }
            }

            // Брендинг и заголовок
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                )
                Text(
                    text = subtitle,
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.6.sp
                )
            }

            // Элегантная неоновая полоска прогресса и статус
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Тонкий неоновый трек
                val trackWidth = 160.dp
                Box(
                    modifier = Modifier
                        .width(trackWidth)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(colors.accentCyan.copy(alpha = 0.12f))
                ) {
                    val sliderWidth = 54.dp
                    val maxOffsetDp = trackWidth - sliderWidth
                    val currentOffsetDp = maxOffsetDp * shimmerProgress
                    Box(
                        modifier = Modifier
                            .offset(x = currentOffsetDp)
                            .width(sliderWidth)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        colors.accentCyan,
                                        colors.accentSecondary,
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                Text(
                    text = statusText,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Контейнер активной вкладки. Сохранённое состояние прокрутки и фильтров
 * восстанавливается SaveableStateProvider при повторном открытии вкладки.
 */
@Composable
private fun TabHostScreen(
    tab: AppTab,
    isSelected: Boolean,
    viewModel: MainViewModel,
    onToggleConnect: () -> Unit,
    settingsOpenRequest: Int
) {
    val animationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled
    val transitionAnim = remember { Animatable(if (animationsEnabled) 0f else 1f) }
    LaunchedEffect(isSelected, animationsEnabled) {
        if (!animationsEnabled) transitionAnim.snapTo(if (isSelected) 1f else 0f)
        else transitionAnim.animateTo(
            targetValue = if (isSelected) 1f else 0f,
            animationSpec = tween(durationMillis = 115, easing = FastOutSlowInEasing)
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (isSelected) 2f else 1f)
            .graphicsLayer {
                val p = transitionAnim.value
                alpha = p
                val s = 0.99f + 0.01f * p
                scaleX = s
                scaleY = s
                translationY = (1f - p) * 5.dp.toPx()
                translationX = if (!isSelected && p == 0f) 100000f else 0f
            }
            .then(
                if (!isSelected) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                } else Modifier
            )
    ) {
        when (tab) {
            AppTab.HOME -> HomeScreen(
                viewModel = viewModel,
                onToggleConnect = onToggleConnect,
                onNavigateToServers = { viewModel.openAllServersInPro() }
            )
            AppTab.SERVERS -> ServersScreen(viewModel = viewModel)
            AppTab.APPS -> PerAppScreen(viewModel = viewModel)
            AppTab.SETTINGS -> SettingsScreen(viewModel = viewModel, resetRequest = settingsOpenRequest)
        }
    }
}

/**
 * Изолированная нижняя навигационная панель (Liquid Glass Dock).
 * Физика жестов, пружинные анимации и трекинг бегунка инкапсулированы внутри компонента,
 * предотвращая ненужную рекомпозицию экранов и родительского Scaffold.
 */
@Composable
internal fun FloatingLiquidDock(
    currentTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    colors: AppThemeColors,
    strings: AppStrings,
    tabs: List<AppTab> = AppTab.entries
) {
    val activeTabs = if (tabs.isEmpty()) AppTab.entries else tabs
    val currentIndex = activeTabs.indexOf(currentTab).coerceAtLeast(0)
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestTabs by rememberUpdatedState(activeTabs)
    val selectTab by rememberUpdatedState(onSelectTab)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val transparency = LocalGlassTransparency.current
    val animationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled
    val animateDock by rememberUpdatedState(animationsEnabled)
    // Keep the navigation area legible even when glass surfaces are fully transparent.
    val glassEnabled = LocalUiPerformanceSettings.current.glassHighlightsEnabled
    val scrimScale = (1f - transparency).coerceIn(0.85f, 1f)
    val labelMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(
        fontSize = 10.sp, lineHeight = 12.sp, fontFamily = OneSans,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
        textAlign = TextAlign.Center
    )

    var isDragging by remember { mutableStateOf(false) }
    var draggedTabIndex by remember(activeTabs) { mutableIntStateOf(currentIndex) }
    val tabCount = activeTabs.size

    var innerWidthPx by remember { mutableFloatStateOf(0f) }
    val pillAnimX = remember { Animatable(0f) }

    val slotWidthPx = if (innerWidthPx > 0f) innerWidthPx / tabCount else 0f
    val slotWidthDp = with(density) { slotWidthPx.toDp() }

    // Синхронизация позиции бегунка при программной смене таба или тапе
    LaunchedEffect(currentIndex, slotWidthPx, animationsEnabled, tabCount) {
        if (slotWidthPx > 0f && !isDragging) {
            val targetX = currentIndex * slotWidthPx
            if (!animationsEnabled || pillAnimX.value == 0f && currentIndex == 0) {
                pillAnimX.snapTo(targetX)
            } else {
                pillAnimX.animateTo(
                    targetValue = targetX,
                    animationSpec = spring(
                        dampingRatio = 0.80f,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bottomDockArea")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        // Main pass reaches children first, preserving tab taps and scrubbing.
                        // Consume the surrounding padding too, so it cannot hit the list below.
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
            .background(
                Brush.verticalGradient(
                    0.0f to Color.Transparent,
                    0.28f to colors.background.copy(alpha = (if (colors.isDark) 0.45f else 0.35f) * scrimScale),
                    0.48f to colors.background.copy(alpha = if (glassEnabled) 0.88f * scrimScale else 1f),
                    1.0f to colors.background
                )
            )
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // Measure with the same width and typography as the actual labels. All tabs
        // reserve the tallest label so icons stay aligned when one title wraps.
        val labelWidth = with(density) {
            ((minOf(maxWidth, 600.dp) - 8.dp) / tabCount - 8.dp).roundToPx().coerceAtLeast(1)
        }
        val labelHeight = with(density) {
            activeTabs.maxOf { tab ->
                labelMeasurer.measure(
                    text = tab.getTitle(strings), style = labelStyle, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = labelWidth)
                ).size.height
            }.toDp()
        }
        val dockHeight = maxOf(64.dp, 48.dp + labelHeight)
        Box(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .height(dockHeight)
                .then(if (glassEnabled) Modifier.frostedGlass(cornerRadius = 24.dp, elevation = 14.dp) else {
                    val shape = RoundedCornerShape(24.dp)
                    Modifier.shadow(12.dp, shape).background(colors.surface, shape).border(1.dp, colors.borderSubtle, shape)
                })
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            // Внутренний контейнер: общее координатное пространство для бегунка и иконок
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        if (size.width > 0) {
                            innerWidthPx = size.width.toFloat()
                        }
                    }
                    .pointerInput(tabCount) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val downX = down.position.x
                                val totalW = size.width.toFloat()
                                if (totalW <= 0f) continue
                                val currentSlotW = totalW / tabCount
                                var isScrubbing = false
                                var lastHovered = latestCurrentIndex

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) {
                                        if (isScrubbing) {
                                            latestTabs.getOrNull(lastHovered)?.let(selectTab)
                                            coroutineScope.launch {
                                                if (!animateDock) pillAnimX.snapTo(lastHovered * currentSlotW)
                                                else pillAnimX.animateTo(
                                                    targetValue = lastHovered * currentSlotW,
                                                    animationSpec = spring(
                                                        dampingRatio = 0.82f,
                                                        stiffness = Spring.StiffnessMedium
                                                    )
                                                )
                                            }
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            } catch (e: Exception) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            }
                                        }
                                        isDragging = false
                                        break
                                    }

                                    val currentX = change.position.x
                                    if (!isScrubbing && abs(currentX - downX) > viewConfiguration.touchSlop) {
                                        isScrubbing = true
                                        isDragging = true
                                        try {
                                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                        } catch (e: Exception) {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }

                                    if (isScrubbing) {
                                        change.consume()
                                        val clampedPillX = (currentX - currentSlotW / 2f).coerceIn(0f, (totalW - currentSlotW).coerceAtLeast(0f))
                                        coroutineScope.launch {
                                            pillAnimX.snapTo(clampedPillX)
                                        }
                                        val hovered = ((currentX / totalW) * tabCount).toInt().coerceIn(0, tabCount - 1)
                                        if (hovered != lastHovered) {
                                            lastHovered = hovered
                                            draggedTabIndex = hovered
                                            try {
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            } catch (e: Exception) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        }
                                    }
                                }
                                isDragging = false
                            }
                        }
                    }
            ) {
                // Бегунок-капсула
                if (slotWidthPx > 0f) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(pillAnimX.value.roundToInt(), 0) }
                            .width(slotWidthDp)
                            .fillMaxHeight()
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                            .liquidGlass(cornerRadius = 18, accentBorder = colors.accentCyan,
                                borderAlpha = if (isDragging) .72f else .56f,
                                backgroundColor = colors.accentCyan.copy(alpha = if (isDragging) .22f else .14f))
                    )
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val inactiveIconColor = lerp(colors.textSecondary, colors.textPrimary, if (colors.isDark) 0.25f else 0.15f)
                    val inactiveTextColor = lerp(colors.textSecondary, colors.textPrimary, if (colors.isDark) 0.20f else 0.10f)

                    activeTabs.forEachIndexed { index, tab ->
                        val isHighlighted = if (isDragging) draggedTabIndex == index else currentTab == tab
                        val iconColor by animateColorAsState(
                            targetValue = if (isHighlighted) colors.accentCyan else inactiveIconColor,
                            animationSpec = tween(150),
                            label = "nav_icon"
                        )
                        val textColor by animateColorAsState(
                            targetValue = if (isHighlighted) colors.accentCyan else inactiveTextColor,
                            animationSpec = tween(150),
                            label = "nav_text"
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .testTag("mainTab:${tab.name}")
                                .tvFocusOutline().selectable(
                                    selected = currentTab == tab,
                                    role = Role.Tab,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onSelectTab(tab)
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = tab.getTitle(strings),
                                color = textColor,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(labelHeight),
                                style = labelStyle,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )

                        }
                    }
                }
            }
        }
    }
}
