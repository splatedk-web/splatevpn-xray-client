package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.*
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.*
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class QuickPickerProjection(
    val candidates: List<QuickServerCandidate> = emptyList(),
    val measurements: Map<String, CandidateMeasurement> = emptyMap(),
    val revision: Int = -1
)

@Composable
internal fun QuickServerPickerDialog(viewModel: MainViewModel, onDismiss: () -> Unit, onAllServers: (() -> Unit)?) {
    val catalog by viewModel.quickServerCatalog.collectAsState()
    val nodes = catalog?.nodes.orEmpty()
    val subscriptions = catalog?.subscriptions.orEmpty()
    val proofs by viewModel.verifiedServerDelays.collectAsState()
    val measurements by viewModel.candidateMeasurements.collectAsState()
    val preferences by viewModel.balancerPreferences.collectAsState()
    val disabled by viewModel.disabledServerIds.collectAsState()
    val selected by viewModel.selectedServer.collectAsState()
    val tor by viewModel.torSettings.collectAsState()
    var source by remember { mutableStateOf(viewModel.quickServerSource()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var revision by remember { mutableIntStateOf(0) }
    var snapshot by remember(source, preferences.goal, preferences.metric) { mutableStateOf<QuickServerListSnapshot?>(null) }
    var displayedRevision by remember(source, preferences.goal, preferences.metric) { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val strings = LocalAppStrings.current
    val isPinging by viewModel.isPinging.collectAsState()
    fun doRefresh() {
        if (busy || isPinging) return
        revision++
        now = System.currentTimeMillis()
        viewModel.refreshQuickServers(source) {
            revision++
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val projection by key(source, preferences.goal, preferences.metric) {
        produceState(QuickPickerProjection(), nodes, proofs, measurements, preferences, now, subscriptions, disabled, revision) {
            val generation = revision
            value = QuickPickerProjection(
                viewModel.quickServerCandidates(nodes, proofs, source, now, subscriptions),
                viewModel.quickCandidateMeasurements(nodes), generation
            )
        }
    }
    val eligible = projection.candidates
    val presentIds = remember(nodes) { nodes.mapTo(hashSetOf()) { it.id } }
    LaunchedEffect(eligible, presentIds, source, revision, projection.revision, isPinging) {
        if (projection.revision == revision && !isPinging && displayedRevision != revision) {
            snapshot = QuickServerListSnapshot.capture(eligible)
            displayedRevision = revision
        } else {
            snapshot = QuickServerListSnapshot.publish(snapshot, eligible, presentIds, projection.revision, revision)
        }
    }
    val displayed = remember(snapshot, presentIds) { snapshot?.visible(presentIds).orEmpty() }
    val available = snapshot?.available(eligible).orEmpty()
    fun choose(id: String?) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try {
                val fresh = viewModel.quickServerCandidates(nodes, proofs, source, System.currentTimeMillis(), subscriptions)
                if (id != null && id !in snapshot?.available(fresh).orEmpty()) {
                    error = strings.localized("Результат устарел. Обновите список.", "The result expired. Refresh the list.")
                } else {
                    if (id != null && id == selected?.id && !tor.enabled) {
                        viewModel.setAutoSelect(false)
                        onDismiss()
                        return@launch
                    }
                    val result = if (id == null) viewModel.selectConnectionMode(!tor.enabled) else viewModel.selectVpnServer(id)
                    if (result.isSuccess) onDismiss()
                    else error = strings.localized("Не удалось переключить подключение.", "Could not switch the connection.")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { busy = false }
        }
    }
    ToolDialog(onDismissRequest = onDismiss) {
        QuickServerPickerContent(displayed, source, if (tor.enabled) null else selected?.id, tor.enabled, busy, error,
            onSource = { source = it; viewModel.setQuickServerSource(it) }, onSelect = ::choose,
            onRefresh = ::doRefresh, onAllServers = onAllServers, onDismiss = onDismiss,
            availableIds = available, measurements = projection.measurements, isRefreshing = isPinging, metric = preferences.metric, speedBudgetResumesAt = viewModel.speedBudgetResumesAt())
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ColumnScope.QuickServerPickerContent(
    candidates: List<QuickServerCandidate>, source: QuickServerSource, selectedId: String?, torMode: Boolean,
    busy: Boolean, error: String?, onSource: (QuickServerSource) -> Unit, onSelect: (String?) -> Unit,
    onRefresh: () -> Unit, onAllServers: (() -> Unit)?, onDismiss: () -> Unit,
    availableIds: Set<String> = candidates.mapTo(hashSetOf()) { it.server.id },
    measurements: Map<String, CandidateMeasurement> = emptyMap(),
    isRefreshing: Boolean = false,
    metric: BalancerMetric = BalancerMetric.LATENCY,
    speedBudgetResumesAt: Long? = null
) {
    val tv = LocalTvControls.current
    val initialFocus = remember { FocusRequester() }
    val input = LocalInputModeManager.current
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
        LaunchedEffect(tv) { if (tv) { input.requestInputMode(InputMode.Keyboard); withFrameNanos { }; initialFocus.requestFocus() } }
        if (maxHeight < 360.dp) {
            LazyColumn(Modifier.fillMaxWidth().testTag("quickServerList"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { QuickServerHeader(busy, isRefreshing, false, onRefresh, onDismiss, metric == BalancerMetric.DOWNLOAD, speedBudgetResumesAt) }
                item { QuickServerSources(source, busy, initialFocus, onSource) }
                if (candidates.isEmpty()) item { QuickServerEmptyState() }
                items(candidates, key = { it.server.id }) { QuickServerRow(it, selectedId, busy, it.server.id in availableIds, onSelect, measurements[it.server.id], metric == BalancerMetric.DOWNLOAD) }
                error?.let { message -> item { Text(message, color = LocalAppThemeColors.current.accentCrimson) } }
                item { QuickServerActions(torMode, busy, onSelect, onAllServers) }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QuickServerHeader(busy, isRefreshing, true, onRefresh, onDismiss, metric == BalancerMetric.DOWNLOAD, speedBudgetResumesAt)
                QuickServerSources(source, busy, initialFocus, onSource)
                LazyColumn(Modifier.weight(1f, fill = false).testTag("quickServerList"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (candidates.isEmpty()) item { QuickServerEmptyState() }
                    items(candidates, key = { it.server.id }) { QuickServerRow(it, selectedId, busy, it.server.id in availableIds, onSelect, measurements[it.server.id], metric == BalancerMetric.DOWNLOAD) }
                }
                error?.let { Text(it, color = LocalAppThemeColors.current.accentCrimson) }
                QuickServerActions(torMode, busy, onSelect, onAllServers)
            }
        }
    }
}

@Composable
private fun QuickServerHeader(busy: Boolean, isRefreshing: Boolean, showDescription: Boolean, onRefresh: () -> Unit, onDismiss: () -> Unit, speedMode: Boolean = false, speedBudgetResumesAt: Long? = null) {
    val s = LocalAppStrings.current
    val c = LocalAppThemeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(s.localized("Быстрый выбор", "Quick selection"), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
            SplateIconButton(onClick = onRefresh, enabled = !busy && !isRefreshing) {
                Icon(Icons.Default.Refresh, s.localized("Обновить список", "Refresh list"), tint = if (busy || isRefreshing) c.textTertiary else c.accentCyan)
            }
            SplateIconButton(onClick = onDismiss, enabled = true) {
                Icon(Icons.Default.Close, s.localized("Закрыть", "Close"), tint = c.textSecondary)
            }
        }
        if (speedMode && speedBudgetResumesAt != null) {
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(speedBudgetResumesAt))
            Text(String.format(s.localized("Лимит трафика для замеров исчерпан. Новые замеры после %s. Лимит можно увеличить в настройках балансировщика.",
                "Speed-test traffic budget exhausted. New samples after %s. You can raise the budget in balancer settings."), time),
                style = MaterialTheme.typography.bodySmall, color = c.accentAmber)
        }
        if (showDescription) Text(if (speedMode) s.localized("До 10 узлов · до 6 замеров Mbps за обновление в пределах лимита трафика", "Up to 10 nodes · up to 6 Mbps samples per refresh within the traffic budget") else s.localized("До 10 узлов · Real ping за последние 10 минут", "Up to 10 nodes · Real ping from the last 10 minutes"),
            style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

@Composable
private fun QuickServerSources(source: QuickServerSource, busy: Boolean, initialFocus: FocusRequester,
    onSource: (QuickServerSource) -> Unit) {
    val s = LocalAppStrings.current
    val c = LocalAppThemeColors.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Leave room for two independently rounded text lines and the button padding.
    val height = maxOf(if (LocalTvControls.current) 56.dp else 48.dp, with(density) { 32.sp.toDp() } + 20.dp)
    Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        QuickServerSource.entries.forEach { option ->
            val label = when (option) {
                QuickServerSource.ALL -> s.localized("Все узлы", "All nodes")
                QuickServerSource.SUBSCRIPTIONS -> s.localized("Подписки", "Subscriptions")
                QuickServerSource.PERSONAL -> s.localized("Личные", "Personal")
            }
            val active = option == source
            SplateOutlinedButton(onClick = { onSource(option) }, enabled = !busy,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                border = BorderStroke(1.dp, if (active) c.accentCyan.copy(alpha = .7f) else c.borderSubtle),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (active) c.accentCyan else c.textSecondary),
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .then(if (option == QuickServerSource.ALL) Modifier.focusRequester(initialFocus) else Modifier)
                    .testTag("quickSource:${option.name}").semantics { selected = option == source }) {
                // Box bounds text width inside RowScope so word-wrap works at large font scales.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(label, Modifier.fillMaxWidth(), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun QuickServerEmptyState() {
    val s = LocalAppStrings.current
    Text(s.localized("Нет недавно проверенных серверов. Нажмите «Обновить список».",
        "No recently verified servers. Tap Refresh list."),
        Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = LocalAppThemeColors.current.textSecondary)
}

@Composable
private fun QuickServerRow(candidate: QuickServerCandidate, selectedId: String?, busy: Boolean, available: Boolean, onSelect: (String?) -> Unit, measurement: CandidateMeasurement? = null, speedMode: Boolean = false) {
    val s = LocalAppStrings.current
    val c = LocalAppThemeColors.current
    val active = candidate.server.id == selectedId
    val locale = LocalAppResources.current.configuration.locales[0]
    val time = remember(candidate.checkedAt, locale) {
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale).format(java.util.Date(candidate.checkedAt))
    }
    SplateOutlinedButton(onClick = { onSelect(candidate.server.id) }, enabled = !busy && available,
        contentPadding = PaddingValues(12.dp),
        border = BorderStroke(1.dp, if (active) c.accentCyan.copy(alpha = .65f) else c.borderSubtle),
        modifier = Modifier.fillMaxWidth().testTag("quickServer:${candidate.server.id}").semantics { selected = active }) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(candidate.server.name, color = if (available) c.textPrimary else c.textTertiary, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            measurement?.country?.let { country ->
                val countryName = remember(country, locale) { java.util.Locale("", country).getDisplayCountry(locale) }
                val flag = country.map { Character.toChars(0x1F1E6 + it.code - 'A'.code).concatToString() }.joinToString("")
                Text("$flag $countryName", color = c.textSecondary, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = c.accentCyan)
                Text(candidate.server.protocol.uppercase() + " · " + if (available) time else s.localized("Нужна проверка", "Needs verification"), color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
        val mbps = measurement?.freshDownload()?.takeIf { available }
        if (mbps != null) {
            Text(String.format(locale, "%.1f Mbps", mbps), color = c.accentCyan, style = MaterialTheme.typography.labelMedium)
        } else if (speedMode) {
            Text(s.localized("Нет замера Mbps", "Mbps not measured"), color = c.textSecondary, style = MaterialTheme.typography.labelSmall)
        }
        Text(if (available) s.localized("%d мс", "%d ms").format(candidate.delayMs) else "—", Modifier.widthIn(min = 48.dp),
            color = if (active) c.accentCyan else c.textPrimary, style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, maxLines = 1)
        }
    }
}

@Composable
private fun QuickServerActions(torMode: Boolean, busy: Boolean, onSelect: (String?) -> Unit, onAllServers: (() -> Unit)?) {
    val s = LocalAppStrings.current
    val c = LocalAppThemeColors.current
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SplateOutlinedButton(onClick = { onSelect(null) }, enabled = !busy,
            modifier = Modifier.weight(1f).fillMaxHeight().semantics { selected = torMode },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
            Icon(if (torMode) Icons.Default.CheckCircle else Icons.Default.Public, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (torMode) "VPN" else "Tor")
        }
        if (onAllServers != null) SplateButton(onClick = onAllServers, enabled = true, modifier = Modifier.weight(2f).fillMaxHeight(),
            colors = ButtonDefaults.buttonColors(containerColor = c.accentCyan)) {
            Text(s.localized("Все серверы", "All servers"), textAlign = TextAlign.Center)
        }
    }
}
