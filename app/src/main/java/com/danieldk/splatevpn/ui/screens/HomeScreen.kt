package com.danieldk.splatevpn.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.glassmorphism
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
    val uploadSpeed by viewModel.uploadSpeed.collectAsState()
    val downloadSpeed by viewModel.downloadSpeed.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val routingMode by viewModel.routingMode.collectAsState()
    val countryFlag by viewModel.countryFlag.collectAsState()
    val countryName by viewModel.countryName.collectAsState()
    val currentIp by viewModel.currentIp.collectAsState()
    val isDetectingIp by viewModel.isDetectingIp.collectAsState()
    val isAutoSelect by viewModel.isAutoSelect.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()

    var showLogsDialog by remember { mutableStateOf(false) }

    // Live session duration timer
    var sessionSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            sessionSeconds = 0L
            while (isActive) {
                delay(1000)
                sessionSeconds++
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

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        BackgroundDark,
                        BackgroundDeep,
                        Color(0xFF020408)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Tactical Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .border(1.dp, if (isConnected) AccentEmerald.copy(alpha = 0.5f) else BorderSubtle, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (isConnected) AccentEmerald else AccentCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = "SPLATE VPN",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = "CORE 26.9 // ENCRYPTED OUTBOUND",
                            color = TextTertiary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }

                // Quick Terminal / Diagnostics Button
                IconButton(
                    onClick = { showLogsDialog = true },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Logs",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status Badge & Live Timer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .glassmorphism(
                        cornerRadius = 20,
                        accentBorder = if (isConnected) AccentEmerald else null,
                        backgroundColor = SurfaceDark.copy(alpha = 0.7f)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) AccentEmerald else AccentCrimson)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "ENCRYPTED • $sessionTimeFormatted" else "STANDBY • UNPROTECTED",
                    color = if (isConnected) AccentEmerald else TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            // Egress IP & Location Readout (When Connected)
            if (isConnected) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassmorphism(
                            cornerRadius = 14,
                            backgroundColor = SurfaceElevated.copy(alpha = 0.5f)
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (countryFlag.isNotBlank()) countryFlag else "🌐",
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (countryName.isNotBlank()) countryName.uppercase() else if (isDetectingIp) "RESOLVING LOCATION..." else "PROTECTED GATEWAY",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        if (currentIp.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "EGRESS: $currentIp",
                                color = AccentCyan,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    if (isDetectingIp) {
                        CircularProgressIndicator(
                            color = AccentCyan,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = AccentEmerald,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Central Cyber Power Core
            CyberPowerCore(
                isConnected = isConnected,
                onClick = onToggleConnect
            )

            Spacer(modifier = Modifier.height(30.dp))

            // Real-time Telemetry HUD
            TelemetryHUD(
                isConnected = isConnected,
                latency = if ((selectedServer?.ping ?: 0L) > 0) "${selectedServer?.ping} ms" else "---",
                pingMs = selectedServer?.ping ?: 0L,
                downSpeed = downloadSpeed,
                upSpeed = uploadSpeed
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Routing Profile Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ROUTING PROFILE",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TacticalModeChip(
                    title = "SMART ROUTE",
                    subtitle = "Bypass RU Services",
                    tag = "SPLIT-TUNNEL",
                    isSelected = routingMode == "smart_ru",
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.setRoutingMode("smart_ru")
                }

                TacticalModeChip(
                    title = "GLOBAL TUNNEL",
                    subtitle = "Strict 100% Proxy",
                    tag = "FULL ENCAP",
                    isSelected = routingMode == "global",
                    modifier = Modifier.weight(1f)
                ) {
                    viewModel.setRoutingMode("global")
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Active Server Node Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ACTIVE GATEWAY",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                // Auto-Select Action
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isAutoSelect) AccentCyan.copy(alpha = 0.15f) else SurfaceDark)
                        .border(1.dp, if (isAutoSelect) AccentCyan.copy(alpha = 0.4f) else BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable { viewModel.autoSelectFastestServer() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isAutoSelect) AccentCyan else TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPinging) "PINGING..." else "FASTEST",
                        color = if (isAutoSelect) AccentCyan else TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassmorphism(
                        cornerRadius = 16,
                        accentBorder = if (isConnected) BorderMedium else null
                    )
                    .clickable { onNavigateToServers() }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BackgroundDark)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = AccentCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedServer?.name ?: "No Server Selected",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = selectedServer?.let {
                            "${it.protocol.uppercase()} // ${it.network.uppercase()} // ${it.address}:${it.port}"
                        } ?: "Tap to choose a node",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (selectedServer != null) {
                    val ping = selectedServer!!.ping
                    val pingColor = when {
                        ping in 1..120 -> AccentEmerald
                        ping in 121..250 -> AccentAmber
                        ping > 250 -> AccentCrimson
                        else -> TextTertiary
                    }
                    Text(
                        text = if (ping > 0) "$ping ms" else "---",
                        color = pingColor,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showLogsDialog) {
        LogsDialog(
            viewModel = viewModel,
            onDismiss = { showLogsDialog = false }
        )
    }
}

@Composable
fun CyberPowerCore(
    isConnected: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "core_anim")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (isConnected) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val breathingGlow by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = if (isConnected) 0.85f else 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    val coreColor by animateColorAsState(
        targetValue = if (isConnected) AccentEmerald else AccentCyan,
        animationSpec = tween(400),
        label = "core_color"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(190.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        // Outer Radar / Dashed Ring
        Canvas(
            modifier = Modifier
                .size(186.dp)
                .rotate(rotationAngle)
        ) {
            drawCircle(
                color = if (isConnected) coreColor.copy(alpha = breathingGlow * 0.7f) else BorderSubtle.copy(alpha = 0.6f),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 16f), 0f)
                )
            )
        }

        // Concentric ambient halo
        if (isConnected) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                coreColor.copy(alpha = breathingGlow * 0.22f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Inner Tactical Core Disc
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(136.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SurfaceElevated,
                            BackgroundDark
                        )
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            if (isConnected) coreColor else BorderMedium,
                            if (isConnected) coreColor.copy(alpha = 0.3f) else BorderSubtle
                        )
                    ),
                    shape = CircleShape
                )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (isConnected) coreColor else TextSecondary,
                    modifier = Modifier.size(42.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isConnected) "SECURED" else "CONNECT",
                    color = if (isConnected) coreColor else TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
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
    upSpeed: String
) {
    val pingStatusColor = when {
        !isConnected || pingMs <= 0 -> TextTertiary
        pingMs in 1..120 -> AccentEmerald
        pingMs in 121..250 -> AccentAmber
        else -> AccentCrimson
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassmorphism(
                cornerRadius = 16,
                backgroundColor = SurfaceDark.copy(alpha = 0.9f)
            )
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // LATENCY
        TelemetryItem(
            label = "LATENCY",
            value = if (isConnected) latency else "---",
            statusDotColor = pingStatusColor
        )

        Box(
            modifier = Modifier
                .height(30.dp)
                .width(1.dp)
                .background(BorderSubtle.copy(alpha = 0.6f))
        )

        // DOWNLINK
        TelemetryItem(
            label = "DOWNLINK",
            value = if (isConnected) downSpeed else "0 KB/s",
            icon = Icons.Default.ArrowDownward,
            iconTint = AccentCyan
        )

        Box(
            modifier = Modifier
                .height(30.dp)
                .width(1.dp)
                .background(BorderSubtle.copy(alpha = 0.6f))
        )

        // UPLINK
        TelemetryItem(
            label = "UPLINK",
            value = if (isConnected) upSpeed else "0 KB/s",
            icon = Icons.Default.ArrowUpward,
            iconTint = Color(0xFFA78BFA)
        )
    }
}

@Composable
fun TelemetryItem(
    label: String,
    value: String,
    statusDotColor: Color? = null,
    icon: ImageVector? = null,
    iconTint: Color = TextSecondary
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (statusDotColor != null) {
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
                    tint = iconTint,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
            }

            Text(
                text = label,
                color = TextSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = value,
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun TacticalModeChip(
    title: String,
    subtitle: String,
    tag: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) AccentCyan else BorderSubtle
    val bgColor = if (isSelected) AccentCyan.copy(alpha = 0.08f) else SurfaceDark

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = if (isSelected) AccentCyan else TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )

            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) AccentCyan else BorderMedium)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subtitle,
            color = TextSecondary,
            fontSize = 10.sp,
            lineHeight = 13.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = tag,
            color = if (isSelected) AccentCyan.copy(alpha = 0.8f) else TextTertiary,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}
