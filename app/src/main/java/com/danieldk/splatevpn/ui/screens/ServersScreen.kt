package com.danieldk.splatevpn.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.glassmorphism
import com.danieldk.splatevpn.ui.theme.*

@Composable
fun ServersScreen(
    viewModel: MainViewModel
) {
    val servers by viewModel.allServers.collectAsState()
    val subscriptions by viewModel.allSubscriptions.collectAsState()
    val selectedServer by viewModel.selectedServer.collectAsState()
    val isPinging by viewModel.isPinging.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var selectedFilterSubId by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSubManageDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerNode?>(null) }

    val filteredServers = remember(servers, selectedFilterSubId) {
        if (selectedFilterSubId == null) {
            servers
        } else {
            servers.filter { it.subId == selectedFilterSubId }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BackgroundDark, BackgroundDeep, Color(0xFF020408))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 18.dp, start = 16.dp, end = 16.dp)
        ) {
            // Tactical Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "NETWORK NODES",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "AVAILABLE: ${filteredServers.size} / ${servers.size}",
                        color = TextTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Auto-select button
                    IconButton(
                        onClick = {
                            viewModel.autoSelectFastestServer { best ->
                                if (best != null) {
                                    Toast.makeText(context, "Выбран: ${best.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceDark)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Автовыбор",
                            tint = AccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Ping all button
                    Button(
                        onClick = { viewModel.testAllPings() },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        enabled = !isPinging
                    ) {
                        if (isPinging) {
                            CircularProgressIndicator(
                                color = AccentCyan,
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = AccentCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = if (isPinging) "PING..." else "PING ALL",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Row: Paste link / Subscriptions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val clipText = clipboardManager.getText()?.text
                        if (!clipText.isNullOrBlank()) {
                            viewModel.addServerFromLink(clipText) { success ->
                                if (success) {
                                    Toast.makeText(context, "Сервер успешно добавлен!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Неверный формат ссылки", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            showAddDialog = true
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = SurfaceDark,
                        contentColor = AccentCyan
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PASTE LINK",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { showSubManageDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderMedium)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SUBS (${subscriptions.size})",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Subscription Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // "All" chip
                FilterChip(
                    selected = selectedFilterSubId == null,
                    onClick = { selectedFilterSubId = null },
                    label = { Text("Все (${servers.size})", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentTeal.copy(alpha = 0.2f),
                        selectedLabelColor = AccentTeal,
                        containerColor = Color(0xFF0F172A),
                        labelColor = Color(0xFF94A3B8)
                    )
                )

                // "Built-in" chip
                FilterChip(
                    selected = selectedFilterSubId == "builtin",
                    onClick = { selectedFilterSubId = "builtin" },
                    label = { Text("⚡ Встроенные", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentTeal.copy(alpha = 0.2f),
                        selectedLabelColor = AccentTeal,
                        containerColor = Color(0xFF0F172A),
                        labelColor = Color(0xFF94A3B8)
                    )
                )

                // Custom user subscriptions
                subscriptions.filter { it.id != "builtin" }.forEach { sub ->
                    FilterChip(
                        selected = selectedFilterSubId == sub.id,
                        onClick = { selectedFilterSubId = sub.id },
                        label = { Text("📋 ${sub.name}", fontSize = 11.sp, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentTeal.copy(alpha = 0.2f),
                            selectedLabelColor = AccentTeal,
                            containerColor = Color(0xFF0F172A),
                            labelColor = Color(0xFF94A3B8)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Server List
            if (filteredServers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (servers.isEmpty()) "Нет доступных серверов.\nДобавьте ссылку или подписку." else "В данной категории нет серверов.",
                        color = Color.Gray,
                        lineHeight = 20.sp
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(filteredServers, key = { it.id }) { server ->
                        val isSelected = selectedServer?.id == server.id
                        ServerCard(
                            server = server,
                            isSelected = isSelected,
                            onSelect = { viewModel.selectServer(server.id) },
                            onEdit = { editingServer = server },
                            onDelete = { viewModel.deleteServer(server.id) }
                        )
                    }
                }
            }
        }

        // Dialog: Add manual link
        if (showAddDialog) {
            var inputLink by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Добавить сервер") },
                text = {
                    Column {
                        Text("Вставьте vless://, trojan:// или vmess:// ссылку:", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = inputLink,
                            onValueChange = { inputLink = it },
                            placeholder = { Text("vless://...", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.addServerFromLink(inputLink) { success ->
                                if (success) {
                                    Toast.makeText(context, "Сервер добавлен!", Toast.LENGTH_SHORT).show()
                                    showAddDialog = false
                                } else {
                                    Toast.makeText(context, "Ошибка разбора ссылки", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("Добавить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Отмена")
                    }
                }
            )
        }

        // Dialog: Manage Subscriptions (View, Update, Delete, Add)
        if (showSubManageDialog) {
            var newSubUrl by remember { mutableStateOf("") }
            var newSubName by remember { mutableStateOf("") }
            var isOperating by remember { mutableStateOf(false) }

            AlertDialog(
                onDismissRequest = { if (!isOperating) showSubManageDialog = false },
                title = { Text("Управление подписками") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Current subscriptions list
                        Text("Активные подписки:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AccentTeal)
                        Spacer(modifier = Modifier.height(6.dp))

                        subscriptions.forEach { sub ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sub.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${sub.nodeCount} серверов", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                }

                                if (sub.url.isNotBlank()) {
                                    // Update single sub button
                                    IconButton(
                                        onClick = {
                                            isOperating = true
                                            viewModel.updateSubscription(sub.id) { res ->
                                                isOperating = false
                                                res.onSuccess { count ->
                                                    Toast.makeText(context, "Обновлено: $count серверов", Toast.LENGTH_SHORT).show()
                                                }.onFailure { err ->
                                                    Toast.makeText(context, "Ошибка: ${err.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(28.dp),
                                        enabled = !isOperating
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Обновить", tint = AccentTeal, modifier = Modifier.size(16.dp))
                                    }

                                    // Delete sub button
                                    IconButton(
                                        onClick = {
                                            viewModel.deleteSubscription(sub.id)
                                            Toast.makeText(context, "Подписка удалена", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(28.dp),
                                        enabled = !isOperating
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Удалить", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                                    }
                                } else {
                                    Text("Встроенная", color = Color(0xFF64748B), fontSize = 10.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = Color(0xFF334155))
                        Spacer(modifier = Modifier.height(10.dp))

                        Text("Добавить новую подписку:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = newSubName,
                            onValueChange = { newSubName = it },
                            placeholder = { Text("Название (например: Мой VPN)", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = newSubUrl,
                            onValueChange = { newSubUrl = it },
                            placeholder = { Text("https://example.com/sub/...", fontSize = 11.sp) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (isOperating) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentTeal)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Загрузка серверов...", fontSize = 12.sp, color = AccentTeal)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = !isOperating && newSubUrl.isNotBlank(),
                        onClick = {
                            isOperating = true
                            viewModel.importSubscription(newSubUrl, newSubName.takeIf { it.isNotBlank() }) { result ->
                                isOperating = false
                                result.onSuccess { count ->
                                    Toast.makeText(context, "Добавлено серверов: $count", Toast.LENGTH_SHORT).show()
                                    showSubManageDialog = false
                                }.onFailure { err ->
                                    Toast.makeText(context, "Ошибка: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    ) {
                        Text("Добавить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSubManageDialog = false }, enabled = !isOperating) {
                        Text("Закрыть")
                    }
                }
            )
        }

        if (editingServer != null) {
            EditServerDialog(
                server = editingServer!!,
                onDismiss = { editingServer = null },
                onSave = { updated ->
                    viewModel.updateServer(updated)
                    editingServer = null
                    Toast.makeText(context, "Параметры узла сохранены", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
fun ServerCard(
    server: ServerNode,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (isSelected) AccentCyan else BorderSubtle
    val bgColor = if (isSelected) AccentCyan.copy(alpha = 0.08f) else SurfaceDark

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onSelect() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection Indicator
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (isSelected) AccentCyan else BorderMedium, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(AccentCyan)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server.subName.isNotBlank()) {
                    TagPill(text = server.subName.uppercase(), color = AccentAmber)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                TagPill(text = server.protocol.uppercase(), color = AccentCyan)
                Spacer(modifier = Modifier.width(4.dp))
                if (server.security.isNotBlank() && server.security != "none") {
                    TagPill(text = server.security.uppercase(), color = AccentEmerald)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if (server.network.isNotBlank()) {
                    TagPill(text = server.network.uppercase(), color = Color(0xFFA78BFA))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${server.address}:${server.port}",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Ping Pill
        val ping = server.ping
        val (pingText, pingColor) = when {
            ping in 1..120 -> "$ping ms" to AccentEmerald
            ping in 121..250 -> "$ping ms" to AccentAmber
            ping > 250 -> "$ping ms" to AccentCrimson
            else -> "---" to TextTertiary
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ping > 0) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(pingColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = pingText,
                color = pingColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Редактировать",
                tint = AccentCyan,
                modifier = Modifier.size(16.dp)
            )
        }

        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = "Удалить",
                tint = TextTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
fun TagPill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.12f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}
