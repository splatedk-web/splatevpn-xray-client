package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.data.SubscriptionRoutingRules
import com.danieldk.splatevpn.ui.theme.*
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

@Composable
internal fun SubscriptionPolicySettings(
    sub: SubscriptionEntity,
    onRetention: (Boolean, (Result<Unit>) -> Unit) -> Unit,
    onRules: (Boolean, String?, (Result<Unit>) -> Unit) -> Unit,
    onPurge: ((Result<Int>) -> Unit) -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showRules by remember { mutableStateOf(false) }
    var showPurge by remember { mutableStateOf(false) }
    var reviewedJson by remember { mutableStateOf("") }
    var reviewedSkipped by remember { mutableIntStateOf(0) }
    val preview = remember(sub.importedRulesJson) { runCatching { SubscriptionRoutingRules.preview(sub.importedRulesJson.orEmpty()) }.getOrDefault(emptyList()) }
    val reviewed = remember(reviewedJson) { runCatching { SubscriptionRoutingRules.preview(reviewedJson) }.getOrDefault(emptyList()) }
    fun openRules() { reviewedJson = sub.importedRulesJson.orEmpty(); reviewedSkipped = sub.importedRulesSkipped; showRules = true }
    fun completed(result: Result<Unit>) { busy = false; error = result.exceptionOrNull()?.localizedMessage }
    HorizontalDivider()
    Text(strings.localized("Резерв рабочих серверов", "Working server reserve"), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(strings.localized("Сохранять исчезнувшие рабочие узлы", "Keep working nodes missing from updates"), Modifier.weight(1f))
        Switch(sub.retainWorkingNodes, { value -> busy = true; onRetention(value, ::completed) }, enabled = !busy)
    }
    Text(String.format(strings.localized("До 100 узлов и до 30 дней после успешной проверки через туннель. Обычный TCP-пинг не считается подтверждением. В резерве: %d.", "Up to 100 nodes for up to 30 days after a successful tunnel check. TCP ping alone is insufficient. Reserved: %d."), sub.retainedNodeCount),
        color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
    if (sub.retainedNodeCount > 0) TextButton(onClick = { showPurge = true }, enabled = !busy) {
        Text(strings.localized("Очистить резерв этой подписки", "Clear this subscription's reserve"))
    }
    HorizontalDivider()
    Text(strings.localized("Правила подписки", "Subscription rules"), style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(strings.localized("Использовать правила", "Use subscription rules"), Modifier.weight(1f))
        Switch(sub.useSubscriptionRules, { value ->
            if (value) openRules() else { busy = true; onRules(false, null, ::completed) }
        }, enabled = !busy && (sub.useSubscriptionRules || preview.isNotEmpty() && sub.importedRulesSkipped == 0))
    }
    Text(when {
        sub.importedRulesSkipped > 0 -> String.format(strings.localized("Есть неподдерживаемые условия (%d). Этот набор нельзя включить целиком безопасно. Поддерживаемых правил: %d.", "Unsupported conditions: %d. This set cannot be enabled safely. Supported rules: %d."), sub.importedRulesSkipped, preview.size)
        preview.isEmpty() -> strings.localized("Правила не найдены. Для старой подписки сначала выполните обновление. Обычный список ссылок на серверы правил не содержит.",
            "No rules found. Refresh an older subscription first. A plain list of server links contains no routing rules.")
        else -> String.format(strings.localized("Правил: %d. Применяются только при подключении к серверу этой подписки. Изменение правил сбрасывает согласие.", "Rules: %d. Applied only when using a server from this subscription. Changed rules require approval again."), preview.size)
    }, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
    if (preview.isNotEmpty()) TextButton(onClick = ::openRules, enabled = !busy) { Text(strings.localized("Просмотреть правила", "Review rules")) }
    Text(strings.localized("Опции применяются сразу. При изменении URL правила и резерв прежнего источника сбрасываются.",
        "Options apply immediately. Changing the URL clears the old source's rules and reserve."), color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = colors.accentCrimson) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (showRules) AlertDialog(
        onDismissRequest = { if (!busy) showRules = false },
        title = { Text(String.format(strings.localized("Правила: %s", "Rules: %s"), sub.name)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(strings.localized("DIRECT отправляет совпавший трафик напрямую. PROXY использует выбранный сервер. DNS, настройки приложений и выбор серверов внутри групп не переносятся. Ваши собственные правила имеют приоритет.",
                        "DIRECT sends matching traffic directly. PROXY uses the selected server. DNS, per-app settings and server choices within groups are not imported. Your own rules take priority."), color = colors.accentAmber)
                }
                items(reviewed) { rule -> Column {
                    Text(rule.target.uppercase(), style = MaterialTheme.typography.labelLarge)
                    Text(rule.conditions, style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                } }
                if (reviewedSkipped > 0) item { Text(strings.localized("Есть неподдерживаемые правила. Включение заблокировано.", "Unsupported rules are present. Activation is blocked."), color = colors.accentCrimson) }
                if (reviewedJson != sub.importedRulesJson) item { Text(strings.localized("Подписка обновилась. Закройте окно и просмотрите новые правила.", "The subscription changed. Close this dialog and review the new rules."), color = colors.accentAmber) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && reviewed.isNotEmpty() && reviewedSkipped == 0 && reviewedJson == sub.importedRulesJson, onClick = {
            busy = true; onRules(true, reviewedJson) { result -> completed(result); if (result.isSuccess) showRules = false }
        }) { Text(strings.localized("Разрешить и включить", "Approve and enable")) } },
        dismissButton = { TextButton(onClick = { showRules = false }, enabled = !busy) { Text(strings.localized("Закрыть", "Close")) } }
    )
    if (showPurge) AlertDialog(onDismissRequest = { if (!busy) showPurge = false },
        title = { Text(strings.localized("Удалить резерв?", "Delete reserved nodes?")) },
        text = { Text(String.format(strings.localized("Будут удалены %d узлов, которых уже нет в актуальной подписке. Серверы из текущего ответа останутся.", "Deletes %d nodes missing from the current subscription. Current nodes remain."), sub.retainedNodeCount)) },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; onPurge {
            busy = false; error = it.exceptionOrNull()?.localizedMessage; if (it.isSuccess) showPurge = false
        } }) { Text(strings.localized("Удалить", "Delete")) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { showPurge = false }) { Text(strings.localized("Отмена", "Cancel")) } })
}
