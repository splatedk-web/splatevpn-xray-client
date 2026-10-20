package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.AdFilterCompiler
import com.danieldk.splatevpn.data.AdFilterState
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.RussianAppStrings
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import com.danieldk.splatevpn.data.AdFilterRepository

@Composable
internal fun AdFilterCard(state: AdFilterState, allow: List<String>, update: () -> Unit, save: (List<String>) -> Unit) {
    val ru = LocalAppStrings.current is RussianAppStrings
    val colors = LocalAppThemeColors.current
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AdGuard DNS · ${state.count}", color = colors.textPrimary, fontSize = 14.sp)
        val date = if (state.updatedAt == 0L) {
            if (ru) "База из приложения · работает без сети" else "Bundled base · works offline"
        } else {
            val value = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.updatedAt))
            if (ru) "Обновлено: $value" else "Updated: $value"
        }
        Text(date, color = colors.textSecondary, fontSize = 11.sp)
        Text(if (ru) "Автообновление раз в сутки. Фоновая база применяется при следующем подключении. Ручное обновление перезагружает активный VPN."
            else "Updates daily. Background updates apply on next connection. Manual updates reload the active VPN.",
            color = colors.textSecondary, fontSize = 11.sp)
        if (state.failed) Text(if (ru) "Не удалось обновить или загрузить расширенную базу. Доступная локальная защита сохранена."
            else "Could not update or load the extended list. Available local protection is retained.", color = colors.textPrimary, fontSize = 11.sp)
        OutlinedButton(onClick = update, enabled = !state.updating, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.updating) { if (ru) "Обновление…" else "Updating…" }
                else { if (ru) "Обновить фильтры" else "Update filters" })
        }
        TextButton(onClick = { text = allow.joinToString("\n"); dialog = true }) {
            Text(if (ru) "Исключения (${allow.size})" else "Exceptions (${allow.size})")
        }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AdFilterRepository.SOURCE))) }) {
            Text(if (ru) "Источник и лицензия: AdGuard · GPL-3.0" else "Source and license: AdGuard · GPL-3.0", fontSize = 10.sp)
        }
    }
    if (dialog) {
        val domains = text.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        val valid = domains.size <= 100 && domains.all { AdFilterCompiler.normalizeDomain(it) != null }
        AlertDialog(onDismissRequest = { dialog = false }, title = { Text(if (ru) "Исключения блокировщика" else "Ad blocker exceptions") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ru) "По одному домену в строке, без https:// и путей. Разрешает домен и его поддомены только в блокировщике рекламы. До 100 доменов."
                        else "One domain per line, without https:// or paths. Allows the domain and subdomains only in the ad blocker. Up to 100 domains.")
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        isError = !valid, minLines = 3)
                    if (!valid) Text(if (ru) "Проверьте домены и их количество" else "Check domain names and count", color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(enabled = valid, onClick = { save(domains); dialog = false }) { Text(if (ru) "Сохранить" else "Save") } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text(if (ru) "Отмена" else "Cancel") } })
    }
}
