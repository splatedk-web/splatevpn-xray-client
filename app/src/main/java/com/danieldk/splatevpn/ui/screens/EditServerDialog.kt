package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.tvFocusOutline

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import com.danieldk.splatevpn.ui.components.SplateButton as Button

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.ToolDialog
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditServerDialog(
    server: ServerNode,
    onDismiss: () -> Unit,
    onSave: (ServerNode, (Result<Unit>) -> Unit) -> Unit
) {
    if (server.protocol.equals("amneziawg", true)) { AmneziaWgEditor(server, onDismiss, onSave); return }
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val connectionEditable = com.danieldk.splatevpn.data.ServerEditPolicy.supportsConnectionEditing(server)
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    var name by remember { mutableStateOf(server.name) }
    var protocol by remember { mutableStateOf(server.protocol.lowercase()) }
    var address by remember { mutableStateOf(server.address) }
    var portText by remember { mutableStateOf(server.port.toString()) }
    var network by remember { mutableStateOf(server.network.lowercase().ifEmpty { "tcp" }) }
    var security by remember { mutableStateOf(server.security.lowercase().ifEmpty { "none" }) }
    var sni by remember { mutableStateOf(server.sni) }
    var path by remember { mutableStateOf(server.path) }
    var host by remember { mutableStateOf(server.host) }
    var uuid by remember { mutableStateOf(server.uuid) }
    var flow by remember { mutableStateOf(server.flow) }
    var publicKey by remember { mutableStateOf(server.publicKey) }
    var shortId by remember { mutableStateOf(server.shortId) }
    var spiderX by remember { mutableStateOf(server.spiderX) }
    var fingerprint by remember { mutableStateOf(server.fingerprint.ifEmpty { "chrome" }) }

    val validation = remember(address, portText) {
        com.danieldk.splatevpn.data.validateServerEndpoint(address, portText)
    }
    val scrollState = rememberScrollState()

    ToolDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        maxWidth = 540.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Text(
            text = strings.editServerTitle,
            color = colors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${server.address}:${server.port}",
            color = colors.accentCyan,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = colors.borderSubtle)
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(scrollState)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            EditTextField(
                label = strings.fieldName,
                value = name,
                onValueChange = { if (!saving) name = it }
            )
            if (!connectionEditable) Text(strings.localized(
                "Для JSON-профиля здесь можно изменить название. Остальные параметры измените в исходном JSON и импортируйте его снова.",
                "You can rename a JSON profile here. To change connection settings, edit the original JSON and import it again."),
                color = colors.textSecondary, fontSize = 12.sp)
            if (connectionEditable) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.fieldProtocol,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val protocols = listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2")
                        protocols.forEach { p ->
                            val selected = protocol == p
                            SelectChip(
                                label = when (p) { "shadowsocks" -> "SS"; "hysteria2" -> "HY2"; else -> p.uppercase() },
                                isSelected = selected,
                                modifier = Modifier.defaultMinSize(minWidth = 52.dp)
                            ) {
                                protocol = p
                            }
                        }
                    }
                }

                // Host & Port
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(2.5f)) {
                        EditTextField(
                            label = strings.fieldAddress,
                            value = address,
                            onValueChange = { address = it },
                            error = if (!validation.isAddressValid) strings.fieldAddressError else null,
                            showErrorText = false
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        EditTextField(
                            label = strings.fieldPort,
                            value = portText,
                            onValueChange = { portText = it },
                            keyboardType = KeyboardType.Number,
                            error = if (validation.port == null) strings.fieldPortError else null,
                            showErrorText = false
                        )
                    }
                }

                if (!validation.isValid) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!validation.isAddressValid) Text(strings.fieldAddressError, color = colors.accentCrimson, fontSize = 12.sp)
                        if (validation.port == null) Text(strings.fieldPortError, color = colors.accentCrimson, fontSize = 12.sp)
                    }
                }

                // Network Transport (type)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.fieldNetwork,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val networks = listOf("ws", "tcp", "xhttp", "grpc")
                        networks.forEach { net ->
                            val selected = network == net
                            SelectChip(
                                label = net.uppercase(),
                                isSelected = selected,
                                modifier = Modifier.defaultMinSize(minWidth = 52.dp)
                            ) {
                                network = net
                            }
                        }
                    }
                }

                // Security Mode
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = strings.fieldSecurity,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val securities = listOf("none", "tls", "reality")
                        securities.forEach { sec ->
                            val selected = security == sec
                            SelectChip(
                                label = sec.uppercase(),
                                isSelected = selected,
                                modifier = Modifier.defaultMinSize(minWidth = 52.dp)
                            ) {
                                security = sec
                            }
                        }
                    }
                }

                HorizontalDivider(color = colors.borderSubtle)
                Text(
                    strings.localized("Дополнительные параметры", "Advanced options"),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )

                // SNI Domain
                EditTextField(
                    label = strings.fieldSni,
                    value = sni,
                    onValueChange = { sni = it },
                    placeholder = strings.fieldSniHint
                )

                // Path & Host Header (for WS, XHTTP, gRPC)
                if (network in listOf("ws", "xhttp", "grpc", "h2")) {
                    EditTextField(
                        label = strings.fieldPath,
                        value = path,
                        onValueChange = { path = it },
                        placeholder = if (network == "grpc") "gun (serviceName)" else "/ws"
                    )
                    EditTextField(
                        label = strings.fieldHost,
                        value = host,
                        onValueChange = { host = it },
                        placeholder = strings.fieldHostHint
                    )
                }

                // UUID / Password
                EditTextField(
                    label = if (protocol in listOf("trojan", "shadowsocks", "hysteria2")) strings.fieldPassword else strings.fieldUuid,
                    value = uuid,
                    onValueChange = { uuid = it }
                )

                // Reality specific fields
                if (security == "reality") {
                    EditTextField(
                        label = strings.fieldPublicKey,
                        value = publicKey,
                        onValueChange = { publicKey = it }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            EditTextField(
                                label = strings.fieldShortId,
                                value = shortId,
                                onValueChange = { shortId = it }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            EditTextField(
                                label = strings.fieldSpiderX,
                                value = spiderX,
                                onValueChange = { spiderX = it }
                            )
                        }
                    }
                }

                // Flow (xtls-rprx-vision for TCP)
                if (network == "tcp" && security in listOf("reality", "tls")) {
                    EditTextField(
                        label = "Flow (Vision)",
                        value = flow,
                        onValueChange = { flow = it },
                        placeholder = "xtls-rprx-vision"
                    )
                }

                // Fingerprint
                EditTextField(
                    label = strings.fieldFingerprint,
                    value = fingerprint,
                    onValueChange = { fingerprint = it },
                    placeholder = "chrome"
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = colors.borderSubtle)
        Spacer(modifier = Modifier.height(10.dp))

        saveError?.let { Text(it, color = colors.accentCrimson, fontSize = 12.sp) }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onDismiss,
                enabled = !saving,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(strings.cancel, color = colors.textSecondary)
            }
            Spacer(modifier = Modifier.width(10.dp))
            Button(
                enabled = !saving && (validation.isValid || !connectionEditable),
                onClick = {
                    if (connectionEditable && !validation.isValid) return@Button
                    val parsedPort = validation.port ?: server.port
                    val updated = if (!connectionEditable) server.copy(name = name.trim().ifEmpty { server.name }) else server.copy(
                        name = name.trim().ifEmpty { server.name },
                        protocol = protocol.trim(),
                        address = address.trim(),
                        port = parsedPort,
                        network = network.trim(),
                        security = security.trim(),
                        sni = sni.trim(),
                        path = path.trim(),
                        host = host.trim(),
                        uuid = uuid.trim(),
                        flow = flow.trim(),
                        publicKey = publicKey.trim(),
                        shortId = shortId.trim(),
                        spiderX = spiderX.trim(),
                        fingerprint = fingerprint.trim().ifEmpty { "chrome" }
                    )
                    saving = true
                    saveError = null
                    onSave(updated) { result ->
                        saving = false
                        result.onSuccess { onDismiss() }.onFailure { saveError = it.message ?: strings.invalidLinkFormat }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accentCyan,
                    disabledContainerColor = colors.surfaceElevated,
                    disabledContentColor = colors.textSecondary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = strings.save,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SelectChip(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    Box(
        modifier = modifier
            .liquidGlass(
                cornerRadius = 10.dp,
                backgroundColor = if (isSelected) colors.accentCyan.copy(alpha = 0.2f) else colors.glassSurface,
                borderBrush = Brush.linearGradient(
                    if (isSelected) listOf(colors.accentCyan.copy(alpha = 0.8f), colors.accentCyan.copy(alpha = 0.3f))
                    else listOf(colors.glassBorderLight.copy(alpha = 0.4f), colors.glassBorderDark.copy(alpha = 0.1f))
                ),
                specularSheen = isSelected
            )
            .tvFocusOutline().selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) colors.accentCyan else colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun EditTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    error: String? = null,
    showErrorText: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val colors = LocalAppThemeColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.ContentOrLtr),
            onValueChange = onValueChange,
            isError = error != null,
            supportingText = if (error != null && showErrorText) { { Text(error, color = colors.accentCrimson) } } else null,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, fontSize = 12.sp, color = colors.textSecondary) }
            } else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentCyan,
                unfocusedBorderColor = colors.borderSubtle,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accentCyan,
                focusedContainerColor = colors.glassSurface.copy(alpha = 0.35f),
                unfocusedContainerColor = colors.glassSurface.copy(alpha = 0.2f)
            ),
            shape = RoundedCornerShape(12.dp)
        )
    }
}
