package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton
import com.danieldk.splatevpn.ui.components.SplateCheckbox as Checkbox

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.data.BackupManager
import com.danieldk.splatevpn.data.DeviceTransferManager
import com.danieldk.splatevpn.data.WebDavSettings
import com.danieldk.splatevpn.data.WebDavSyncManager
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import com.danieldk.splatevpn.ui.components.SplateOutlinedButton as OutlinedButton
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

@Composable
fun DeviceTransferSettingsCard(manager: DeviceTransferManager, webDav: WebDavSyncManager, vpnActive: Boolean) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val outgoing by manager.outgoing.collectAsState()
    val saved by webDav.settings.collectAsState()
    val webState by webDav.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    var invitation by remember { mutableStateOf("") }
    var manualCodeExpanded by remember { mutableStateOf(false) }
    var shareDialogOpen by remember { mutableStateOf(false) }
    var sharePassword by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importFilePassword by remember { mutableStateOf("") }
    var webExpanded by remember { mutableStateOf(false) }
    var endpoint by remember(saved) { mutableStateOf(saved.endpoint) }
    var username by remember(saved) { mutableStateOf(saved.username) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    var backupPassword by remember { mutableStateOf("") }

    val importFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val result = manager.stageFileUri(uri, password = null)
                if (result.isSuccess) {
                    error = false
                    message = strings.localized("Файл прочитан. Подтвердите импорт.", "File loaded. Confirm the import.")
                } else {
                    val errText = result.exceptionOrNull()?.message.orEmpty()
                    if (errText.contains("password", ignoreCase = true) || errText.contains("encrypted", ignoreCase = true) || errText.contains("decrypt", ignoreCase = true)) {
                        pendingImportUri = uri
                        importFilePassword = ""
                    } else {
                        error = true
                        message = errText.ifBlank { strings.localized("Не удалось прочитать файл.", "Could not read file.") }
                    }
                }
            } finally { busy = false }
        }
    }

    fun shareConfigFile(passwordText: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val secret = passwordText.takeIf { it.isNotEmpty() }?.toCharArray()
                val result = try { manager.exportShareFile(secret) } finally { secret?.fill('\u0000') }
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "SplateVPN")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, strings.localized("Поделиться конфигурацией", "Share configuration")))
                    error = false
                    message = strings.localized("Файл готов к отправке.", "Configuration file is ready to share.")
                }.onFailure {
                    error = true
                    message = it.message ?: strings.localized("Не удалось подготовить файл.", "Could not prepare file.")
                }
            } finally { busy = false }
        }
    }

    fun receive(raw: String) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val result = manager.receiveInvitation(raw)
                error = result.isFailure
                message = result.fold({ preview ->
                    if (preview.received) strings.localized("Настройки получены. Проверьте состав перед импортом.", "Settings received. Review before importing.")
                    else strings.localized("Проверьте устройство-получатель и подтвердите отправку.", "Review the receiving device and confirm sending.")
                }, { it.message })
            } finally { busy = false }
        }
    }
    val tvControls = LocalTvControls.current
    val scan = rememberQrImporter(::receive)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.localized("Перенос на ПК / Android / TV", "Transfer to PC / Android / TV"), color = colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(strings.localized("Подключите телефон и ПК (или TV) к одной сети Wi-Fi. Передаются серверы, подписки, правила сайтов, Tor-мосты и настройки с шифрованием AES-256.",
                "Connect your phone and PC (or TV) to the same Wi-Fi network. Servers, subscriptions, site rules, Tor bridges and settings travel encrypted with AES-256."), color = colors.textSecondary)
            if (vpnActive) Text(strings.localized("Для применения полученных настроек отключите VPN.", "Disconnect VPN before applying received settings."), color = colors.accentAmber)
            val startSend: () -> Unit = {
                scope.launch {
                    busy = true
                    try {
                        val result = manager.startSending()
                        error = result.isFailure; message = result.exceptionOrNull()?.message
                    } finally { busy = false }
                }
            }
            val startReceive: () -> Unit = {
                scope.launch {
                    busy = true
                    try {
                        val result = manager.startReceiving()
                        error = result.isFailure; message = result.exceptionOrNull()?.message
                    } finally { busy = false }
                }
            }
            if (tvControls) {
                Button(onClick = startReceive, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.localized("Получить на это устройство / TV", "Receive on this device / TV"))
                }
                OutlinedButton(onClick = startSend, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.localized("Отправить настройки", "Send settings"))
                }
            } else {
                Button(onClick = scan, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.localized("Отсканировать QR с ПК / устройства", "Scan QR from PC / device"))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = startSend, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(strings.localized("Отправить (QR)", "Send (QR)"))
                    }
                    OutlinedButton(onClick = startReceive, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(strings.localized("Получить (QR)", "Receive (QR)"))
                    }
                }
            }
            outgoing?.let { transfer ->
                var bitmap by remember(transfer.invitation) { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(transfer.invitation) {
                    bitmap = withContext(Dispatchers.Default) { QrCodeGenerator.generateQrBitmap(transfer.invitation, 720) }
                }
                var now by remember(transfer) { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(transfer) { while (now < transfer.expiresAt) { delay(1000); now = System.currentTimeMillis() } }
                bitmap?.let {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Image(
                            it.asImageBitmap(),
                            strings.localized("QR сопряжения устройств", "Device pairing QR"),
                            Modifier.padding(12.dp).sizeIn(maxWidth = 260.dp).fillMaxWidth().aspectRatio(1f)
                        )
                    }
                }
                Text(if (transfer.receiving) strings.localized("Отсканируйте этот QR телефоном, с которого нужно перенести настройки, или вставьте код на ПК.",
                    "Scan this QR with the device containing your settings, or paste the code on your PC.")
                    else strings.localized("На ПК нажмите «Вставить код» или отсканируйте этот QR с другого телефона. Для переноса на ПК проще открыть «Получить с Android (QR)» на ПК и нажать «Отсканировать QR» здесь.",
                        "Paste the code on PC or scan this QR from another phone. To transfer to PC, open 'Receive from Android (QR)' on PC and tap 'Scan QR' here."), color = colors.textSecondary)
                Text(strings.localized("Код действует ещё ", "Code expires in ") + ((transfer.expiresAt - now).coerceAtLeast(0) / 1000) + strings.localized(" с · одно использование", " s · one use"), color = colors.accentCyan)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(transfer.invitation)) }) { Text(strings.localized("Скопировать код", "Copy code")) }
                    TextButton(onClick = manager::stopSending) { Text(strings.localized("Закрыть передачу", "Close transfer")) }
                }
            }
            TextButton(onClick = { manualCodeExpanded = !manualCodeExpanded }) {
                Text(if (manualCodeExpanded) strings.localized("Скрыть ввод кода", "Hide manual code") else strings.localized("Ввести код вручную", "Enter code manually"))
            }
            if (manualCodeExpanded) {
                OutlinedTextField(invitation, { invitation = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(strings.localized("Код или ссылка сопряжения", "Pairing code or link")) }, maxLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { receive(invitation.ifBlank { clipboard.getText()?.text.orEmpty() }) }, enabled = !busy) { Text(strings.localized("Принять код", "Use code")) }
                }
            }
            Text(strings.localized("QR содержит одноразовый ключ шифрования. Показывайте его только своему устройству. Импорт применяется после подтверждения.",
                "The QR contains a one-time encryption key. Show it only to your own device. Received settings require confirmation before applying."), color = colors.textTertiary)
        }

        Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings.localized("Перенос через файл", "Transfer via file"), color = colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(strings.localized("Выгрузите конфигурацию и отправьте через Telegram, Quick Share или любое другое приложение, либо импортируйте файл с другого устройства.",
                "Export your configuration and share it via Telegram, Quick Share, or any app, or import a file from another device."), color = colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { sharePassword = ""; shareDialogOpen = true },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(strings.localized("Поделиться", "Share file")) }
                OutlinedButton(
                    onClick = { importFilePicker.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f)
                ) { Text(strings.localized("Импорт файла", "Import file")) }
            }
        }

        if (shareDialogOpen) {
            AlertDialog(
                onDismissRequest = { shareDialogOpen = false },
                title = { Text(strings.localized("Поделиться конфигурацией", "Share configuration")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.localized("Задайте пароль шифрования (от 8 символов) или оставьте поле пустым для обычного JSON-файла.",
                            "Enter an encryption password (8+ characters) or leave blank for a plain JSON file."), color = colors.textSecondary)
                        OutlinedTextField(
                            value = sharePassword,
                            onValueChange = { sharePassword = it.take(1024) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.localized("Пароль (необязательно)", "Password (optional)")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        if (sharePassword.isNotEmpty() && sharePassword.length < 8) {
                            Text(strings.localized("Минимум 8 символов для шифрования.", "Use at least 8 characters for encryption."), color = colors.accentAmber)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val pwd = sharePassword
                            shareDialogOpen = false
                            shareConfigFile(pwd)
                        },
                        enabled = sharePassword.isEmpty() || sharePassword.length >= 8
                    ) { Text(strings.localized("Поделиться", "Share")) }
                },
                dismissButton = {
                    TextButton(onClick = { shareDialogOpen = false }) { Text(strings.localized("Отмена", "Cancel")) }
                }
            )
        }

        pendingImportUri?.let { uri ->
            AlertDialog(
                onDismissRequest = { pendingImportUri = null },
                title = { Text(strings.localized("Файл зашифрован", "Encrypted file")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(strings.localized("Введите пароль, заданный при экспорте файла.", "Enter the password used when exporting this file."), color = colors.textSecondary)
                        OutlinedTextField(
                            value = importFilePassword,
                            onValueChange = { importFilePassword = it.take(1024) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(strings.localized("Пароль файла", "File password")) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val pwd = importFilePassword.toCharArray()
                            pendingImportUri = null
                            scope.launch {
                                busy = true
                                try {
                                    val result = try { manager.stageFileUri(uri, pwd) } finally { pwd.fill('\u0000') }
                                    error = result.isFailure
                                    message = result.fold(
                                        { strings.localized("Файл расшифрован. Подтвердите импорт.", "File decrypted. Confirm the import.") },
                                        { it.message ?: strings.localized("Неверный пароль или повреждённый файл.", "Wrong password or damaged file.") }
                                    )
                                } finally { busy = false }
                            }
                        },
                        enabled = importFilePassword.isNotEmpty()
                    ) { Text(strings.localized("Открыть", "Open")) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingImportUri = null }) { Text(strings.localized("Отмена", "Cancel")) }
                }
            )
        }

        Column(Modifier.fillMaxWidth().liquidGlass(cornerRadius = 20).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { webExpanded = !webExpanded }) { Text(if (webExpanded) strings.localized("Скрыть WebDAV", "Hide WebDAV") else strings.localized("Синхронизация WebDAV", "WebDAV sync")) }
            if (webExpanded) {
                Text(strings.localized("В существующую папку WebDAV сохраняется зашифрованный файл splate-settings.v1.json. Отправка и получение запускаются вручную.",
                    "An encrypted splate-settings.v1.json file is stored in an existing WebDAV folder. Upload and download run when you choose."), color = colors.textSecondary)
                OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text(strings.localized("HTTPS URL папки WebDAV", "WebDAV folder HTTPS URL")) }, enabled = !busy && !webState.busy)
                OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text(strings.localized("Логин WebDAV", "WebDAV username")) }, enabled = !busy && !webState.busy)
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(strings.localized("Пароль WebDAV", "WebDAV password")) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy && !webState.busy)
                OutlinedButton(onClick = {
                    scope.launch {
                        val result = webDav.saveSettings(WebDavSettings(endpoint, username, password))
                        error = result.isFailure
                        message = result.fold({ strings.localized("WebDAV сохранён", "WebDAV saved") }, { it.message })
                    }
                }, enabled = !busy && !webState.busy) { Text(strings.localized("Сохранить WebDAV", "Save WebDAV")) }
                OutlinedTextField(backupPassword, { backupPassword = it }, Modifier.fillMaxWidth(),
                    label = { Text(strings.localized("Пароль шифрования копии (от 8 символов)", "Backup encryption password (8+ characters)")) },
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy && !webState.busy)
                Text(strings.localized("Пароль шифрования не сохраняется. Он нужен и на другом устройстве. Учётные данные WebDAV хранятся только на этом устройстве и не входят в передаваемую копию.",
                    "The encryption password is not saved. Enter the same password on the other device. WebDAV credentials stay on this device and are excluded from the backup."), color = colors.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val secret = backupPassword.toCharArray()
                            val result = try { webDav.upload(secret) } finally { secret.fill('\u0000') }
                            error = result.isFailure
                            message = result.fold({ strings.localized("Зашифрованная копия отправлена", "Encrypted backup uploaded") }, { it.message })
                        }
                    }, enabled = !busy && !webState.busy && backupPassword.length >= 8 && saved.endpoint.isNotBlank()) { Text(strings.localized("Заменить копию", "Replace copy")) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            val secret = backupPassword.toCharArray()
                            val result = try { webDav.download(secret) } finally { secret.fill('\u0000') }
                            error = result.isFailure
                            message = result.fold({ strings.localized("Копия получена. Подтвердите импорт.", "Backup downloaded. Confirm the import.") }, { it.message })
                        }
                    }, enabled = !busy && !webState.busy && backupPassword.length >= 8 && saved.endpoint.isNotBlank()) { Text(strings.localized("Получить", "Download")) }
                }
                webState.error?.let { Text(it, color = colors.accentCrimson) }
            }
        }
        if (busy || webState.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, color = if (error) colors.accentCrimson else colors.accentEmerald) }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DeviceTransferImportDialog(manager: DeviceTransferManager, vpnActive: Boolean) {
    val preview = manager.pending.collectAsState().value
    val strings = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val tvControls = LocalTvControls.current
    val inputMode = LocalInputModeManager.current
    val confirmFocus = remember { FocusRequester() }
    var replace by remember(preview) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(preview) { mutableStateOf<String?>(null) }
    if (preview != null) AlertDialog(
        onDismissRequest = { if (!busy) scope.launch { manager.dismissPending(preview) } },
        title = { Text(if (preview?.received == false) strings.localized("Передать настройки устройству?", "Send settings to this device?") else strings.localized("Импорт настроек", "Import settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val value = requireNotNull(preview)
                Text((if (value.received) strings.localized("Получено из ", "Received from ") else strings.localized("Устройство в локальной сети: ", "Device on your local network: ")) + value.source)
                Text(strings.localized("Серверов: ", "Servers: ") + value.servers + strings.localized(" · подписок: ", " · subscriptions: ") + value.subscriptions)
                value.subscriptionUpdateInterval?.let { interval ->
                    val label = when (interval) {
                        "off" -> strings.intervalOff
                        "6h" -> strings.interval6h
                        "12h" -> strings.interval12h
                        "24h" -> strings.interval24h
                        else -> strings.localized("%s ч", "%s h").format(interval.removeSuffix("h"))
                    }
                    Text(strings.localized("Автообновление подписок: ", "Subscription auto-update: ") + label)
                }
                if (value.customRoutes > 0) Text(strings.localized("Правил сайтов: ", "Site rules: ") + value.customRoutes)
                if (!value.encrypted) Text(strings.localized("Обычная JSON-копия без шифрования", "Plain JSON backup without encryption"))
                if (value.advancedRules > 0) Text(strings.localized("Составных правил: ", "Advanced rules: ") + value.advancedRules)
                if (value.includesTor) Text(strings.localized("Включены Tor и мосты", "Includes Tor and bridges"))
                if (value.includesProxyPassword) Text(strings.localized("Включены прокси и пароль раздачи", "Includes proxy settings and sharing password"))
                if (value.received) Text(strings.localized("Общие настройки будут применены к этому устройству. По умолчанию серверы добавляются к существующим.",
                    "General settings will be applied to this device. Servers are added to existing entries by default."))
                else Text(strings.localized("Будут переданы серверы, подписки, настройки Tor и пароль прокси. Продолжайте только для своего устройства, которое показало этот QR.",
                    "This sends servers, subscriptions, Tor settings and the proxy password. Continue only for your own device that displayed this QR."))
                if (value.received) {
                    Row(
                        Modifier.fillMaxWidth().tvFocusOutline(!busy).toggleable(replace, enabled = !busy, role = Role.Checkbox) { replace = it }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(replace, onCheckedChange = null, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.localized("Заменить существующие серверы и подписки", "Replace existing servers and subscriptions"))
                    }
                }
                if (vpnActive && value.received) Text(strings.localized("Сначала отключите VPN", "Disconnect VPN first"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (tvControls) {
                LaunchedEffect(preview) {
                    inputMode.requestInputMode(InputMode.Keyboard)
                    withFrameNanos { }
                    runCatching { confirmFocus.requestFocus() }
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            scope.launch {
                busy = true
                try { error = manager.applyPending(preview, if (replace) BackupManager.ImportMode.REPLACE else BackupManager.ImportMode.MERGE).exceptionOrNull()?.message }
                finally { busy = false }
            }
        }, modifier = Modifier.focusRequester(confirmFocus), enabled = !busy && (!vpnActive || preview?.received == false)) { Text(if (preview?.received == false) strings.localized("Передать настройки", "Send settings") else strings.localized("Применить настройки", "Apply settings")) } },
        dismissButton = { TextButton(onClick = { scope.launch { manager.dismissPending(preview) } }, enabled = !busy) { Text(strings.localized("Отмена", "Cancel")) } }
    )
}
