package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.BalancerMode
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.*

@Composable
internal fun SourcePrivacyCard(viewModel: MainViewModel, compact: Boolean = false) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val enabled by viewModel.sourceDownloadsViaVpnState.collectAsState()
    val updatesWifiOnly by viewModel.updatesWifiOnly.collectAsState()
    val checksWifiOnly by viewModel.checksWifiOnly.collectAsState()
    val preferences by viewModel.balancerPreferences.collectAsState()
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20, innerSheen = true).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(strings.localized("Сеть и проверки", "Network and checks"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        SettingsToggleContent(Icons.Default.Shield,
            strings.localized("Загрузка только через VPN", "Downloads through VPN only"),
            strings.localized("Подписки, мосты, геоданные, фильтры и WebDAV. Без перехода на прямое соединение.", "Subscriptions, bridges, geodata, filters and WebDAV. No fallback to a direct connection."),
            enabled, onChange = viewModel::setSourceDownloadsViaVpn)
        HorizontalDivider(color = colors.borderSubtle.copy(alpha = .6f))
        SettingsToggleContent(Icons.Default.Wifi,
            strings.localized("Обновления только по Wi-Fi", "Updates over Wi-Fi only"), "",
            updatesWifiOnly, onChange = viewModel::setUpdatesWifiOnly)
        SettingsToggleContent(Icons.Default.NetworkCheck,
            strings.localized("Пинг и проверки только по Wi-Fi", "Pings and checks over Wi-Fi only"), "",
            checksWifiOnly, onChange = viewModel::setChecksWifiOnly)
        if (checksWifiOnly || updatesWifiOnly) Text(strings.localized("На мобильной сети эти операции откладываются. Текущее VPN-соединение можно использовать; пропуск проверки не помечает сервер нерабочим.",
            "These operations wait on mobile data. You can still use the current VPN; a skipped check does not mark a server as dead."), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (!compact) {
            Text(strings.localized("Прямые проверки других серверов видны оператору. Режим «Только при сбое» убирает регулярный поиск более быстрого сервера; проверки при старте и сбое сохраняются.",
                "Direct checks of other servers are visible to your ISP. Failover mode stops regular searches for faster servers; startup and failure checks remain."), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { viewModel.setBalancerPreferences(preferences.copy(mode = BalancerMode.FAILOVER,
                healthIntervalSeconds = 10, failureConfirmations = 3, cooldownSeconds = 600)) }) {
                Text(strings.localized("Меньше фоновых проверок", "Reduce background checks"))
            }
            Text(strings.localized("Текущее поведение: ", "Current mode: ") + when (preferences.mode) {
                BalancerMode.BALANCED -> strings.localized("периодическая оптимизация", "periodic optimization")
                BalancerMode.FAILOVER -> strings.localized("переключение при сбое", "failover")
                BalancerMode.INITIAL_ONLY -> strings.localized("выбор только при подключении", "initial selection only")
            }, color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
