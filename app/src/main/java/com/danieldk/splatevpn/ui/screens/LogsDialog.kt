package com.danieldk.splatevpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.danieldk.splatevpn.data.LogEntry
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.rememberHapticFeedbackManager
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import kotlinx.coroutines.launch

@Composable
fun LogsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val logs by viewModel.logsFlow.collectAsState()
    val errorLogs by viewModel.errorsFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val haptic = rememberHapticFeedbackManager()

    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current

    val allFilter = strings.logsFilterAll
    val errorsFilter = strings.logsFilterErrors
    val xrayFilter = strings.logsFilterXray
    val vpnFilter = strings.logsFilterVpn

    var selectedFilter by remember { mutableStateOf(allFilter) }
    var searchQuery by remember { mutableStateOf("") }
    var isAutoscrollEnabled by remember { mutableStateOf(true) }

    // Statistics counts
    val totalCount = logs.size
    val errorsCount = errorLogs.size
    val warningsCount = remember(logs) { logs.count { it.level.equals("WARN", ignoreCase = true) } }
    val xrayCount = remember(logs) { logs.count { it.tag.contains("Xray", ignoreCase = true) } }

    val filteredLogs = remember(logs, errorLogs, selectedFilter, searchQuery) {
        val base = when (selectedFilter) {
            errorsFilter -> errorLogs
            xrayFilter -> logs.filter { it.tag.contains("Xray", ignoreCase = true) }
            vpnFilter -> logs.filter { it.tag.contains("Splate", ignoreCase = true) || it.tag.contains("VPN", ignoreCase = true) }
            else -> logs
        }
        if (searchQuery.isBlank()) {
            base
        } else {
            val q = searchQuery.trim()
            base.filter {
                it.message.contains(q, ignoreCase = true) ||
                        it.tag.contains(q, ignoreCase = true) ||
                        it.level.contains(q, ignoreCase = true)
            }
        }
    }

    // Auto-scroll to bottom when new logs arrive (if autoscroll is enabled)
    LaunchedEffect(filteredLogs.size, isAutoscrollEnabled) {
        if (isAutoscrollEnabled && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    // Read host insets before entering the Dialog's independent window.
    val hostSafeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val hostSafeTop = hostSafeInsets.calculateTopPadding()
    val hostSafeBottom = hostSafeInsets.calculateBottomPadding()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        // Outer dismissable scrim that respects status and navigation insets
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background.copy(alpha = 0.78f))
                .padding(top = hostSafeTop, bottom = hostSafeBottom).imePadding()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 640.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.surface)
                    .liquidGlass(
                        cornerRadius = 24,
                        specularSheen = true,
                        borderAlpha = 0.90f
                    )
                    .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp)
            ) {
                // — Terminal Header —
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.accentCyan.copy(alpha = 0.15f))
                                .border(1.dp, colors.accentCyan.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = colors.accentCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = strings.logsDialogTitle,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                maxLines = 2,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "splate@engine:~/logs $ tail -f xray.log",
                                color = colors.accentCyan,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Top quick close button
                    IconButton(
                        onClick = {
                            haptic.click()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = strings.close,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // — Live Telemetry HUD Bar (Stats Pills) —
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatPill(
                        label = strings.logsStatsTotal,
                        count = totalCount,
                        accentColor = colors.accentCyan,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = strings.logsStatsErrors,
                        count = errorsCount,
                        accentColor = if (errorsCount > 0) colors.accentCrimson else colors.accentEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = strings.logsStatsWarnings,
                        count = warningsCount,
                        accentColor = if (warningsCount > 0) colors.accentAmber else colors.textSecondary,
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = "Xray",
                        count = xrayCount,
                        accentColor = colors.accentCyan,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // — Live Search Field —
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundDeep.copy(alpha = 0.7f))
                        .border(
                            1.dp,
                            if (searchQuery.isNotBlank()) colors.accentCyan.copy(alpha = 0.6f)
                            else colors.glassBorderLight.copy(alpha = 0.25f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = if (searchQuery.isNotBlank()) colors.accentCyan else colors.textTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(colors.accentCyan),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = strings.logsSearchHint,
                                        color = colors.textSecondary,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = colors.textSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { searchQuery = "" }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // — Filter Chips & Autoscroll Switch —
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        listOf(allFilter, errorsFilter, xrayFilter, vpnFilter).forEach { filter ->
                            val isSelected = selectedFilter == filter
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) colors.accentCyan.copy(alpha = 0.2f)
                                        else colors.surfaceElevated.copy(alpha = 0.5f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) colors.accentCyan.copy(alpha = 0.75f)
                                        else colors.glassBorderLight.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        haptic.tick()
                                        selectedFilter = filter
                                    }
                                    .padding(horizontal = 9.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = filter,
                                    color = if (isSelected) colors.accentCyan else colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Autoscroll Toggle
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isAutoscrollEnabled) colors.accentEmerald.copy(alpha = 0.15f)
                                else colors.surfaceElevated.copy(alpha = 0.5f)
                            )
                            .border(
                                1.dp,
                                if (isAutoscrollEnabled) colors.accentEmerald.copy(alpha = 0.4f)
                                else colors.glassBorderLight.copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                haptic.tick()
                                isAutoscrollEnabled = !isAutoscrollEnabled
                            }
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isAutoscrollEnabled) colors.accentEmerald else colors.textTertiary)
                        )
                        Text(
                            text = if (isAutoscrollEnabled) "AUTO" else "PAUSE",
                            color = if (isAutoscrollEnabled) colors.accentEmerald else colors.textTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // — Terminal Log Screen Viewport —
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.backgroundDeep.copy(alpha = 0.95f))
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    colors.glassBorderLight.copy(alpha = 0.35f),
                                    colors.glassBorderDark.copy(alpha = 0.15f)
                                )
                            ),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = null,
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = if (searchQuery.isNotBlank()) strings.uiNoMatches else strings.logsEmpty,
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            items(filteredLogs) { entry ->
                                LogEntryItem(
                                    entry = entry,
                                    onCopyLine = { line ->
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Log Entry", line))
                                        haptic.click()
                                        Toast.makeText(context, strings.logsCopiedLine, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // — Grounded Bottom Action Dock (Definitive Close & Actions) —
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Copy report button
                        Button(
                            onClick = {
                                haptic.click()
                                val report = viewModel.getDiagnosticReport()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("SplateVPN Diagnostic", report)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, strings.reportCopied, Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceElevated),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                tint = colors.accentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                strings.copyReport,
                                color = colors.textPrimary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Share button
                        OutlinedButton(
                            onClick = {
                                haptic.click()
                                val report = viewModel.getDiagnosticReport()
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, report)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "SplateVPN Diagnostic Report"))
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textPrimary),
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.glassBorderLight.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = colors.textPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                strings.shareReport,
                                color = colors.textPrimary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Clear button
                        IconButton(
                            onClick = {
                                haptic.heavy()
                                viewModel.clearLogs()
                                Toast.makeText(context, strings.logsCleared, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.accentCrimson.copy(alpha = 0.15f))
                                .border(1.dp, colors.accentCrimson.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = strings.clearLogs,
                                tint = colors.accentCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Large Prominent Close Terminal Button (Elevated above system gesture pill / "полоска")
                    Button(
                        onClick = {
                            haptic.pop()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentCyan,
                            contentColor = LocalAppThemeColors.current.onAccent
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = LocalAppThemeColors.current.onAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.logsClose,
                            color = LocalAppThemeColors.current.onAccent,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Safe clearance guaranteeing zero interference with the system navigation bar ("полоска")
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    count: Int,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppThemeColors.current

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.10f))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = count.toString(),
                color = accentColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = label,
                color = colors.textSecondary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun LogEntryItem(
    entry: LogEntry,
    onCopyLine: (String) -> Unit
) {
    val colors = LocalAppThemeColors.current
    val levelColor = when (entry.level.uppercase()) {
        "ERROR" -> colors.accentCrimson
        "WARN" -> colors.accentAmber
        "DEBUG" -> colors.textTertiary
        else -> colors.accentCyan
    }

    val levelBadge = when (entry.level.uppercase()) {
        "ERROR" -> "ERR"
        "WARN" -> "WRN"
        "DEBUG" -> "DBG"
        else -> "INF"
    }

    val fullLine = "${entry.timestamp} [$levelBadge] ${entry.tag}: ${entry.message.trim()}"

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onCopyLine(fullLine) }
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Timestamp
            Text(
                text = entry.timestamp,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )

            // Glowing Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(levelColor.copy(alpha = 0.18f))
                    .border(1.dp, levelColor.copy(alpha = 0.40f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = levelBadge,
                    color = levelColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Message with IP / Port syntax styling
            val formattedMessage = remember(entry.message, entry.tag, colors) {
                formatLogSyntax("${entry.tag}: ${entry.message.trim()}", levelColor, colors)
            }

            Text(
                text = formattedMessage,
                color = if (entry.level == "ERROR") colors.accentCrimson else colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Parses IP addresses, ports (:443), and domain tags in log messages,
 * coloring them for high readability in developer console.
 */
private fun formatLogSyntax(
    text: String,
    levelColor: Color,
    colors: AppThemeColors
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        // Match IPs (e.g. 198.18.0.1 or 127.0.0.1), Ports (:443, :10808), and domains (.com, .ru)
        val pattern = Regex("""(\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?\b|:\d{2,5}\b|[a-zA-Z0-9-]+\.(com|ru|org|net|io|dev|app)\b)""")
        val matches = pattern.findAll(text)

        for (match in matches) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            val token = match.value
            pushStyle(
                SpanStyle(
                    color = if (token.startsWith(":")) colors.accentAmber else colors.accentSecondary,
                    fontWeight = FontWeight.Bold
                )
            )
            append(token)
            pop()
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
