package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch
import com.danieldk.splatevpn.ui.components.SplateCheckbox as Checkbox

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.AdvancedRouteRule
import com.danieldk.splatevpn.data.AdvancedRoutingRules
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.screens.AppItem
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog
import com.danieldk.splatevpn.ui.components.SplateButton as Button

@Composable
internal fun AdvancedRoutingCard(viewModel: MainViewModel) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val encoded by viewModel.advancedRouting.collectAsState()
    val tor by viewModel.torSettings.collectAsState()
    val allServers by viewModel.allServers.collectAsState()
    val serverMap = remember(allServers) { allServers.associateBy { it.id } }
    var decoded by remember { mutableStateOf<Result<List<AdvancedRouteRule>>>(Result.success(emptyList())) }
    LaunchedEffect(encoded) {
        decoded = withContext(Dispatchers.Default) { runCatching { AdvancedRoutingRules.decode(encoded) } }
    }
    val rules = decoded.getOrDefault(emptyList())
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<AdvancedRouteRule?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Result<Unit>) {
        scope.launch { busy = true; try { error = block().exceptionOrNull()?.localizedMessage } finally { busy = false } }
    }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.localized("Составные правила · AND / OR", "Combined rules · AND / OR"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Text(strings.localized("AND — совпадают все заполненные поля. OR — любое из них. Строки в одном поле — варианты. Применяется первое подходящее правило.", "AND requires all filled fields to match; OR requires any one. Lines within a field are alternatives. The first matching rule applies."), color = colors.textSecondary)
        Text(strings.localized("Условия: приложения, домены, IP, порты и протоколы. Для приложений нужен Android 10+ и включение в VPN в разделе «Приложения».", "Match apps, domains, IPs, ports and protocols. App conditions need Android 10+ and inclusion in VPN on the Apps screen."), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        if (tor.enabled) Text(strings.localized("Правила сохраняются, но в режиме Tor не применяются.", "Rules are saved but do not apply in Tor mode."), color = colors.accentAmber)
        rules.forEachIndexed { index, rule ->
            Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 12).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${index + 1}. ${rule.name}", color = colors.textPrimary)
                        val targetServerName = rule.targetServerId?.let { serverMap[it]?.name }
                        val destination = when (rule.outbound) {
                            "proxy" -> if (targetServerName != null) "VPN ($targetServerName)" else "VPN"
                            "direct" -> strings.localized("Напрямую", "Direct")
                            else -> strings.localized("Блок", "Block")
                        }
                        Text(rule.operation.uppercase() + " → " + destination, style = MaterialTheme.typography.bodySmall, color = colors.accentCyan)
                    }
                    Switch(rule.enabled, { value -> action { viewModel.saveAdvancedRoute(rule.copy(enabled = value)) } }, enabled = !busy)
                }
                Text(listOfNotNull(
                    rule.packages.takeIf { it.isNotEmpty() }?.let { strings.localized("Приложения: ", "Apps: ") + it.joinToString() },
                    rule.suffixes.takeIf { it.isNotEmpty() }?.let { strings.localized("Суффиксы: ", "Suffixes: ") + it.joinToString() },
                    rule.domains.takeIf { it.isNotEmpty() }?.let { strings.localized("Домены: ", "Domains: ") + it.joinToString() },
                    rule.keywords.takeIf { it.isNotEmpty() }?.let { strings.localized("Слова: ", "Keywords: ") + it.joinToString() },
                    rule.ips.takeIf { it.isNotEmpty() }?.let { "IP: " + it.joinToString() },
                    rule.ports.takeIf { it.isNotEmpty() }?.let { strings.localized("Порты: ", "Ports: ") + it },
                    rule.networks.takeIf { it.isNotEmpty() }?.joinToString(), rule.protocols.takeIf { it.isNotEmpty() }?.joinToString()
                ).joinToString("\n"), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 5)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editor = rule }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(strings.localized("Изменить", "Edit")) }
                    IconButton(onClick = { action { viewModel.moveAdvancedRoute(rule.id, -1) } }, enabled = !busy && index > 0) { Icon(Icons.Default.ArrowUpward, strings.localized("Выше", "Move up")) }
                    IconButton(onClick = { action { viewModel.moveAdvancedRoute(rule.id, 1) } }, enabled = !busy && index < rules.lastIndex) { Icon(Icons.Default.ArrowDownward, strings.localized("Ниже", "Move down")) }
                    IconButton(onClick = { action { viewModel.removeAdvancedRoute(rule.id) } }, enabled = !busy) { Icon(Icons.Default.DeleteOutline, strings.delete) }
                }
            }
        }
        Button(onClick = { editor = AdvancedRouteRule(UUID.randomUUID().toString(), "") }, enabled = !busy && rules.size < AdvancedRoutingRules.MAX_RULES) { Text(strings.localized("Добавить составное правило", "Add advanced rule")) }
        Text(strings.localized("Сначала применяются пользовательские исключения и системные DNS-правила. Составные правила — перед правилами подписок и региона.", "Custom exceptions and essential DNS rules run first. Combined rules run before subscription and region rules."), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        decoded.exceptionOrNull()?.let {
            Text(strings.localized("Сохранённые правила повреждены. До исправления VPN не будет применять непроверенный набор.", "Saved rules are invalid. The VPN will not use an unvalidated set."), color = colors.accentCrimson)
            TextButton(onClick = { action { viewModel.resetAdvancedRoutes() } }, enabled = !busy) { Text(strings.localized("Очистить повреждённые правила", "Clear invalid rules")) }
        }
        error?.let { Text(it, color = colors.accentCrimson) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    editor?.let { original -> AdvancedRouteEditor(original, viewModel, allServers, viewModel.domainSniffingEnabled(), onDismiss = { editor = null },
        onSave = { next, enable, done -> scope.launch { done(viewModel.saveAdvancedRoute(next, enable)) } }) }
}

@Composable
private fun AdvancedRouteEditor(
    original: AdvancedRouteRule,
    viewModel: MainViewModel,
    allServers: List<ServerNode>,
    sniffingEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: (AdvancedRouteRule, Boolean, (Result<Unit>) -> Unit) -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var name by remember(original) { mutableStateOf(original.name) }
    var operation by remember(original) { mutableStateOf(original.operation) }
    var outbound by remember(original) { mutableStateOf(original.outbound) }
    var targetServerId by remember(original) { mutableStateOf(original.targetServerId) }
    var packages by remember(original) { mutableStateOf(original.packages.joinToString("\n")) }
    var domains by remember(original) { mutableStateOf(original.domains.joinToString("\n")) }
    var suffixes by remember(original) { mutableStateOf(original.suffixes.joinToString("\n")) }
    var keywords by remember(original) { mutableStateOf(original.keywords.joinToString("\n")) }
    var ips by remember(original) { mutableStateOf(original.ips.joinToString("\n")) }
    var ports by remember(original) { mutableStateOf(original.ports) }
    var networks by remember(original) { mutableStateOf(original.networks) }
    var protocols by remember(original) { mutableStateOf(original.protocols.joinToString("\n")) }
    var more by remember { mutableStateOf(false) }
    var enableDetection by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var packagePicker by remember { mutableStateOf(false) }
    var serverPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    LaunchedEffect(error) {
        if (error != null) {
            scrollState.animateScrollTo(0)
        }
    }

    fun lines(value: String) = value.split(Regex("[\r\n,;]+")).map(String::trim).filter(String::isNotEmpty)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.localized("Составное правило", "Combined rule")) },
        text = { Column(Modifier.verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { err ->
                Surface(
                    color = colors.accentCrimson.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.accentCrimson.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = colors.accentCrimson,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(64); error = null },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.localized("Название", "Name")) },
                placeholder = { Text(strings.localized("Автоматически при сохранении", "Auto-generated if empty")) },
                singleLine = true,
                enabled = !busy
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { listOf("and", "or").forEach { mode ->
                FilterChip(operation == mode, { operation = mode }, label = { Text(mode.uppercase()) }, enabled = !busy)
            } }
            Text(if (operation == "and") strings.localized("Должны совпасть все заполненные разделы.", "All filled sections must match.") else strings.localized("Достаточно любого заполненного раздела.", "Any filled section can match."), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("proxy", "direct", "block").forEach { mode ->
                FilterChip(outbound == mode, { outbound = mode }, label = { Text(when (mode) {
                    "proxy" -> "VPN"; "direct" -> strings.localized("Напрямую", "Direct"); else -> strings.localized("Блок", "Block")
                }) }, enabled = !busy)
            } }
            if (outbound == "proxy") {
                val selectedServer = allServers.firstOrNull { it.id == targetServerId }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !busy) { serverPicker = true },
                    color = colors.surfaceElevated,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                strings.localized("Сервер для этого правила", "Server for this rule"),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                selectedServer?.name ?: strings.localized("Основной VPN (текущий активный)", "Default VPN (current active)"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selectedServer != null) colors.accentCyan else colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    }
                }
            } else if (outbound == "direct") {
                Text(strings.localized("Совпавший трафик пойдёт напрямую, вне VPN-сервера.", "Matching traffic goes directly, outside the VPN server."), color = colors.accentAmber)
            }
            OutlinedTextField(packages, { packages = it.take(20000); error = null }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4,
                label = { Text(strings.localized("Пакеты приложений", "App package IDs")) },
                placeholder = { Text("org.example.app") }, enabled = !busy)
            TextButton(onClick = { packagePicker = true }, enabled = !busy && Build.VERSION.SDK_INT >= 29) {
                Text(strings.localized("Выбрать установленные приложения", "Choose installed apps"))
            }
            Text(strings.localized("Один ID пакета на строку, с учётом регистра. Отсутствующие приложения сохраняются. Приложения с общим UID не поддерживаются.", "One package ID per line, case-sensitive. Uninstalled apps stay in the rule. Shared-UID apps are unsupported."),
                style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            if (Build.VERSION.SDK_INT < 29) Text(strings.localized("Для условий по приложениям требуется Android 10 или новее.",
                "App conditions require Android 10 or later."), color = colors.accentAmber)
            OutlinedTextField(suffixes, { suffixes = it.take(20000); error = null }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 5,
                label = { Text(strings.localized("Суффиксы доменов", "Domain suffixes")) }, enabled = !busy)
            Text(strings.localized("Один домен на строку. Включает сам домен и поддомены. Точка в начале необязательна.", "One domain per line. Matches the domain and its subdomains. A leading dot is optional."), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            OutlinedTextField(domains, { domains = it.take(20000); error = null }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                label = { Text(strings.localized("Точные домены", "Exact domains")) }, enabled = !busy)
            OutlinedTextField(keywords, { keywords = it.take(12000); error = null }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                label = { Text(strings.localized("Ключевые слова домена", "Domain keywords")) }, enabled = !busy)
            Text(strings.localized("Несколько строк внутри поля означают «или». Пустые поля не участвуют в сопоставлении.",
                "Multiple lines within a field mean OR. Empty fields are ignored."), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            TextButton(onClick = { more = !more }) { Text(strings.localized("IP, порты и протоколы", "IP, ports and protocols")) }
            if (more) {
                OutlinedTextField(ips, { ips = it.take(12000); error = null }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 4, label = { Text("IP / CIDR") }, enabled = !busy)
                OutlinedTextField(ports, { ports = it.take(4096); error = null }, Modifier.fillMaxWidth(), label = { Text(strings.localized("Порты: 443, 8000-8100", "Ports: 443,8000-8100")) }, enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { listOf("tcp", "udp").forEach { network ->
                    FilterChip(network in networks, { networks = if (network in networks) networks - network else networks + network }, label = { Text(network.uppercase()) }, enabled = !busy)
                } }
                OutlinedTextField(protocols, { protocols = it.take(1024); error = null }, Modifier.fillMaxWidth(), label = { Text(strings.localized("Протоколы: http, tls, quic", "Protocols: http, tls, quic")) }, enabled = !busy)
            }
            if (!sniffingEnabled) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(enableDetection, { enableDetection = it }, enabled = !busy)
                Text(strings.localized("Разрешить определение доменов для этих правил", "Enable domain detection for these rules"))
            }
            if (error != null) {
                Text(error!!, color = colors.accentCrimson, style = MaterialTheme.typography.bodySmall)
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val domainList = lines(domains)
            val suffixList = lines(suffixes)
            val keywordList = lines(keywords)
            val ipList = lines(ips)
            val packageList = lines(packages)
            val protocolSet = lines(protocols).toSet()

            if (domainList.isEmpty() && suffixList.isEmpty() && keywordList.isEmpty() &&
                ipList.isEmpty() && ports.isBlank() && networks.isEmpty() &&
                protocolSet.isEmpty() && packageList.isEmpty()) {
                error = strings.localized("Заполните хотя бы одно условие (домены, приложения, IP или порты)", "Fill at least one rule condition (domains, apps, IPs, or ports)")
                return@TextButton
            }

            val effectiveName = name.trim().ifBlank {
                val candidate = listOfNotNull(
                    domainList.firstOrNull(),
                    suffixList.firstOrNull(),
                    keywordList.firstOrNull(),
                    packageList.firstOrNull()?.substringAfterLast('.'),
                    ipList.firstOrNull(),
                    ports.takeIf { it.isNotBlank() }?.let { "Port $it" }
                ).firstOrNull()
                if (candidate != null) {
                    String.format(strings.localized("Правило: %s", "Rule: %s"), candidate)
                } else {
                    strings.localized("Составное правило", "Combined rule")
                }
            }

            busy = true
            error = null
            val updatedRule = original.copy(
                name = effectiveName,
                operation = operation,
                outbound = outbound,
                targetServerId = if (outbound == "proxy") targetServerId else null,
                domains = domainList,
                suffixes = suffixList,
                keywords = keywordList,
                ips = ipList,
                ports = ports,
                networks = networks,
                protocols = protocolSet,
                packages = packageList
            )
            onSave(updatedRule, enableDetection) { result ->
                busy = false
                val ex = result.exceptionOrNull()
                if (ex != null) {
                    error = ex.localizedMessage ?: ex.message ?: strings.localized("Ошибка валидации правила", "Rule validation error")
                } else {
                    onDismiss()
                }
            }
        }) { Text(strings.save) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings.cancel) } })
    if (serverPicker) {
        ServerPickerDialog(
            selectedId = targetServerId,
            servers = allServers,
            onDismiss = { serverPicker = false },
            onSelect = { selected -> targetServerId = selected }
        )
    }
    if (packagePicker) {
        val apps by viewModel.installedApps.collectAsState()
        val loading by viewModel.isLoadingApps.collectAsState()
        LaunchedEffect(Unit) { viewModel.loadInstalledApps(context) }
        AdvancedPackagePicker(lines(packages).toSet(), apps, loading,
            onRefresh = { viewModel.loadInstalledApps(context, forceReload = true) },
            onDismiss = { packagePicker = false },
            onApply = { selected -> packages = selected.sorted().joinToString("\n"); packagePicker = false })
    }
}

