package com.danieldk.splatevpn.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danieldk.splatevpn.data.UpdateDownloadState
import com.danieldk.splatevpn.data.UpdateInfo
import com.danieldk.splatevpn.ui.MainViewModel
import com.danieldk.splatevpn.ui.components.MarkdownContent
import com.danieldk.splatevpn.ui.components.liquidGlass
import com.danieldk.splatevpn.ui.components.tactileClickable
import com.danieldk.splatevpn.ui.theme.LocalAppStrings
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

@Composable
fun UpdateDialog(
    viewModel: MainViewModel,
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val colors = LocalAppThemeColors.current
    val strings = LocalAppStrings.current
    val downloadState by viewModel.updateDownloadState.collectAsState()
    var isChangelogExpanded by remember { mutableStateOf(false) }

    val hintPulse = rememberInfiniteTransition(label = "hint_pulse")
    val bounceY by hintPulse.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    Dialog(
        onDismissRequest = {
            if (downloadState !is UpdateDownloadState.Downloading) {
                viewModel.cancelUpdateDownload()
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnClickOutside = downloadState !is UpdateDownloadState.Downloading,
            dismissOnBackPress = downloadState !is UpdateDownloadState.Downloading,
            usePlatformDefaultWidth = false
        )
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colors.textPrimary
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background.copy(alpha = 0.70f))
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .liquidGlass(
                        cornerRadius = 24,
                        specularSheen = true,
                        borderAlpha = 0.85f
                    )
                    .padding(22.dp)
            ) {
                Column {
                    // — Header —
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(colors.accentCyan.copy(alpha = 0.15f))
                                .border(1.dp, colors.accentCyan.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            val infiniteTransition = rememberInfiniteTransition(label = "icon_spin")
                            val spin by infiniteTransition.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(2000, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "spin"
                            )
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = colors.accentCyan,
                                modifier = Modifier
                                    .size(24.dp)
                                    .then(
                                        if (downloadState is UpdateDownloadState.Downloading)
                                            Modifier.rotate(spin)
                                        else Modifier
                                    )
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.updateAvailableTitle,
                                color = colors.textPrimary,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.accentCyan.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "v${updateInfo.version}",
                                        color = colors.accentCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.accentEmerald.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Release",
                                        color = colors.accentEmerald,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        // Close button only when not downloading
                        if (downloadState !is UpdateDownloadState.Downloading) {
                            IconButton(
                                onClick = {
                                    viewModel.cancelUpdateDownload()
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(colors.surfaceElevated)
                                    .tactileClickable(targetScale = 0.90f) {
                                        viewModel.cancelUpdateDownload()
                                        onDismiss()
                                    }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.close,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // — Rich Markdown Changelog Section with Scroll Guidance —
                    if (updateInfo.changelog.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Article,
                                    contentDescription = null,
                                    tint = colors.accentCyan,
                                    modifier = Modifier.size(15.dp)
                                )
                                Text(
                                    text = strings.updateChangelog.uppercase(),
                                    color = colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.8.sp
                                )
                            }

                            // Expand / Collapse Notes toggle
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceElevated)
                                    .clickable { isChangelogExpanded = !isChangelogExpanded }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isChangelogExpanded) strings.updateCollapseNotes else strings.updateExpandNotes,
                                    color = colors.accentCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Icon(
                                    imageVector = if (isChangelogExpanded) Icons.Default.UnfoldLess else Icons.Default.UnfoldMore,
                                    contentDescription = null,
                                    tint = colors.accentCyan,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }

                        val changelogScrollState = rememberScrollState()
                        val changelogMaxHeight = if (isChangelogExpanded) 340.dp else 190.dp

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = changelogMaxHeight)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (colors.isDark) colors.surface.copy(alpha = 0.75f)
                                    else colors.surfaceElevated.copy(alpha = 0.95f)
                                )
                                .border(
                                    width = 1.dp,
                                    color = colors.glassBorderLight.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .padding(12.dp)
                        ) {
                            // Scrollable rendered Markdown
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(changelogScrollState)
                                    .padding(end = 6.dp)
                            ) {
                                MarkdownContent(markdown = updateInfo.changelog)
                            }

                            // Dynamic Visible Scrollbar Track
                            val canScroll = changelogScrollState.maxValue > 0
                            if (canScroll) {
                                val scrollFraction = changelogScrollState.value.toFloat() / changelogScrollState.maxValue.toFloat()
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(3.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(colors.glassBorderLight.copy(alpha = 0.20f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxHeight(0.35f)
                                            .fillMaxWidth()
                                            .offset(y = (scrollFraction * 60).dp)
                                            .clip(RoundedCornerShape(1.5.dp))
                                            .background(colors.accentCyan.copy(alpha = 0.85f))
                                    )
                                }
                            }

                            // Interactive Bouncing Scroll Hint (Disappears when scrolled down)
                            val showScrollHint = canScroll && changelogScrollState.value < 20
                            val hintAlpha by animateFloatAsState(
                                targetValue = if (showScrollHint) 1f else 0f,
                                animationSpec = tween(250),
                                label = "hint_alpha"
                            )
                            if (hintAlpha > 0.01f) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 2.dp)
                                        .offset(y = bounceY.dp)
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    colors.backgroundDeep.copy(alpha = 0.95f),
                                                    colors.surfaceElevated.copy(alpha = 0.95f)
                                                )
                                            )
                                        )
                                        .border(
                                            width = 1.dp,
                                            color = colors.accentCyan.copy(alpha = 0.45f * hintAlpha),
                                            shape = RoundedCornerShape(20.dp)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = strings.updateScrollHint,
                                            color = colors.accentCyan.copy(alpha = hintAlpha),
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // — Progress / status area —
                    AnimatedContent(
                        targetState = downloadState,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                        contentKey = { state ->
                            when (state) {
                                is UpdateDownloadState.Idle -> 0
                                is UpdateDownloadState.Downloading -> 1
                                is UpdateDownloadState.Completed -> 2
                                is UpdateDownloadState.Error -> 3
                            }
                        },
                        label = "download_state"
                    ) { state ->
                        when (state) {
                            is UpdateDownloadState.Idle -> {
                                // Nothing to show in idle
                            }
                            is UpdateDownloadState.Downloading -> {
                                val currentDownloading = (downloadState as? UpdateDownloadState.Downloading) ?: state
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = strings.updateDownloading,
                                            color = colors.accentCyan,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        if (currentDownloading.progress >= 0f) {
                                            Text(
                                                text = "${(currentDownloading.progress * 100).toInt()}%  " +
                                                        formatBytes(currentDownloading.downloadedBytes) +
                                                        if (currentDownloading.totalBytes > 0) " / ${formatBytes(currentDownloading.totalBytes)}" else "",
                                                color = colors.textSecondary,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    val progressAnim by animateFloatAsState(
                                        targetValue = if (currentDownloading.progress >= 0f) currentDownloading.progress else 0f,
                                        animationSpec = tween(180, easing = LinearEasing),
                                        label = "progress_anim"
                                    )
                                    if (currentDownloading.progress >= 0f) {
                                        LinearProgressIndicator(
                                            progress = { progressAnim },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(2.5.dp)),
                                            color = colors.accentCyan,
                                            trackColor = colors.accentCyan.copy(alpha = 0.2f),
                                            strokeCap = StrokeCap.Round
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(5.dp)
                                                .clip(RoundedCornerShape(2.5.dp)),
                                            color = colors.accentCyan,
                                            trackColor = colors.accentCyan.copy(alpha = 0.2f),
                                            strokeCap = StrokeCap.Round
                                        )
                                    }
                                }
                            }
                            is UpdateDownloadState.Completed -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.accentEmerald.copy(alpha = 0.1f))
                                        .border(1.dp, colors.accentEmerald.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = colors.accentEmerald,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = strings.updateDownloaded,
                                        color = colors.accentEmerald,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            is UpdateDownloadState.Error -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.accentCrimson.copy(alpha = 0.1f))
                                        .border(1.dp, colors.accentCrimson.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = colors.accentCrimson,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = state.message.ifBlank { strings.updateFailed },
                                        color = colors.accentCrimson,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // — Action buttons —
                    when (downloadState) {
                        is UpdateDownloadState.Idle, is UpdateDownloadState.Error -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Later button
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = colors.textSecondary
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, colors.borderSubtle
                                    )
                                ) {
                                    Text(
                                        text = strings.cancel,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                // Download & Install button
                                Button(
                                    onClick = { viewModel.startUpdateDownload() },
                                    modifier = Modifier.weight(2f),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = colors.accentCyan,
                                        contentColor = LocalAppThemeColors.current.onAccent
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (downloadState is UpdateDownloadState.Error)
                                            Icons.Default.Refresh else Icons.Default.Download,
                                        contentDescription = null,
                                        tint = LocalAppThemeColors.current.onAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (downloadState is UpdateDownloadState.Error)
                                            strings.updateRetry else strings.updateDownloadAndInstall,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = LocalAppThemeColors.current.onAccent
                                    )
                                }
                            }
                        }
                        is UpdateDownloadState.Downloading -> {
                            // Cancel download
                            OutlinedButton(
                                onClick = { viewModel.cancelUpdateDownload() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = colors.accentCrimson
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, colors.accentCrimson.copy(alpha = 0.4f)
                                )
                            ) {
                                Text(
                                    text = strings.cancel,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        is UpdateDownloadState.Completed -> {
                            Button(
                                onClick = {
                                    viewModel.installDownloadedUpdate()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentEmerald,
                                    contentColor = LocalAppThemeColors.current.onAccent
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.InstallMobile,
                                    contentDescription = null,
                                    tint = LocalAppThemeColors.current.onAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = strings.updateInstall,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = LocalAppThemeColors.current.onAccent
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
