package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.LinkParser
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
internal fun SubscriptionDetailsDialog(
    sub: SubscriptionEntity,
    autoUpdateInterval: String,
    onDismiss: () -> Unit,
    onSave: (String, String, (Result<Unit>) -> Unit) -> Unit,
    history: List<com.danieldk.splatevpn.data.SubscriptionUpdateEvent> = emptyList(),
    onRetention: (Boolean, (Result<Unit>) -> Unit) -> Unit = { _, done -> done(Result.failure(IllegalStateException("Unavailable"))) },
    onRules: (Boolean, String?, (Result<Unit>) -> Unit) -> Unit = { _, _, done -> done(Result.failure(IllegalStateException("Unavailable"))) },
    onPurge: ((Result<Int>) -> Unit) -> Unit = { done -> done(Result.failure(IllegalStateException("Unavailable"))) },
    hwidEnabled: (String) -> Boolean? = { null },
    onHwidChange: (String, Boolean) -> Unit = { _, _ -> },
    getUserAgent: (String) -> com.danieldk.splatevpn.data.SubscriptionUserAgent? = { null },
    onUserAgentChange: (String, com.danieldk.splatevpn.data.SubscriptionUserAgent) -> Unit = { _, _ -> }
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var name by remember(sub.id) { mutableStateOf(sub.name) }
    var url by remember(sub.id) { mutableStateOf(sub.url) }
    val resources = com.danieldk.splatevpn.ui.theme.LocalAppResources.current
    val hwidEligible = hwidEnabled(url) != null
    var sendHwid by remember(sub.id, url) { mutableStateOf(hwidEnabled(url) == true) }
    var userAgent by remember(sub.id, url) { mutableStateOf(getUserAgent(url)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val normalized = remember(url) { LinkParser.normalizeSubscriptionUrl(url) }
    val validUrl = remember(url) {
        normalized?.let { runCatching { java.net.URI(it).host?.isNotBlank() == true }.getOrDefault(false) } == true ||
            LinkParser.isUnsupportedEncryptedSubscriptionLink(url)
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = colors.surface,
        title = { Text(strings.subscriptionDetails) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = if (name == sub.name) subscriptionDisplayName(sub.id, sub.name, strings) else name,
                    onValueChange = { name = it; error = null },
                    label = { Text(strings.subscriptionName) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving
                )
                OutlinedTextField(
                    value = url,
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr), onValueChange = { url = it; error = null },
                    label = { Text(strings.subscriptionUrl) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving,
                    isError = url.isNotBlank() && !validUrl,
                    supportingText = { if (url.isNotBlank() && !validUrl) Text(strings.subscriptionUrlInvalid) }
                )
                Text(strings.subscriptionEditHint, color = colors.textSecondary)
                if (hwidEligible) com.danieldk.splatevpn.ui.components.SubscriptionHwidSetting(
                    sendHwid, { sendHwid = it }, enabled = !saving)
                userAgent?.let {
                    com.danieldk.splatevpn.ui.components.SubscriptionUserAgentSetting(it, { userAgent = it }, !saving)
                }
                SubscriptionPolicySettings(sub, onRetention, onRules, onPurge)
                Text(
                    if (!sub.autoUpdate) strings.subscriptionManual
                    else if (autoUpdateInterval == "off") strings.subscriptionScheduleOff
                    else String.format(strings.subscriptionSchedule, autoUpdateInterval),
                    color = colors.textSecondary
                )
                fun timestamp(value: Long): String = java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(value))
                Text(if (sub.lastUpdated > 0) strings.localized("Успешно загружено: ", "Successfully downloaded: ") + timestamp(sub.lastUpdated)
                    else strings.localized("Ещё не загружалась из сети", "Not downloaded from the network yet"),
                    color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                if (sub.lastAttemptAt > 0) Text(
                    strings.localized("Последняя попытка: ", "Last attempt: ") + timestamp(sub.lastAttemptAt) +
                        if (sub.lastError.isNotBlank()) " • ${com.danieldk.splatevpn.ui.subscriptionErrorText(resources, sub.lastError)}" else strings.localized(" • Успешно", " • Successful"),
                    color = if (sub.lastError.isNotBlank()) colors.accentAmber else colors.textSecondary,
                    style = MaterialTheme.typography.bodySmall)
                Text(strings.localized("Дата загрузки меняется после успешного получения подписки. Если обновление не удалось, сохранённые серверы остаются доступны.",
                    "The download date changes after the subscription is retrieved successfully. Saved servers remain available if an update fails."),
                    color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = colors.accentCrimson) }
                if (history.isNotEmpty()) {
                    Text(strings.localized("Последние обновления", "Recent updates"), style = MaterialTheme.typography.titleSmall)
                    history.forEach { event ->
                        val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(event.attemptedAt))
                        Text("$time • " + if (event.successful) "${event.previousNodeCount} → ${event.nodeCount}" else com.danieldk.splatevpn.ui.subscriptionErrorText(resources, event.error),
                            color = if (event.successful) colors.textSecondary else colors.accentAmber,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && name.isNotBlank() && validUrl, onClick = {
                saving = true
                onSave(name.trim(), url.trim()) { result ->
                    saving = false
                    result.onSuccess {
                        if (hwidEligible) onHwidChange(url.trim(), sendHwid)
                        userAgent?.let { onUserAgentChange(url.trim(), it) }
                        onDismiss()
                    }.onFailure { error = it.localizedMessage ?: strings.subscriptionUrlInvalid }
                }
            }) { Text(if (saving) strings.updating else strings.save) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(strings.cancel) } }
    )
}
