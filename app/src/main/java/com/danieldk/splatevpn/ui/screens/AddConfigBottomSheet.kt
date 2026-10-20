package com.danieldk.splatevpn.ui.screens

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

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
    var subscriptionName by remember { mutableStateOf("") }
    var isOperating by remember { mutableStateOf(false) }

    // Smart detector for entered or pasted payload
    val detectedType = remember(inputLink) {
        val trimmed = inputLink.trim()
        when {
            trimmed.isEmpty() -> DetectedPayloadType.NONE
            com.danieldk.splatevpn.data.LinkParser.normalizeSubscriptionUrl(trimmed) != null ||
                    com.danieldk.splatevpn.data.LinkParser.isEncryptedSubscriptionLink(trimmed) -> {
                DetectedPayloadType.SUBSCRIPTION_URL
            }
            trimmed.lines().count { it.isNotBlank() } > 1 -> {
                DetectedPayloadType.MULTIPLE_SERVERS
            }
            trimmed.startsWith("vless://", ignoreCase = true) ||
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
                title = subscriptionName.takeIf { it.isNotBlank() }
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
            viewModel.addServersFromLinks(trimmed) { count ->
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                            val options = GmsBarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build()
                            val scanner = GmsBarcodeScanning.getClient(context, options)
                            scanner.startScan()
                                .addOnSuccessListener { barcode ->
                                    val raw = barcode.rawValue
                                    if (!raw.isNullOrBlank()) {
                                        inputLink = raw.trim()
                                    }
                                }
                                .addOnFailureListener { e ->
                                    Toast.makeText(
                                        context,
                                        String.format(strings.qrScanError, e.message ?: ""),
                                        Toast.LENGTH_SHORT
                                    ).show()
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
                        text = if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL) strings.subLoading else "Импорт узлов...",
                        fontSize = 12.sp,
                        color = colors.textSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Primary Action Button (High Contrast & Clear Ergonomics, 52dp height)
            val isButtonEnabled = inputLink.isNotBlank() && !isOperating
            val buttonAccentColor = when (detectedType) {
                DetectedPayloadType.SUBSCRIPTION_URL -> colors.accentAmber
                DetectedPayloadType.MULTIPLE_SERVERS -> colors.accentEmerald
                else -> colors.accentCyan
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (isButtonEnabled) {
                            Brush.horizontalGradient(
                                listOf(
                                    buttonAccentColor,
                                    buttonAccentColor.copy(alpha = 0.85f)
                                )
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(
                                    colors.surface,
                                    colors.surface
                                )
                            )
                        }
                    )
                    .tactileClickable(enabled = isButtonEnabled) {
                        executeImport()
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (detectedType == DetectedPayloadType.SUBSCRIPTION_URL) {
                            Icons.Default.CloudDownload
                        } else {
                            Icons.Default.Add
                        },
                        contentDescription = null,
                        tint = if (isButtonEnabled) LocalAppThemeColors.current.onAccent else colors.textTertiary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (detectedType) {
                            DetectedPayloadType.SUBSCRIPTION_URL -> "Импортировать подписку"
                            DetectedPayloadType.MULTIPLE_SERVERS -> "Импортировать все узлы"
                            DetectedPayloadType.SINGLE_SERVER -> "Добавить узел"
                            DetectedPayloadType.NONE -> strings.importAction
                        },
                        color = if (isButtonEnabled) LocalAppThemeColors.current.onAccent else colors.textTertiary,
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
