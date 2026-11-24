package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch
import com.danieldk.splatevpn.ui.components.SplateDropdownMenuItem as DropdownMenuItem

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.AnonymousErrorReports
import com.danieldk.splatevpn.data.AnonymousReportStore
import com.danieldk.splatevpn.data.LocalErrorReport
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.launch
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

@Composable
fun AnonymousReportsCard(store: AnonymousReportStore) {
    val enabled by store.enabled.collectAsState()
    val reports by store.reports.collectAsState()
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<LocalErrorReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf("QR") }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(strings.localized("Отчёты об ошибках", "Error reports"), color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
        Text(strings.localized("Только с вашего согласия сохраняются тип ошибки, строки кода и версия приложения. IP, сведения об устройстве, сообщения исключений, логи, подписки и настройки в отчёт не попадают.",
            "With your consent, reports contain only the error type, code locations and app version. IP addresses, device data, exception messages, logs, subscriptions and settings are excluded."), color = colors.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.localized("Сохранять локально", "Save locally"), Modifier.weight(1f), color = colors.textPrimary)
            Switch(checked = enabled, enabled = !busy, onCheckedChange = { value ->
                scope.launch { busy = true; try { store.setEnabled(value) }
                    catch (error: Exception) { message = error.message } finally { busy = false } }
            })
        }
        Text(strings.localized("Автоматической отправки нет: сервер приёма не настроен. До пяти отчётов хранятся только на этом устройстве. Отключение сбора удаляет их.",
            "No automatic sending: there is no report receiver. Up to five reports stay on this device. Disabling collection deletes them."), color = colors.textSecondary)
        if (enabled) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(strings.localized("Раздел: ", "Feature: ") + category) }
                DropdownMenu(expanded, { expanded = false }) { AnonymousErrorReports.categories.forEach { item ->
                    DropdownMenuItem(text = { Text(item) }, onClick = { category = item; expanded = false })
                } }
            }
            OutlinedButton(onClick = { scope.launch { store.recordFeature(category).onFailure { message = it.message } } }) {
                Text(strings.localized("Отметить проблему", "Record a problem"))
            }
        }
        reports.forEachIndexed { index, report ->
            OutlinedButton(onClick = { preview = report }, modifier = Modifier.fillMaxWidth()) {
                Text(strings.localized("Посмотреть отчёт ", "Review report ") + (index + 1))
            }
        }
        if (reports.isEmpty()) Text(strings.localized("Сохранённых отчётов нет", "No saved reports"), color = colors.textTertiary)
        if (reports.isNotEmpty()) TextButton(onClick = { scope.launch { store.clear() } }) { Text(strings.localized("Удалить все", "Delete all")) }
        message?.let { Text(it, color = colors.accentCrimson) }
    }
    preview?.let { report ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(strings.localized("Проверить перед экспортом", "Review before export")) }, text = {
            Column {
                Text(strings.localized("Экспорт открывает системное меню. Вы сами выбираете получателя.", "Export opens the system menu. You choose the recipient."))
                Spacer(Modifier.height(12.dp))
                SelectionContainer(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text(report.json, fontFamily = FontFamily.Monospace)
                }
            }
        }, confirmButton = { Button(onClick = {
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, report.json), "SplateVPN"))
        }) { Text(strings.localized("Экспорт", "Export")) } }, dismissButton = {
            Row {
                TextButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("SplateVPN", report.json))
                }) { Text(strings.localized("Копировать", "Copy")) }
                TextButton(onClick = { scope.launch { store.delete(report.id); preview = null } }) { Text(strings.localized("Удалить", "Delete")) }
            }
        })
    }
}
