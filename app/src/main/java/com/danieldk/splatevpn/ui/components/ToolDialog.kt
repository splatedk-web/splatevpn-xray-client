package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

/** Keeps tool actions clear of the keyboard and system bars without stretching short content. */
@Composable
internal fun ToolDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 680.dp,
    maxHeight: Dp = 720.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAppThemeColors.current
    val widthLimit = maxWidth
    val heightLimit = maxHeight
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        rememberAndApplyPopupWindowBlur(blurBehind = true, backgroundBlur = false)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                ),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime))
                    .padding(horizontal = 14.dp, vertical = 20.dp)
                    .testTag("toolDialogViewport"),
                contentAlignment = Alignment.Center
            ) {
                val availableHeight = this.maxHeight
                val panelHeight = minOf(
                    heightLimit,
                    if (availableHeight > 420.dp) availableHeight * .9f else availableHeight
                )
                Surface(
                    modifier.widthIn(max = widthLimit).fillMaxWidth().heightIn(max = panelHeight)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* Intercept clicks inside dialog content so backdrop is not triggered */ }
                        )
                        .popupSurface(24.dp).liquidGlass(cornerRadius = 24, elevated = true),
                    shape = RoundedCornerShape(24.dp), color = Color.Transparent,
                    contentColor = colors.textPrimary
                ) {
                    Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
                }
            }
        }
    }
}
