package com.danieldk.splatevpn.ui.screens
import com.danieldk.splatevpn.ui.components.SplateIconButton as IconButton
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog
import com.danieldk.splatevpn.ui.components.SplateButton as Button
import androidx.compose.ui.draw.alpha
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danieldk.splatevpn.data.ServerNode
import com.danieldk.splatevpn.data.SubscriptionEntity
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.*
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.danieldk.splatevpn.data.LinkParser
import com.danieldk.splatevpn.ui.components.QrCodeGenerator

/**
 * Dialog for sharing a server node via QR Code and System Share Sheet
 */
@Composable
internal fun ServerShareDialog(
    server: ServerNode,
    onDismiss: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val shareLink = remember(server) {
        LinkParser.toShareLink(server)
    }

    val qrBitmap = remember(shareLink) {
        QrCodeGenerator.generateQrBitmap(shareLink, sizePx = 640)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strings.shareServerTitle,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = colors.textSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = server.name,
                    color = colors.accentCyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${server.protocol.uppercase()} • ${server.address}:${server.port}",
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(14.dp))

                // High contrast QR Container (white background guarantees instant camera readability)
                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Server QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(34.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = String.format(strings.sharePayloadTooLarge, shareLink.toByteArray(Charsets.UTF_8).size),
                                color = Color(0xFF1E293B),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = strings.shareQrPrompt,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons: Copy & System Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Copy Link Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentCyan.copy(alpha = 0.4f))
                            .tactileClickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(shareLink))
                                Toast.makeText(context, strings.linkCopied, Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.accentCyan, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareCopyLink,
                            color = colors.accentCyan,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // System Share Intent Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentEmerald.copy(alpha = 0.4f))
                            .tactileClickable {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, server.name)
                                    putExtra(Intent.EXTRA_TEXT, shareLink)
                                }
                                val shareIntent = Intent.createChooser(sendIntent, strings.shareServerTitle)
                                context.startActivity(shareIntent)
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareSend,
                            color = colors.accentEmerald,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}

/**
 * Dialog for sharing a subscription via QR Code and System Share Sheet
 */
@Composable
internal fun SubscriptionShareDialog(
    sub: SubscriptionEntity,
    onDismiss: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val shareLink = sub.url

    val qrBitmap = remember(shareLink) {
        QrCodeGenerator.generateQrBitmap(shareLink, sizePx = 640)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strings.shareSubscriptionTitle,
                    color = colors.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = colors.textSecondary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = subscriptionDisplayName(sub.id, sub.name, strings),
                    color = colors.accentAmber,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = String.format(strings.subscriptionNodes, sub.nodeCount),
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(modifier = Modifier.height(14.dp))

                Box(
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Subscription QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(8.dp)
                        ) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(34.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = String.format(strings.sharePayloadTooLarge, shareLink.toByteArray(Charsets.UTF_8).size),
                                color = Color(0xFF1E293B),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = strings.shareSubQrPrompt,
                    color = colors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Copy Link Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentAmber.copy(alpha = 0.4f))
                            .tactileClickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(shareLink))
                                Toast.makeText(context, strings.linkCopied, Toast.LENGTH_SHORT).show()
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareCopyLink,
                            color = colors.accentAmber,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // System Share Intent Button
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .liquidGlass(cornerRadius = 12, innerSheen = true, accentBorder = colors.accentEmerald.copy(alpha = 0.4f))
                            .tactileClickable {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, sub.name)
                                    putExtra(Intent.EXTRA_TEXT, shareLink)
                                }
                                val shareIntent = Intent.createChooser(sendIntent, strings.shareSubscriptionTitle)
                                context.startActivity(shareIntent)
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, tint = colors.accentEmerald, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.shareSend,
                            color = colors.accentEmerald,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {}
    )
}
