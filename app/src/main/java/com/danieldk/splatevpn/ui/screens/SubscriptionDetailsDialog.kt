package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.LinkParser
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
internal fun SubscriptionDetailsDialog(
    sub: SubscriptionEntity,
    autoUpdateInterval: String,
    onDismiss: () -> Unit,
    onSave: (String, String, (Result<Unit>) -> Unit) -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var name by remember(sub.id) { mutableStateOf(sub.name) }
    var url by remember(sub.id) { mutableStateOf(sub.url) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val normalized = remember(url) { LinkParser.normalizeSubscriptionUrl(url) }
    val validUrl = remember(url) {
        normalized?.let { runCatching { java.net.URI(it).host?.isNotBlank() == true }.getOrDefault(false) } == true ||
            LinkParser.isEncryptedSubscriptionLink(url)
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = colors.surface,
        title = { Text(strings.subscriptionDetails) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; error = null },
                    label = { Text(strings.subscriptionName) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving
                )
                OutlinedTextField(
                    value = url, onValueChange = { url = it; error = null },
                    label = { Text(strings.subscriptionUrl) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving,
                    isError = url.isNotBlank() && !validUrl,
                    supportingText = { if (url.isNotBlank() && !validUrl) Text(strings.subscriptionUrlInvalid) }
                )
                Text(strings.subscriptionEditHint, color = colors.textSecondary)
                Text(
                    if (!sub.autoUpdate) strings.subscriptionManual
                    else if (autoUpdateInterval == "off") strings.subscriptionScheduleOff
                    else String.format(strings.subscriptionSchedule, autoUpdateInterval),
                    color = colors.textSecondary
                )
                error?.let { Text(it, color = colors.accentCrimson) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && name.isNotBlank() && validUrl, onClick = {
                saving = true
                onSave(name.trim(), url.trim()) { result ->
                    saving = false
                    result.onSuccess { onDismiss() }.onFailure { error = it.localizedMessage ?: strings.subscriptionUrlInvalid }
                }
            }) { Text(if (saving) strings.updating else strings.save) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(strings.cancel) } }
    )
}
