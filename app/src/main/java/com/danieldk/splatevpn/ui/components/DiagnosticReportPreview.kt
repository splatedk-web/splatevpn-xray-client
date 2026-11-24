package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.theme.*

@Composable
internal fun DiagnosticReportPreview(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.localized("Проверка отчёта", "Review diagnostic report")) },
        text = {
            Column {
                Text(strings.localized("Ссылки, ключи, IP и имена серверов скрыты автоматически. Проверьте оставшийся текст перед отправкой.", "Links, keys, IP addresses and server names are hidden automatically. Review the remaining text before sharing."))
                Spacer(Modifier.height(12.dp))
                SelectionContainer(Modifier.weight(1f, fill = false).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(report, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.textPrimary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("SplateVPN diagnostic", report))
                onDismiss()
            }) { Text(strings.localized("Копировать", "Copy")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(strings.localized("Закрыть", "Close")) }
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    context.startActivity(Intent.createChooser(intent, "SplateVPN diagnostic"))
                }) { Text(strings.localized("Поделиться", "Share")) }
            }
        },
        containerColor = colors.surface
    )
}
