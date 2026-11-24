package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.NotificationPreferences
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.*

@Composable
internal fun NotificationSettingsCard(viewModel: MainViewModel) {
    val preferences by viewModel.notificationPreferences.collectAsState()
    val context = LocalContext.current
    NotificationSettingsContent(preferences, viewModel::setNotificationPreferences) { openNotificationSettings(context) }
}

@Composable
internal fun NotificationSettingsContent(p: NotificationPreferences, onChange: (NotificationPreferences) -> Unit, onSystemSettings: () -> Unit) {
    val r = LocalAppResources.current; val colors = LocalAppThemeColors.current
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(r.getString(R.string.notice_title), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        NotificationToggle(r.getString(R.string.notice_health), p.healthAlerts, "health") { onChange(p.copy(healthAlerts = it)) }
        NotificationToggle(r.getString(R.string.notice_connection), p.connectionIssues, "connection", p.healthAlerts) { onChange(p.copy(connectionIssues = it)) }
        NotificationToggle(r.getString(R.string.notice_dns), p.dnsIssues, "dns", p.healthAlerts) { onChange(p.copy(dnsIssues = it)) }
        NotificationToggle(r.getString(R.string.notice_access), p.accessIssues, "access", p.healthAlerts) { onChange(p.copy(accessIssues = it)) }
        NotificationToggle(r.getString(R.string.notice_device), p.deviceIssues, "device", p.healthAlerts) { onChange(p.copy(deviceIssues = it)) }
        Text(r.getString(R.string.notice_banner_hint), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider(color = colors.borderSubtle)
        Text(r.getString(R.string.notice_status), color = colors.textPrimary, style = MaterialTheme.typography.titleSmall)
        NotificationToggle(r.getString(R.string.notice_speed), p.showSpeed, "speed") { onChange(p.copy(showSpeed = it)) }
        NotificationToggle(r.getString(R.string.notice_server), p.showServer, "server") { onChange(p.copy(showServer = it)) }
        NotificationToggle(r.getString(R.string.notice_duration), p.showDuration, "duration") { onChange(p.copy(showDuration = it)) }
        Text(r.getString(R.string.notice_required), color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        SplateTextButton(onClick = onSystemSettings, modifier = Modifier.testTag("notificationSystemSettings")) { Text(r.getString(R.string.notice_android)) }
    }
}
@Composable
private fun NotificationToggle(title: String, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = LocalAppThemeColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), color = if (enabled) colors.textPrimary else colors.textSecondary)
        SplateSwitch(checked, onChange, enabled = enabled, modifier = Modifier.testTag("notification:$tag"))
    }
}
