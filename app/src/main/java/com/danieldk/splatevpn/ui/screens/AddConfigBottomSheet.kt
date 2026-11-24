package com.danieldk.splatevpn.ui.screens

import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton

import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.localized
import com.danieldk.splatevpn.ui.components.SplateButton

private enum class DetectedPayloadType {
    NONE,
    SUBSCRIPTION_URL,
    SINGLE_SERVER,
    MULTIPLE_SERVERS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddConfigBottomSheet(
    viewModel: MainViewModel,
    initialText: String = "",
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val strings = LocalAppStrings.current
    val colors = LocalAppThemeColors.current
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var inputLink by remember { mutableStateOf(initialText) }
    val scanQr = com.danieldk.splatevpn.ui.components.rememberQrImporter { inputLink = it.trim() }
    val importScope = rememberCoroutineScope()
    val pickAwg = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importScope.launch {
            runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = ByteArray(65537); var count = 0
                    while (count < bytes.size) { val n = stream.read(bytes, count, bytes.size - count); if (n < 0) break; count += n }
                    require(count <= 65536) { "AWG profile exceeds 64 KiB" }
                    bytes.copyOf(count).toString(Charsets.UTF_8).also { com.danieldk.splatevpn.data.AmneziaWg.parse(it) }
                } ?: error("Cannot read AWG file")
            } }.onSuccess { inputLink = it }.onFailure { Toast.makeText(context, it.message ?: strings.invalidLinkFormat, Toast.LENGTH_LONG).show() }
        }
    }
    var subscriptionName by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }
    var userAgent by remember(inputLink) { mutableStateOf(viewModel.getSubscriptionUserAgent(inputLink)) }
    val hwidEligible = remember(inputLink) { viewModel.canSendSubscriptionHwid(inputLink) }
    var hwidEnabled by remember(inputLink) { mutableStateOf(viewModel.isSubscriptionHwidEnabled(inputLink)) }

    val detectedType = remember(inputLink) {
        val trimmed = inputLink.trim()
        when {
            trimmed.isEmpty() -> DetectedPayloadType.NONE
            com.danieldk.splatevpn.data.AmneziaWg.looksLike(trimmed) -> DetectedPayloadType.SINGLE_SERVER
            com.danieldk.splatevpn.data.LinkParser.normalizeSubscriptionUrl(trimmed) != null ||
                    com.danieldk.splatevpn.data.LinkParser.isEncryptedSubscriptionLink(trimmed) -> {
                DetectedPayloadType.SUBSCRIPTION_URL
            }
            com.danieldk.splatevpn.data.LinkParser.isBase64Content(trimmed) || trimmed.lines().count { it.isNotBlank() } > 1 -> {
                DetectedPayloadType.MULTIPLE_SERVERS
            }
            trimmed.startsWith("vless://", ignoreCase = true) ||
            trimmed.startsWith("hy2://", ignoreCase = true) ||
            trimmed.startsWith("hysteria2://", ignoreCase = true) ||
            trimmed.startsWith("vmess://", ignoreCase = true) ||
            trimmed.startsWith("ss://", ignoreCase = true) ||
            trimmed.startsWith("trojan://", ignoreCase = true) ||
            trimmed.startsWith("wireguard://", ignoreCase = true) ||
            trimmed.startsWith("socks5://", ignoreCase = true) ||
            trimmed.startsWith("socks://", ignoreCase = true) ||
            com.danieldk.splatevpn.data.LinkParser.unwrapImportLink(trimmed) != null -> {
                DetectedPayloadType.SINGLE_SERVER
            }
            else -> DetectedPayloadType.NONE
        }
    }

    fun executeImport() {
        val trimmed = inputLink.trim()
        if (trimmed.isBlank() || isOperating) return

        isOperating = true
        if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL) {
            viewModel.importSubscription(
                url = trimmed,
                title = subscriptionName.takeIf { it.isNotBlank() },
                hwid = hwidEnabled.takeIf { hwidEligible },
                userAgent = userAgent
            ) { result ->
                isOperating = false
                result.onSuccess { count ->
                    Toast.makeText(
                        context,
                        String.format(strings.subAddedSuccess, count),
                        Toast.LENGTH_SHORT
                    ).show()
                    onDismissRequest()
                }.onFailure { err ->
                    Toast.makeText(
                        context,
                        String.format(strings.errorPrefix, err.localizedMessage ?: err.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        } else {
            viewModel.addServersFromLinks(trimmed, onImportError = { message ->
                isOperating = false
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }) { count ->
                isOperating = false
                if (count > 0) {
                    val msg = if (count == 1) {
                        strings.serverAddedSuccess
                    } else {
                        String.format(strings.serversAddedCount, count)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onDismissRequest()
                } else {
                    Toast.makeText(context, strings.invalidLinkFormat, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!isOperating) onDismissRequest() },
        sheetState = sheetState,
        containerColor = colors.background,
        dragHandle = null
    ) {
        com.danieldk.splatevpn.ui.components.rememberAndApplyPopupWindowBlur(blurBehind = true, backgroundBlur = false)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Drag Indicator
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.borderMedium)
                    .align(Alignment.CenterHorizontally)
            )

            // Header with title and close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.unifiedAddTitle,
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = strings.unifiedAddSubtitle,
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .liquidGlass(cornerRadius = 14, innerSheen = true)
                        .tactileClickable(targetScale = 0.88f) {
                            if (!isOperating) onDismissRequest()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = strings.close,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            com.danieldk.splatevpn.ui.components.SplateTextButton(onClick = { pickAwg.launch(arrayOf("*/*")) }, enabled = !isOperating) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(com.danieldk.splatevpn.ui.theme.LocalAppResources.current.getString(com.danieldk.splatevpn.R.string.awg_import_file))
            }

            // Quick Actions: Large Tap Target Action Cards (48+ dp height)
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Card 1: Paste from Clipboard
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .liquidGlass(
                            cornerRadius = 14,
                            innerSheen = true,
                            elevated = true,
                            accentBorder = colors.accentCyan.copy(alpha = 0.45f)
                        )
                        .tactileClickable(targetScale = 0.95f, enabled = !isOperating) {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                inputLink = clip.trim()
                                Toast.makeText(context, strings.pasteLink, Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, strings.clipboardEmpty, Toast.LENGTH_SHORT).show()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .liquidGlass(cornerRadius = 10, innerSheen = true),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = null,
                            tint = colors.accentCyan,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = strings.pasteFromClipboard,
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.uiAutoImport,
                            color = colors.accentCyan,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Card 2: Scan QR Code
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .liquidGlass(
                            cornerRadius = 14,
                            innerSheen = true,
                            elevated = true,
                            accentBorder = colors.accentAmber.copy(alpha = 0.45f)
                        )
                        .tactileClickable(targetScale = 0.95f, enabled = !isOperating) {
                            scanQr()
                        }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .liquidGlass(cornerRadius = 10, innerSheen = true),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = colors.accentAmber,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = strings.scanQrCode,
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = strings.uiCamera,
                            color = colors.accentAmber,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Input Container Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(cornerRadius = 18, innerSheen = true)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Link / Config Input Field
                OutlinedTextField(
                    value = inputLink,
                    onValueChange = { inputLink = it },
                    placeholder = {
                        Text(
                            text = strings.uiLinkHint,
                            fontSize = 11.5.sp,
                            color = colors.textTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = when (detectedType) {
                            DetectedPayloadType.SUBSCRIPTION_URL -> colors.accentAmber
                            DetectedPayloadType.SINGLE_SERVER,
                            DetectedPayloadType.MULTIPLE_SERVERS -> colors.accentCyan
                            else -> colors.borderSubtle
                        },
                        unfocusedBorderColor = colors.borderSubtle,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    trailingIcon = {
                        if (inputLink.isNotEmpty()) {
                            IconButton(
                                onClick = { inputLink = "" },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = strings.close,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                )

                // Dynamic Detection Badge
                AnimatedVisibility(
                    visible = detectedType != DetectedPayloadType.NONE,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val (badgeText, badgeColor) = when (detectedType) {
                        DetectedPayloadType.SUBSCRIPTION_URL -> Pair(strings.detectedSubscription, colors.accentAmber)
                        DetectedPayloadType.SINGLE_SERVER -> Pair(strings.detectedServerNode, colors.accentCyan)
                        DetectedPayloadType.MULTIPLE_SERVERS -> {
                            val count = inputLink.lines().count { it.isNotBlank() }
                            Pair(String.format(strings.detectedMultipleNodes, count), colors.accentEmerald)
                        }
                        DetectedPayloadType.NONE -> Pair("", Color.Transparent)
                    }

                    Surface(
                        color = badgeColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeColor,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                // If Subscription URL is detected, show optional Name field
                AnimatedVisibility(
                    visible = detectedType == DetectedPayloadType.SUBSCRIPTION_URL,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    OutlinedTextField(
                        value = subscriptionName,
                        onValueChange = { subscriptionName = it },
                        placeholder = {
                            Text(
                                text = strings.subNameOptional,
                                fontSize = 11.5.sp,
                                color = colors.textTertiary,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Label,
                                contentDescription = null,
                                tint = colors.accentAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accentAmber,
                            unfocusedBorderColor = colors.borderSubtle,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        )
                    )
                }
            }

            if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL && hwidEligible) {
                com.danieldk.splatevpn.ui.components.SubscriptionHwidSetting(
                    hwidEnabled, { hwidEnabled = it }, enabled = !isOperating)
            }

            if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL) {
                userAgent?.let {
                    com.danieldk.splatevpn.ui.components.SubscriptionUserAgentSetting(it, { userAgent = it }, !isOperating)
                }
            }

            // Operation Progress Status
            AnimatedVisibility(visible = isOperating) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL) colors.accentAmber else colors.accentCyan
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL) strings.subLoading else strings.localized("Импорт узлов…", "Importing servers…"),
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            val isButtonEnabled = inputLink.isNotBlank() && !isOperating
            val buttonAccentColor = when (detectedType) {
                DetectedPayloadType.SUBSCRIPTION_URL -> colors.accentAmber
                DetectedPayloadType.MULTIPLE_SERVERS -> colors.accentEmerald
                else -> colors.accentCyan
            }

            SplateButton(
                onClick = { executeImport() },
                enabled = isButtonEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = buttonAccentColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL) {
                            Icons.Default.CloudDownload
                        } else {
                            Icons.Default.Add
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (detectedType) {
                            DetectedPayloadType.SUBSCRIPTION_URL -> strings.localized("Импортировать подписку", "Import subscription")
                            DetectedPayloadType.MULTIPLE_SERVERS -> strings.localized("Импортировать все узлы", "Import all servers")
                            DetectedPayloadType.SINGLE_SERVER -> strings.localized("Добавить узел", "Add server")
                            DetectedPayloadType.NONE -> strings.importAction
                        },
                        fontSize = 13.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}
