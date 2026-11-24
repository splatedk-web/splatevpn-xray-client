package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.CloudBackupLinkImporter
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.launch
import com.danieldk.splatevpn.ui.components.SplateButton as Button

@Composable
fun CloudBackupLinkCard(importer: CloudBackupLinkImporter) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val state by importer.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(strings.localized("Восстановить по ссылке из облака", "Restore from a cloud link"), style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        Text(strings.localized("Вставьте ссылку на JSON-файл резервной копии. Клиент загрузит файл и покажет его состав перед восстановлением. Выбирать папку не нужно.",
            "Paste a link to a JSON backup file. The client downloads it and shows its contents before restoring. No folder selection is needed."), color = colors.textSecondary)
        OutlinedTextField(url, { url = it.take(8192) }, Modifier.fillMaxWidth(),
            label = { Text(strings.localized("Ссылка на резервную копию", "Backup file link")) }, singleLine = true, enabled = !state.busy,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
        TextButton(onClick = { clipboard.getText()?.text?.let { url = it.take(8192) } }, enabled = !state.busy) { Text(strings.localized("Вставить ссылку", "Paste link")) }
        OutlinedTextField(password, { password = it.take(1024) }, Modifier.fillMaxWidth(),
            label = { Text(strings.localized("Пароль, если копия зашифрована", "Password, if the backup is encrypted")) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !state.busy)
        Text(strings.localized("Google Drive, Яндекс Диск, Dropbox и прямые HTTPS-ссылки. Файл должен быть доступен по ссылке без входа в облачный аккаунт. Для обычной копии пароль не требуется.",
            "Google Drive, Yandex Disk, Dropbox and direct HTTPS links. The file must be accessible without signing into a cloud account. Plain backups need no password."), color = colors.textTertiary, style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            scope.launch {
                val secret = password.toCharArray()
                val result = try { importer.load(url, secret) } finally { secret.fill('\u0000') }
                if (result.isSuccess) password = ""
            }
        }, enabled = url.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.needsPassword) strings.localized("Открыть загруженную копию", "Open downloaded backup") else strings.localized("Загрузить и просмотреть", "Download and review"))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = if (state.needsPassword) colors.accentAmber else colors.accentCrimson) }
    }
}