@Composable
private fun ServerPickerDialog(
    selectedId: String?,
    servers: List<ServerNode>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var search by remember { mutableStateOf("") }
    val matching = remember(servers, search) {
        if (search.isBlank()) servers
        else servers.filter { it.name.contains(search, ignoreCase = true) || it.address.contains(search, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.localized("Выбор сервера для правила", "Select server for rule")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(strings.localized("Поиск сервера", "Search server")) }
                )
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        val isDefault = selectedId == null
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isDefault) colors.accentCyan.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { onSelect(null); onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    strings.localized("Основной VPN (автовыбор)", "Default VPN (auto-selected)"),
                                    color = if (isDefault) colors.accentCyan else colors.textPrimary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    strings.localized("Использовать текущий подключённый узел", "Use current connected server"),
                                    color = colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isDefault) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = colors.accentCyan)
                            }
                        }
                    }
                    items(matching, key = { it.id }) { server ->
                        val isSelected = selectedId == server.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) colors.accentCyan.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable { onSelect(server.id); onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    server.name,
                                    color = if (isSelected) colors.accentCyan else colors.textPrimary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${server.protocol.uppercase()} · ${server.address}",
                                    color = colors.textSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = colors.accentCyan)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}

@Composable
private fun AdvancedPackagePicker(initialPackages: Set<String>, apps: List<AppItem>, loading: Boolean,
    onRefresh: () -> Unit, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var selected by remember { mutableStateOf(initialPackages) }
    var search by remember { mutableStateOf("") }
    val matching = remember(apps, search) { apps.filter { search.isBlank() || it.name.contains(search, true) || it.packageName.contains(search, true) } }
    fun toggle(packageName: String) {
        selected = if (packageName in selected) selected - packageName
        else if (selected.size < AdvancedRoutingRules.MAX_VALUES) selected + packageName else selected
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(strings.localized("Приложения для правила", "Apps for this rule")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(strings.localized("Название или пакет", "Name or package ID")) })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format(strings.localized("Выбрано: %d / %d", "Selected: %d / %d"), selected.size, AdvancedRoutingRules.MAX_VALUES), Modifier.weight(1f), color = colors.textSecondary)
                    TextButton(onClick = onRefresh, enabled = !loading) { Text(strings.refresh) }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (matching.isEmpty() && !loading) Text(strings.localized("Подходящих приложений нет", "No matching apps"), color = colors.textSecondary)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(matching, key = { it.packageName }) { app ->
                        val checked = app.packageName in selected
                        val canToggle = checked || selected.size < AdvancedRoutingRules.MAX_VALUES
                        Row(Modifier.fillMaxWidth().tvFocusOutline().clickable(enabled = canToggle, role = Role.Checkbox) { toggle(app.packageName) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked, onCheckedChange = null, enabled = canToggle)
                            Column(Modifier.weight(1f)) {
                                Text(app.name, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text(strings.localized("Введённые вручную пакеты сохраняются, даже если их нет в списке.", "Manually entered packages stay selected even when absent from this list."), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            }
        },
        confirmButton = { TextButton(onClick = { onApply(selected) }) { Text(strings.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } })
}
