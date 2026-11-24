package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateSwitch as Switch

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.CloudBackupManager
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized
import kotlinx.coroutines.launch
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton

@Composable
fun CloudBackupSettingsCard(manager: CloudBackupManager) {
    val strings = LocalAppStrings.current
    val saved by manager.settings.collectAsState()
    val colors = LocalAppThemeColors.current
    val scope = rememberCoroutineScope()
    var noticeError by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved.folderMigrationNotice) Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 16).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.localized("Автокопирование в папку отключено", "Folder auto-backup disabled"), style = MaterialTheme.typography.titleSmall)
            Text(strings.localized("Выбор папки убран из облачного сценария. Существующие файлы и пароль шифрования сохранены. Для восстановления вставьте ссылку на копию; для фоновых копий можно настроить WebDAV.",
                "Folder selection was removed from cloud backup. Existing files and the encryption password were kept. Restore using a backup link, or configure WebDAV for background backups."), color = colors.textSecondary)
            TextButton(onClick = { scope.launch { noticeError = manager.dismissFolderMigrationNotice().exceptionOrNull()?.message } }) {
                Text(strings.localized("Понятно", "Got it"))
            }
            noticeError?.let { Text(it, color = colors.accentCrimson) }
        }
        TextButton(onClick = { expanded = !expanded }) { Text(strings.localized(
            "Автокопии WebDAV · дополнительные настройки" + if (saved.enabled) " · включены" else "",
            "WebDAV automatic backups · advanced settings" + if (saved.enabled) " · enabled" else "")) }
        if (expanded) CloudBackupSettingsForm(manager)
    }
}

@Composable
private fun CloudBackupSettingsForm(manager: CloudBackupManager) {
    val saved by manager.settings.collectAsState()
    val state by manager.state.collectAsState()
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val scope = rememberCoroutineScope()
    var enabled by remember(saved.enabled) { mutableStateOf(saved.enabled) }
    var unmetered by remember(saved.unmeteredOnly) { mutableStateOf(saved.unmeteredOnly) }
    var hours by remember(saved.intervalHours) { mutableIntStateOf(saved.intervalHours) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { manager.load() }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.localized("Автокопии WebDAV", "WebDAV automatic backups"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Text(strings.localized("Зашифрованные копии каждые 6 или 24 часа. Восстановление выполняется после просмотра и подтверждения. Последние 10 снимков этого устройства сохраняются отдельно.",
            "Encrypted backups every 6 or 24 hours. Restore after review and confirmation. The last 10 snapshots from this device are kept separately."), color = colors.textSecondary)
        Text(strings.localized("Используется WebDAV, сохранённый в разделе переноса. При конфликте с другим устройством общий файл не перезаписывается: получите и проверьте его вручную.",
            "Uses WebDAV saved under transfer settings. A conflicting shared copy is kept: download and review it before syncing again."), color = colors.textSecondary)
        OutlinedTextField(password, { password = it.take(1024) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (saved.hasPassword) strings.localized("Новый пароль (пусто — оставить)", "New password (blank to keep)") else strings.localized("Пароль шифрования от 8 символов", "Encryption password, 8+ characters")) },
            visualTransformation = PasswordVisualTransformation(), enabled = !busy && !state.busy)
        if (password.isNotEmpty()) OutlinedTextField(confirm, { confirm = it.take(1024) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(strings.localized("Повторите пароль", "Confirm password")) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy && !state.busy)
        Text(strings.localized("Для фоновых копий пароль хранится зашифрованным ключом Android Keystore только на этом устройстве. Сохраните его отдельно: на другом устройстве он потребуется для восстановления.",
            "Background backup stores the password encrypted with Android Keystore on this device only. Keep it separately: another device needs it to restore."), style = MaterialTheme.typography.bodySmall, color = colors.textTertiary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(strings.localized("Автокопия включена", "Automatic backup enabled"), Modifier.weight(1f))
            Switch(enabled, { enabled = it }, enabled = !busy && !state.busy)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(strings.localized("Только нетарифицируемая сеть", "Unmetered network only"), Modifier.weight(1f))
            Switch(unmetered, { unmetered = it }, enabled = !busy && !state.busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 24).forEach { value -> FilterChip(hours == value, { hours = value },
                label = { Text(String.format(strings.localized("Каждые %d ч", "Every %d h"), value)) }, enabled = !busy && !state.busy) }
        }
        Button(onClick = {
            scope.launch {
                busy = true
                try {
                    if (password.isNotEmpty() && (password.length < 8 || password != confirm)) {
                        error = true; message = strings.localized("Пароли должны совпадать и содержать минимум 8 символов", "Passwords must match and contain at least 8 characters")
                    } else {
                        val secret = password.takeIf { it.isNotEmpty() }?.toCharArray()
                        val result = try { manager.save(saved.copy(enabled = enabled,
                            unmeteredOnly = unmetered, intervalHours = hours), secret) } finally { secret?.fill('\u0000') }
                        error = result.isFailure
                        message = result.exceptionOrNull()?.message ?: strings.localized("Настройки сохранены", "Settings saved")
                        if (result.isSuccess) { password = ""; confirm = "" }
                    }
                } finally { busy = false }
            }
        }, enabled = !busy && !state.busy) { Text(strings.localized("Сохранить настройки", "Save settings")) }
        OutlinedButton(onClick = { scope.launch { manager.backupNow() } }, enabled = saved.hasPassword && !saved.folderMigrationNotice && !busy && !state.busy) {
            Text(strings.localized("Создать копию сейчас", "Back up now"))
        }
        if (saved.hasPassword) TextButton(onClick = { scope.launch { manager.forget() } }, enabled = !busy && !state.busy) {
            Text(strings.localized("Отключить и забыть пароль", "Disable and forget password"))
        }
        if (saved.lastSuccessAt > 0) Text(strings.localized("Последняя копия: ", "Last backup: ") +
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(saved.lastSuccessAt)), color = colors.textSecondary)
        if (busy || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (saved.lastError.isNotBlank()) Text(saved.lastError, color = colors.accentCrimson)
        message?.let { Text(it, color = if (error) colors.accentCrimson else colors.accentEmerald) }
        if (state.message.isNotEmpty()) Text(state.message, color = if (state.error) colors.accentCrimson else colors.accentEmerald)
    }
}
