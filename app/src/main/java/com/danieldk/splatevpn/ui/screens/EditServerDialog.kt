package com.danieldk.splatevpn.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerDialog(
    server: ServerNode,
    onDismiss: () -> Unit,
    onSave: (ServerNode) -> Unit
) {
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current

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

    val scrollState = rememberScrollState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(20.dp))
                .border(1.dp, colors.borderMedium, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
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
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = colors.borderSubtle)
                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Form
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Node Name
                    EditTextField(
                        label = strings.fieldName,
                        value = name,
                        onValueChange = { name = it }
                    )

                    // Protocol Selector
                    Text(
                        text = strings.fieldProtocol,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val protocols = listOf("vless", "vmess", "trojan", "shadowsocks")
                        protocols.forEach { p ->
                            val selected = protocol == p
                            SelectChip(
                                label = p.uppercase(),
                                isSelected = selected,
                                modifier = Modifier.weight(1f)
                            ) {
                                protocol = p
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
                                onValueChange = { address = it }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            EditTextField(
                                label = strings.fieldPort,
                                value = portText,
                                onValueChange = { portText = it }
                            )
                        }
                    }

                    // Network Transport (type)
                    Text(
                        text = strings.fieldNetwork,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val networks = listOf("ws", "tcp", "xhttp", "grpc")
                        networks.forEach { net ->
                            val selected = network == net
                            SelectChip(
                                label = net.uppercase(),
                                isSelected = selected,
                                modifier = Modifier.weight(1f)
                            ) {
                                network = net
                            }
                        }
                    }

                    // Security Mode
                    Text(
                        text = strings.fieldSecurity,
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val securities = listOf("none", "tls", "reality")
                        securities.forEach { sec ->
                            val selected = security == sec
                            SelectChip(
                                label = sec.uppercase(),
                                isSelected = selected,
                                modifier = Modifier.weight(1f)
                            ) {
                                security = sec
                            }
                        }
                    }

                    // SNI Domain
                    EditTextField(
                        label = strings.fieldSni,
                        value = sni,
                        onValueChange = { sni = it },
                        placeholder = "domain.com (для Cloudflare/TLS)"
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
                            placeholder = "Host HTTP заголовок"
                        )
                    }

                    // UUID / Password
                    EditTextField(
                        label = if (protocol == "trojan" || protocol == "shadowsocks") "Password" else strings.fieldUuid,
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

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = colors.borderSubtle)
                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(strings.cancel, color = colors.textSecondary)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val parsedPort = portText.trim().toIntOrNull() ?: server.port
                            val updated = server.copy(
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
                            onSave(updated)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = strings.save,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) colors.accentCyan.copy(alpha = 0.2f) else colors.surfaceElevated)
            .border(1.dp, if (isSelected) colors.accentCyan else colors.borderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) colors.accentCyan else colors.textPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun EditTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = ""
) {
    val colors = LocalAppThemeColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, fontSize = 12.sp, color = colors.textTertiary) }
            } else null,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentCyan,
                unfocusedBorderColor = colors.borderSubtle,
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                cursorColor = colors.accentCyan
            ),
            shape = RoundedCornerShape(10.dp)
        )
    }
}
