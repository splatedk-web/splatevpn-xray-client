package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.theme.*
import kotlinx.coroutines.launch

@Composable
internal fun SettingsRecoveryDialog(viewModel: MainViewModel) {
    val notice by viewModel.settingsRecovery.collectAsState()
    val current = notice ?: return
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!current.needsDecision) viewModel.dismissSettingsRecoveryNotice() },
        title = { Text(strings.localized("Восстановление настроек", "Settings recovery")) },
        text = { Text(failure ?: if (current.needsDecision) {
            strings.localized("Настройки повреждены, рабочая копия недоступна. Оригинальные файлы сохранены. Создать новые настройки? Серверы и подписки сохранятся. До выбора VPN не подключается.", "Settings are damaged and no working snapshot is available. Original files were preserved. Create new settings? Servers and subscriptions will remain. VPN cannot connect until you decide.")
        } else strings.localized("Настройки восстановлены из последней рабочей копии. Повреждённый оригинал сохранён отдельно.", "Settings were restored from the last working snapshot. The damaged original was preserved separately.")) },
        confirmButton = { TextButton(onClick = {
            if (current.needsDecision) scope.launch {
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.acceptRecoveredSettingsDefaults() } }
                    .onFailure { failure = strings.localized("Не удалось записать настройки. Проверьте свободное место.", "Could not save settings. Check available storage.") }
            } else viewModel.dismissSettingsRecoveryNotice()
        }) { Text(if (current.needsDecision) { strings.localized("Создать новые", "Create new") } else "OK") } },
        dismissButton = {
            if (current.needsDecision) TextButton(onClick = {
                generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.finish()
            }) { Text(strings.localized("Выйти", "Exit")) }
        },
        containerColor = LocalAppThemeColors.current.surface
    )
}
