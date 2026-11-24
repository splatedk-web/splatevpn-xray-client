package com.danieldk.splatevpn.ui.components

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors

internal val LocalTvControls = staticCompositionLocalOf { false }

/** Draw inside the target so clipped cards and scrollers cannot hide the remote focus. */
@Composable
internal fun Modifier.tvFocusOutline(enabled: Boolean = true): Modifier {
    if (!LocalTvControls.current || !enabled) return this
    val colors = LocalAppThemeColors.current
    var focused by remember { mutableStateOf(false) }
    return this.onFocusChanged { focused = it.isFocused }.drawWithContent {
        drawContent()
        if (focused) {
            val width = 3.dp.toPx()
            val inset = width / 2
            drawRoundRect(colors.textPrimary, Offset(inset, inset),
                Size((size.width - width).coerceAtLeast(0f), (size.height - width).coerceAtLeast(0f)),
                CornerRadius(12.dp.toPx()), style = Stroke(width))
        }
    }
}
