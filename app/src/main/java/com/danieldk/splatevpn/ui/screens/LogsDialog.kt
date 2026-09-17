package com.danieldk.splatevpn.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danieldk.splatevpn.data.LogEntry
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.AccentTeal
import kotlinx.coroutines.launch

@Composable
fun LogsDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val logs by viewModel.logsFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedFilter by remember { mutableStateOf("Все") }

    val filteredLogs = remember(logs, selectedFilter) {
        when (selectedFilter) {
            "Ошибки" -> logs.filter { it.level == "ERROR" || it.level == "WARN" }
            "Xray" -> logs.filter { it.tag.contains("Xray", ignoreCase = true) }
            "VPN" -> logs.filter { it.tag.contains("Splate", ignoreCase = true) }
            else -> logs
        }
    }

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1120).copy(alpha = 0.96f))
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF131D31))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(24.dp))
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(AccentTeal.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = AccentTeal,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Журнал отладки",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Записей: ${filteredLogs.size}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Toolbar: Copy, Share, Clear
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Copy button
                    Button(
                        onClick = {
                            val report = viewModel.getDiagnosticReport()
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("SplateVPN Log", report)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Отчет скопирован в буфер! Отправьте его разработчику", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentTeal),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = Color(0xFF0B1120),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Копировать", color = Color(0xFF0B1120), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }

                    // Share button
                    OutlinedButton(
                        onClick = {
                            val report = viewModel.getDiagnosticReport()
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, report)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Поделиться логом SplateVPN"))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Поделиться", color = Color.White, fontSize = 13.sp)
                    }

                    // Clear button
                    IconButton(
                        onClick = {
                            viewModel.clearLogs()
                            Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B))
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Очистить",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Filter chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf("Все", "Ошибки", "Xray", "VPN")
                    filters.forEach { filter ->
                        val isSelected = selectedFilter == filter
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) AccentTeal.copy(alpha = 0.2f) else Color(0xFF1E293B))
                                .border(
                                    1.dp,
                                    if (isSelected) AccentTeal else Color(0xFF334155),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedFilter = filter }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = filter,
                                color = if (isSelected) AccentTeal else Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Terminal Log Console
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF080D1A))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .padding(10.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Журнал пуст. События будут появляться здесь в реальном времени.",
                                color = Color(0xFF64748B),
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredLogs) { entry ->
                                LogEntryItem(entry)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(entry: LogEntry) {
    val levelColor = when (entry.level) {
        "ERROR" -> Color(0xFFEF4444)
        "WARN" -> Color(0xFFF59E0B)
        "DEBUG" -> Color(0xFF94A3B8)
        else -> Color(0xFF38BDF8)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = entry.timestamp,
            color = Color(0xFF64748B),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = "[${entry.level.take(1)}]",
            color = levelColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${entry.tag}: ${entry.message}",
            color = if (entry.level == "ERROR") Color(0xFFFCA5A5) else Color(0xFFE2E8F0),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
    }
}
