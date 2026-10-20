package com.danieldk.splatevpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.*
import java.time.LocalDate
import java.util.Locale

internal class ToolsText(val russian: Boolean) {
    operator fun invoke(ru: String, en: String): String = if (russian) ru else en
    val locale: Locale get() = if (russian) Locale.forLanguageTag("ru") else Locale.ENGLISH
}

@Composable
internal fun toolsText() = ToolsText(LocalAppStrings.current is RussianAppStrings)

internal fun trafficBytes(value: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    return String.format(Locale.getDefault(), if (unit == 0) "%.0f %s" else "%.1f %s", amount, units[unit])
}

@Composable
fun ConnectionToolsSection(viewModel: MainViewModel) {
    val text = toolsText()
    val colors = LocalAppThemeColors.current
    val days by viewModel.trafficDays.collectAsState()
    val services by viewModel.serviceChecks.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    fun dismiss() {
        if (panel == "services") viewModel.cancelServiceChecks()
        if (panel == "diagnostics") viewModel.cancelDiagnostics()
        panel = null
    }
    SectionHeader(text("Статистика и инструменты", "Statistics and tools"))
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val today = days.firstOrNull { it.date == LocalDate.now() }
        ToolEntry(Icons.Default.BarChart, text("Статистика трафика", "Traffic statistics"),
            text("Сегодня", "Today") + "  ↓ ${trafficBytes(today?.rx ?: 0)}  ↑ ${trafficBytes(today?.tx ?: 0)}", colors.accentCyan) { panel = "traffic" }
        HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))
        ToolEntry(Icons.Default.Language, text("Доступность сервисов", "Service availability"),
            text("8 сервисов · проверка через VPN", "8 services · check through VPN"), colors.accentSecondary) {
            panel = "services"; viewModel.checkServices()
        }
        HorizontalDivider(color = colors.borderSubtle.copy(alpha = 0.6f))
        ToolEntry(Icons.Default.HealthAndSafety, text("Диагностика в один тап", "One-tap diagnostics"),
            text("Сеть, DNS, сервер и туннель", "Network, DNS, server and tunnel"), colors.accentEmerald) {
            panel = "diagnostics"; viewModel.diagnoseConnection()
        }
    }
    when (panel) {
        "traffic" -> TrafficStatisticsSheet(days, onClear = viewModel::clearTrafficHistory, onDismiss = ::dismiss)
        "services", "diagnostics" -> ChecksSheet(
            run = if (panel == "services") services else diagnostics,
            services = panel == "services", onDismiss = ::dismiss,
            onRun = { if (panel == "services") viewModel.checkServices() else viewModel.diagnoseConnection() },
            onStop = { if (panel == "services") viewModel.cancelServiceChecks() else viewModel.cancelDiagnostics() })
    }
}

