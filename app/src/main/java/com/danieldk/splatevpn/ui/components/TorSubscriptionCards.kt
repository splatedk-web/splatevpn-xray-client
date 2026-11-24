package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.TOR_TOP100_SUBSCRIPTION
import com.danieldk.splatevpn.data.TorConfigParser
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.vpn.VpnState
import kotlinx.coroutines.launch

@Composable
internal fun TorSubscriptionCards(viewModel: MainViewModel, showAutoUpdate: Boolean = false) {
    val settings by viewModel.torSettings.collectAsState()
    val vpnState by viewModel.vpnState.collectAsState()
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val scope = rememberCoroutineScope()
    val editable = !settings.enabled || vpnState in setOf(VpnState.DISCONNECTED, VpnState.FAILED)
    val sources = settings.bridgeSubscriptions
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var activeUrl by remember { mutableStateOf<String?>(null) }
    var savingAutoUpdate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canAct = editable && activeUrl == null && !savingAutoUpdate

    fun runFor(url: String, action: suspend () -> Result<*>) {
        if (!editable || activeUrl != null || savingAutoUpdate) return
        activeUrl = url
        error = null
        scope.launch {
            try {
                error = action().exceptionOrNull()?.localizedMessage
            } finally {
                activeUrl = null
            }
        }
    }

    Column(Modifier.fillMaxWidth().testTag("torSubscriptions"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.localized("Подписки Tor", "Tor subscriptions"), Modifier.weight(1f),
                color = colors.textPrimary, style = MaterialTheme.typography.titleMedium)
            SplateTextButton(onClick = { adding = true }, enabled = canAct,
                modifier = Modifier.testTag("torAddSubscription")) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(strings.add)
            }
        }
        if (!editable) Text(strings.localized("Отключите Tor, чтобы изменить подписки.",
            "Disconnect Tor to change subscriptions."), color = colors.textSecondary,
            style = MaterialTheme.typography.bodySmall)

        for (source in sources) key(source.url) {
            val isTop100 = source.url == TOR_TOP100_SUBSCRIPTION
            val pending = activeUrl == source.url
            val needsDownload = source.bridges.isEmpty()
            val refresh = if (needsDownload) strings.localized("Загрузить", "Download")
                else strings.localized("Обновить", "Refresh")
            Row(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16, innerSheen = true)
                .padding(start = 14.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (isTop100) "Tor TOP-100" else source.url.removePrefix("https://").removePrefix("http://"),
                        color = colors.textPrimary, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (needsDownload) strings.localized("Мосты ещё не загружены", "Bridges not downloaded")
                        else strings.localized("Мостов: %d", "Bridges: %d").format(source.bridges.size),
                        color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                if (pending) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else if (needsDownload) {
                    SplateTextButton(onClick = { runFor(source.url) { viewModel.updateTorSubscription(source.url) } },
                        enabled = canAct, modifier = Modifier.testTag("torDownloadTop100")) { Text(refresh) }
                } else {
                    IconButton(onClick = { runFor(source.url) { viewModel.updateTorSubscription(source.url) } },
                        enabled = canAct, modifier = Modifier.tvFocusOutline()) {
                        Icon(Icons.Default.Refresh, refresh)
                    }
                }
                IconButton(onClick = { removing = source.url }, enabled = canAct, modifier = Modifier.tvFocusOutline()) {
                    Icon(Icons.Default.DeleteOutline, strings.delete)
                }
            }
        }
        if (showAutoUpdate && sources.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.localized("Автообновление каждые 12 часов", "Auto-update every 12 hours"),
                    Modifier.weight(1f), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                SplateSwitch(checked = settings.autoUpdate, enabled = canAct, onCheckedChange = {
                    savingAutoUpdate = true
                    error = null
                    scope.launch {
                        try {
                            error = viewModel.saveTorSettings(settings.copy(autoUpdate = it)).exceptionOrNull()?.localizedMessage
                        } finally {
                            savingAutoUpdate = false
                        }
                    }
                })
            }
        }
        error?.let { Text(it, color = colors.accentCrimson, style = MaterialTheme.typography.bodySmall) }
    }

    if (adding) AddTorSubscriptionDialog(viewModel, enabled = canAct, onDismiss = { adding = false })
    removing?.let { url ->
        SplateAlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(strings.localized("Удалить подписку Tor?", "Delete Tor subscription?")) },
            text = { Text(strings.localized("Мосты, доступные только в этой подписке, тоже удалятся.",
                "Bridges provided only by this subscription will also be removed.")) },
            confirmButton = { SplateTextButton(enabled = canAct, onClick = {
                removing = null
                runFor(url) { viewModel.removeTorSubscription(url) }
            }) { Text(strings.delete) } },
            dismissButton = { SplateTextButton(onClick = { removing = null }) { Text(strings.cancel) } }
        )
    }
}

@Composable
internal fun AddTorSubscriptionDialog(
    viewModel: MainViewModel,
    enabled: Boolean,
    onDismiss: () -> Unit,
    initialUrl: String = ""
) {
    val settings by viewModel.torSettings.collectAsState()
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasTop100 = settings.bridgeSubscriptions.any { it.url == TOR_TOP100_SUBSCRIPTION }
    val canAdd = enabled && !busy && settings.bridgeSubscriptions.size < TorConfigParser.MAX_SUBSCRIPTIONS

    fun add(action: suspend () -> Result<*>) {
        if (!canAdd || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                action().fold(
                    onSuccess = { onDismiss() },
                    onFailure = { error = it.localizedMessage }
                )
            } finally {
                busy = false
            }
        }
    }

    SplateAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.localized("Добавить подписку Tor", "Add Tor subscription")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), enabled = canAdd, singleLine = true,
                    label = { Text(strings.localized("Ссылка HTTPS", "HTTPS URL")) })
                if (!hasTop100) SplateOutlinedButton(onClick = { add { viewModel.addTorTop100Subscription() } },
                    enabled = canAdd, modifier = Modifier.fillMaxWidth()) { Text("Tor TOP-100") }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (settings.bridgeSubscriptions.size >= TorConfigParser.MAX_SUBSCRIPTIONS) {
                    Text(strings.localized("Достигнут лимит подписок", "Subscription limit reached"),
                        color = colors.textSecondary)
                }
                error?.let { Text(it, color = colors.accentCrimson) }
            }
        },
        confirmButton = { SplateTextButton(onClick = { add { viewModel.updateTorSubscription(url) } }, enabled = canAdd && url.isNotBlank(),
            modifier = Modifier.testTag("torLoadSubscription")) { Text(strings.add) } },
        dismissButton = { SplateTextButton(onClick = onDismiss, enabled = !busy) { Text(strings.cancel) } }
    )
}
