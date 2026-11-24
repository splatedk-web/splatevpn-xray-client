package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.launch
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

@Composable
internal fun LocalProxySettingsCard(viewModel: MainViewModel) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val active by viewModel.localProxyAuthentication.collectAsState()
    var enabled by remember(active) { mutableStateOf(active) }
    var expanded by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(expanded) { if (expanded) {
        viewModel.getLocalProxyCredentials().let { username = it.username; password = it.password }
    } }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.localized("Локальный прокси · Mixed", "Local proxy · Mixed"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Text(strings.localized("HTTP и SOCKS на одном локальном порту. Защита паролем включена по умолчанию, данные создаются клиентом и автоматически передаются VPN. Открывать доступ из локальной сети для этого не требуется.",
            "HTTP and SOCKS on one local port. Authentication is on by default; the client generates credentials and passes them to the VPN automatically. LAN sharing is independent."), color = colors.textSecondary)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) strings.localized("Скрыть настройки", "Hide settings") else strings.localized("Настроить логин и пароль", "Configure credentials")) }
        if (expanded) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(strings.localized("Авторизация", "Authentication"), Modifier.weight(1f))
                Switch(enabled, { enabled = it }, enabled = !busy)
            }
            if (!enabled) Text(strings.localized("Без авторизации другие приложения на телефоне смогут обращаться к локальному прокси.",
                "Without authentication, other apps on this phone can access the local proxy."), color = colors.accentAmber)
            OutlinedTextField(username, { username = it.take(255) }, Modifier.fillMaxWidth(), label = { Text(strings.localized("Логин", "Username")) }, singleLine = true, enabled = !busy)
            OutlinedTextField(password, { password = it.take(255) }, Modifier.fillMaxWidth(), label = { Text(strings.localized("Пароль", "Password")) }, singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(), enabled = !busy)
            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) strings.localized("Скрыть пароль", "Hide password") else strings.localized("Показать пароль", "Show password")) }
            Button(onClick = {
                scope.launch { busy = true; try {
                    val result = viewModel.saveLocalProxyCredentials(enabled, username, password)
                    error = result.isFailure
                    message = result.exceptionOrNull()?.message ?: strings.localized("Сохранено. Активный VPN переподключится.", "Saved. An active VPN will reconnect.")
                } finally { busy = false } }
            }, enabled = !busy && username.isNotBlank() && password.isNotEmpty()) { Text(strings.localized("Сохранить", "Save")) }
            OutlinedButton(onClick = {
                scope.launch { busy = true; try {
                    runCatching { viewModel.regenerateLocalProxyCredentials() }.onSuccess {
                        username = it.username; password = it.password; error = false
                        message = strings.localized("Новые данные сохранены. Активный VPN переподключится.", "New credentials saved. An active VPN will reconnect.")
                    }.onFailure { error = true; message = it.message }
                } finally { busy = false } }
            }, enabled = !busy) { Text(strings.localized("Создать новые данные", "Generate new credentials")) }
            Text(strings.localized("Эти данные остаются на устройстве и не входят в облачные копии. Авторизация прокси не скрывает само VPN-соединение от оператора.",
                "These credentials stay on this device and are excluded from cloud backups. Proxy authentication does not hide the VPN connection from your ISP."), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
            message?.let { Text(it, color = if (error) colors.accentCrimson else colors.accentEmerald) }
        }
    }
}