@Composable
private fun ToolEntry(icon: ImageVector, title: String, subtitle: String, accent: Color, onClick: () -> Unit) {
    val colors = LocalAppThemeColors.current
    Row(Modifier.fillMaxWidth().tactileClickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).liquidGlass(cornerRadius = 12, accentBorder = accent.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.textSecondary, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppThemeColors.current
    val text = toolsText()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Transparent, dragHandle = null) {
        Column(Modifier.fillMaxWidth().heightIn(max = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * 0.9f)
            .liquidGlass(cornerRadius = 28, elevated = true)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(36.dp, 4.dp)
                .background(colors.textTertiary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, text("Закрыть", "Close"), tint = colors.textSecondary) }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
private fun ChecksSheet(run: CheckRun, services: Boolean, onDismiss: () -> Unit, onRun: () -> Unit, onStop: () -> Unit) {
    val text = toolsText()
    val colors = LocalAppThemeColors.current
    ToolsSheet(if (services) text("Доступность сервисов", "Service availability") else text("Диагностика соединения", "Connection diagnostics"), onDismiss) {
        val success = run.results.count { it.outcome == CheckOutcome.OK }
        Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 18, accentBorder = colors.accentCyan).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (run.running) CircularProgressIndicator(Modifier.size(24.dp), color = colors.accentCyan, strokeWidth = 2.dp)
                else Icon(if (success > 0) Icons.Default.CheckCircle else Icons.Default.Info, contentDescription = null, tint = colors.accentCyan)
                Text(when { run.running -> text("Проверяем подключение…", "Checking connection…")
                    run.cancelled -> text("Проверка остановлена", "Check stopped")
                    else -> text("Проверка завершена", "Check complete") }, color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
            }
            Text(text("Успешно: $success из ${run.results.size}", "Successful: $success of ${run.results.size}"), color = colors.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (run.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.accentCyan)
            else if (run.completedAt > 0) Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, text.locale).format(java.util.Date(run.completedAt)), color = colors.textSecondary, fontSize = 11.sp)
            if (run.cancelled) Text(text("Показаны неполные результаты.", "Results are incomplete."), color = colors.accentAmber, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14, accentBorder = colors.accentCyan)
            .tactileClickable { if (run.running) onStop() else onRun() }.padding(14.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (run.running) Icons.Default.Stop else Icons.Default.Refresh, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (run.running) text("Остановить проверку", "Stop check") else text("Проверить снова", "Check again"), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        }
        run.results.forEach { result -> key(result.name) { CheckResultCard(result) } }
        Text(if (services) text("Проверки идут через текущее подключение и его правила. Доступность сайта не гарантирует работу входа, видео или звонков.",
            "Checks use the current connection and routing rules. Website availability does not guarantee login, streaming or calls.")
            else text("DNS и порт сервера проверяются через физическую сеть, HTTPS — через VPN. Открытый порт не подтверждает правильность ключей подключения.",
                "DNS and server port use the physical network; HTTPS uses VPN. An open port does not confirm valid credentials."), color = colors.textSecondary, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun CheckResultCard(result: CheckResult) {
    val text = toolsText()
    val colors = LocalAppThemeColors.current
    var expanded by remember(result) { mutableStateOf(result.outcome != CheckOutcome.OK) }
    val accent = when (result.outcome) { CheckOutcome.OK -> colors.accentEmerald; CheckOutcome.FAILED -> colors.accentCrimson
        CheckOutcome.LIMITED -> colors.accentAmber; else -> colors.textSecondary }
    val name = when (result.name) { "network" -> text("Сеть", "Network"); "dns" -> text("Системный DNS", "System DNS")
        "server" -> text("Порт сервера", "Server port"); "vpn" -> "VPN"; else -> result.name }
    val status = when (result.outcome) { CheckOutcome.OK -> text("Доступно", "Available"); CheckOutcome.FAILED -> text("Ошибка", "Failed")
        CheckOutcome.LIMITED -> text("Ограничение доступа", "Access restricted"); CheckOutcome.SKIPPED -> text("Пропущено", "Skipped") }
    val detail = when (result.detail) {
                                "vpn_off" -> text("Сначала подключите VPN и повторите проверку", "Connect VPN and retry")
                                "vpn_ok" -> text("VPN подключён", "VPN connected")
                                "network_ok" -> text("Есть сеть с доступом к интернету", "Internet-capable network found")
                                "network_missing" -> text("Нет сети: проверьте Wi-Fi или мобильные данные", "No network: check Wi-Fi or mobile data")
                                "dns_ok" -> text("Имя разрешается в IP-адрес", "Hostname resolves to an IP")
                                "dns_failed", "dns" -> text("Не удалось разрешить имя: проверьте DNS", "Name resolution failed: check DNS")
                                "port_ok" -> text("TCP-соединение установлено", "TCP connection established")
                                "port_failed" -> text("Порт недоступен: проверьте адрес, порт или смените сервер", "Port unavailable: check address/port or switch server")
                                "no_server" -> text("Выберите сервер", "Select a server")
                                "tls" -> text("Ошибка TLS: проверьте дату устройства и настройки TLS", "TLS failed: check device date and TLS settings")
                                "timeout" -> text("Таймаут: повторите проверку или смените сервер", "Timeout: retry or switch server")
                                "network" -> text("Сбой подключения: проверьте сеть и сервер", "Connection failed: check network and server")
                                else -> result.detail
                            }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16, accentBorder = accent.copy(alpha = 0.55f))
        .tactileClickable { expanded = !expanded }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(when (result.outcome) { CheckOutcome.OK -> Icons.Default.CheckCircle; CheckOutcome.FAILED -> Icons.Default.ErrorOutline
                CheckOutcome.LIMITED -> Icons.Default.WarningAmber; else -> Icons.Default.RemoveCircleOutline }, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(status, color = accent, fontSize = 11.sp)
            }
            result.elapsedMs?.let { Text("$it ms", color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, text("Подробности", "Details"), tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded) { Text(detail, modifier = Modifier.padding(top = 10.dp), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}
