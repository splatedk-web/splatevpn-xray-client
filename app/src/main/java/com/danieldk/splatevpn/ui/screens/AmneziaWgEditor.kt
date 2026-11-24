package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.data.AmneziaWg
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.ui.components.ToolDialog
import com.danieldk.splatevpn.ui.components.SplateButton
import com.danieldk.splatevpn.ui.components.SplateTextButton
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.LocalAppResources

@Composable
internal fun AmneziaWgEditor(server: ServerNode, onDismiss: () -> Unit, onSave: (ServerNode, (Result<Unit>) -> Unit) -> Unit) {
    val r = LocalAppResources.current
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    var name by remember(server.id) { mutableStateOf(server.name) }
    var conf by remember(server.id) { mutableStateOf(server.amneziaWg?.let(AmneziaWg::conf).orEmpty()) }
    var visible by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = remember(conf) { runCatching { AmneziaWg.parse(conf) } }
    ToolDialog(onDismissRequest = { if (!saving) onDismiss() }, maxWidth = 540.dp) {
        Text("AmneziaWG", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text(strings.fieldName) }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("awgName"))
            Text(r.getString(R.string.awg_profile_help), color = colors.textSecondary)
            Row {
                Checkbox(visible, { visible = it }, enabled = !saving, modifier = Modifier.testTag("awgReveal"))
                Text(r.getString(R.string.awg_show_keys), modifier = Modifier.padding(top = 12.dp))
            }
            OutlinedTextField(conf, { conf = it; error = null }, label = { Text(r.getString(R.string.awg_config)) }, enabled = !saving,
                minLines = 7, maxLines = 12, modifier = Modifier.fillMaxWidth().testTag("awgConfig"),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), isError = parsed.isFailure)
            (error ?: parsed.exceptionOrNull()?.message)?.let { Text(it, color = colors.accentCrimson) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SplateTextButton(onClick = onDismiss, enabled = !saving) { Text(strings.cancel) }
            SplateButton(enabled = !saving && name.isNotBlank() && parsed.isSuccess, onClick = {
                val profile = parsed.getOrThrow(); val (host, port) = AmneziaWg.endpoint(profile.endpoint)
                saving = true
                onSave(server.copy(name = name.trim(), address = host, port = port, network = "udp", amneziaWg = profile, rawLink = "", fullConfigJson = "")) { result ->
                    saving = false; result.onSuccess { onDismiss() }.onFailure { error = it.message ?: strings.invalidLinkFormat }
                }
            }, modifier = Modifier.testTag("awgSave")) { Text(strings.save) }
        }
    }
}
