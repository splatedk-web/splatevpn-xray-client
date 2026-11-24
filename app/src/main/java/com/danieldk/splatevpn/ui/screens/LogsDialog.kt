package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton

import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.LogEntry
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.rememberHapticFeedbackManager
import com.danieldk.splatevpn.ui.components.ToolDialog
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings
import kotlinx.coroutines.launch

private enum class LogFilter { ALL, ERRORS, XRAY, VPN }
private val logAddressPattern = Regex("""(\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}(:\d+)?\b|:\d{2,5}\b|[a-zA-Z0-9-]+\.(com|ru|org|net|io|dev|app)\b)""")

@Composable
fun LogsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val logs by viewModel.logsFlow.collectAsState()
    val errorLogs by viewModel.errorsFlow.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val haptic = rememberHapticFeedbackManager()

    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val animationsEnabled = LocalUiPerformanceSettings.current.animationsEnabled

    val allFilter = strings.logsFilterAll
    val errorsFilter = strings.logsFilterErrors
    val xrayFilter = strings.logsFilterXray
    val vpnFilter = strings.logsFilterVpn

    var selectedFilter by remember { mutableStateOf(LogFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var isAutoscrollEnabled by remember { mutableStateOf(true) }

    val totalCount = logs.size
    val errorsCount = errorLogs.size
    val warningsCount = remember(logs) { logs.count { it.level.equals("WARN", ignoreCase = true) } }
    val xrayCount = remember(logs) { logs.count { it.tag.contains("Xray", ignoreCase = true) } }

    val filteredLogs = remember(logs, errorLogs, selectedFilter, searchQuery) {
        val base = when (selectedFilter) {
            LogFilter.ERRORS -> errorLogs
            LogFilter.XRAY -> logs.filter { it.tag.contains("Xray", ignoreCase = true) }
            LogFilter.VPN -> logs.filter { it.tag.contains("Splate", ignoreCase = true) || it.tag.contains("VPN", ignoreCase = true) }
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

    LaunchedEffect(filteredLogs.lastOrNull(), isAutoscrollEnabled, animationsEnabled) {
        if (isAutoscrollEnabled && filteredLogs.isNotEmpty()) {
            if (animationsEnabled) listState.animateScrollToItem(filteredLogs.size - 1)
            else listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    var reportPreview by remember { mutableStateOf<String?>(null) }
    var reportLoading by remember { mutableStateOf(false) }
    val reportScope = rememberCoroutineScope()
    fun previewReport() {
        if (reportLoading) return
        reportLoading = true
        reportScope.launch {
            try {
                reportPreview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { viewModel.getDiagnosticReport() }
            } finally { reportLoading = false }
        }
    }
    reportPreview?.let { report ->
        com.danieldk.splatevpn.ui.components.DiagnosticReportPreview(report) { reportPreview = null }
    }

    ToolDialog(onDismiss, Modifier.testTag("logsDialogPanel"), maxWidth = 640.dp,
        contentPadding = PaddingValues(0.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
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
                        }
                    }
                    IconButton(
                        onClick = {
                            haptic.click()
                            onDismiss()
                        },
                        modifier = Modifier
                            .size(48.dp)
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
                                contentDescription = strings.uiClear,
                                tint = colors.textSecondary,
                                modifier = Modifier
                                    .size(40.dp)
                                    .tvFocusOutline().clickable { searchQuery = "" }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())
                    ) {
                        listOf(LogFilter.ALL to allFilter, LogFilter.ERRORS to errorsFilter,
                            LogFilter.XRAY to xrayFilter, LogFilter.VPN to vpnFilter).forEach { (filter, label) ->
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
                                    .tvFocusOutline().clickable {
                                        haptic.tick()
                                        selectedFilter = filter
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 9.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) colors.accentCyan else colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
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
                            .tvFocusOutline().clickable {
                                haptic.tick()
                                isAutoscrollEnabled = !isAutoscrollEnabled
                            }
                            .heightIn(min = 48.dp)
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
                            text = if (isAutoscrollEnabled) strings.uiPause else strings.uiResume,
                            color = if (isAutoscrollEnabled) colors.accentEmerald else colors.textTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
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

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { haptic.click(); previewReport() },
                        enabled = !reportLoading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Description, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(com.danieldk.splatevpn.ui.theme.LocalAppResources.current.getString(
                            com.danieldk.splatevpn.R.string.tool_review_report), fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = {
                            haptic.heavy()
                            viewModel.clearLogs()
                            Toast.makeText(context, strings.logsCleared, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, strings.clearLogs, tint = colors.accentCrimson)
                    }
                    Button(
                        onClick = { haptic.pop(); onDismiss() },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("logsClose"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Text(strings.close, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
            .tvFocusOutline().clickable { onCopyLine(fullLine) }
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = entry.timestamp,
                color = colors.textSecondary,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                lineHeight = 14.sp
            )
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
            val formattedMessage = remember(entry.message, entry.tag, colors) {
                formatLogSyntax("${entry.tag}: ${entry.message.trim()}", colors)
            }

            Text(
                text = formattedMessage,
                color = if (entry.level.equals("ERROR", ignoreCase = true)) colors.accentCrimson else colors.textPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun formatLogSyntax(
    text: String,
    colors: AppThemeColors
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        val matches = logAddressPattern.findAll(text)

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
