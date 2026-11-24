package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

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
import com.danieldk.splatevpn.ui.theme.LocalAppResources
import com.danieldk.splatevpn.ui.theme.localized
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import com.danieldk.splatevpn.data.AdFilterRepository

@Composable
internal fun AdFilterCard(state: AdFilterState, allow: List<String>, update: () -> Unit, save: (List<String>) -> Unit) {
    val strings = LocalAppStrings.current
    val locale = LocalAppResources.current.configuration.locales[0]
    val colors = LocalAppThemeColors.current
    val context = LocalContext.current
    var dialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 14).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AdGuard DNS · ${state.count}", color = colors.textPrimary, fontSize = 14.sp)
        val date = if (state.updatedAt == 0L) {
            strings.localized("База из приложения · работает без сети", "Bundled base · works offline")
        } else {
            val value = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(state.updatedAt))
            String.format(strings.localized("Обновлено: %s", "Updated: %s"), value)
        }
        Text(date, color = colors.textSecondary, fontSize = 11.sp)
        Text(strings.localized("Автообновление раз в сутки. Фоновая база применяется при следующем подключении. Ручное обновление перезагружает активный VPN.", "Updates daily. Background updates apply on next connection. Manual updates reload the active VPN."),
            color = colors.textSecondary, fontSize = 11.sp)
        if (state.failed) Text(strings.localized("Не удалось обновить или загрузить расширенную базу. Доступная локальная защита сохранена.", "Could not update or load the extended list. Available local protection is retained."), color = colors.textPrimary, fontSize = 11.sp)
        OutlinedButton(onClick = update, enabled = !state.updating, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.updating) { strings.localized("Обновление…", "Updating…") }
                else { strings.localized("Обновить фильтры", "Update filters") })
        }
        TextButton(onClick = { text = allow.joinToString("\n"); dialog = true }) {
            Text(String.format(strings.localized("Исключения (%d)", "Exceptions (%d)"), allow.size))
        }
        TextButton(onClick = {
            val opened = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AdFilterRepository.SOURCE))) }.isSuccess
            if (!opened) {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("AdGuard DNS", AdFilterRepository.SOURCE))
                android.widget.Toast.makeText(context, strings.linkCopied, android.widget.Toast.LENGTH_SHORT).show()
            }
        }) {
            Text(strings.localized("Источник и лицензия: AdGuard · GPL-3.0", "Source and license: AdGuard · GPL-3.0"), fontSize = 10.sp)
        }
    }
    if (dialog) {
        val domains = text.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        val valid = domains.size <= 100 && domains.all { AdFilterCompiler.normalizeDomain(it) != null }
        AlertDialog(onDismissRequest = { dialog = false }, title = { Text(strings.localized("Исключения блокировщика", "Ad blocker exceptions")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.localized("По одному домену в строке, без https:// и путей. Разрешает домен и его поддомены только в блокировщике рекламы. До 100 доменов.", "One domain per line, without https:// or paths. Allows the domain and subdomains only in the ad blocker. Up to 100 domains."))
                    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        isError = !valid, minLines = 3)
                    if (!valid) Text(strings.localized("Проверьте домены и их количество", "Check domain names and count"), color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(enabled = valid, onClick = { save(domains); dialog = false }) { Text(strings.localized("Сохранить", "Save")) } },
            dismissButton = { TextButton(onClick = { dialog = false }) { Text(strings.localized("Отмена", "Cancel")) } })
    }
}
