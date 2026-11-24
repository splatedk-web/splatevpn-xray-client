package com.danieldk.splatevpn.ui.components

import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.PopupProperties
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import com.danieldk.splatevpn.ui.theme.SplateShapes
import kotlin.math.roundToInt

/**
 * Applies Android 12+ (API 31+) cross-window blur to Dialog or Popup windows when
 * system blur is supported and enabled in app settings. Returns true if blur is active.
 */
@Composable
internal fun rememberAndApplyPopupWindowBlur(
    blurBehind: Boolean = true,
    backgroundBlur: Boolean = true
): Boolean {
    val blurEnabled = LocalGlassBlurEnabled.current
    val transparency = LocalGlassTransparency.current
    if (!blurEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val windowManager = remember(context) {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    } ?: return false

    val crossWindowSupported = runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
    if (!crossWindowSupported) return false

    // Cap cross-window blur radius to 20..36 px so SurfaceFlinger stays smooth at 60/120 Hz during sheet drags.
    val radiusPx = with(density) { (10f + transparency * 4f).dp.toPx().roundToInt().coerceIn(20, 36) }

    LaunchedEffect(view, radiusPx, blurBehind, backgroundBlur) {
        val dialogWindow = (view.parent as? DialogWindowProvider)?.window
        if (dialogWindow != null) {
            runCatching {
                if (blurBehind) {
                    val params = dialogWindow.attributes
                    val needsFlag = (params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0
                    if (needsFlag || params.blurBehindRadius != radiusPx) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        params.blurBehindRadius = radiusPx
                        dialogWindow.attributes = params
                    }
                }
                if (backgroundBlur) {
                    dialogWindow.setBackgroundBlurRadius(radiusPx)
                }
            }
        }
    }
    return true
}

@Composable
internal fun isCrossWindowBlurAvailable(): Boolean {
    if (!LocalGlassBlurEnabled.current || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val context = LocalContext.current
    val windowManager = remember(context) {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    } ?: return false
    return runCatching { windowManager.isCrossWindowBlurEnabled }.getOrDefault(false)
}

private fun popupSurfaceAlpha(blurActive: Boolean, transparency: Float): Float =
    if (!blurActive) 1f else (0.90f - transparency * 0.14f).coerceIn(0.76f, 0.92f)

// Popups keep an opaque backing when system cross-window blur is unavailable so text stays readable.
@Composable
internal fun SplateAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = SplateShapes.extraLarge,
    containerColor: Color = LocalAppThemeColors.current.surface,
    iconContentColor: Color = LocalAppThemeColors.current.accentCyan,
    titleContentColor: Color = LocalAppThemeColors.current.textPrimary,
    textContentColor: Color = LocalAppThemeColors.current.textSecondary,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties()
) {
    val transparency = LocalGlassTransparency.current
    val blurAvailable = isCrossWindowBlurAvailable()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            rememberAndApplyPopupWindowBlur(blurBehind = true, backgroundBlur = true)
            confirmButton()
        },
        modifier = modifier
            .clip(shape)
            .background(containerColor.copy(alpha = popupSurfaceAlpha(blurAvailable, transparency)))
            .liquidGlass(cornerRadius = 24, shape = shape, elevated = true),
        dismissButton = dismissButton, icon = icon, title = title, text = text,
        shape = shape, containerColor = Color.Transparent,
        iconContentColor = iconContentColor, titleContentColor = titleContentColor,
        textContentColor = textContentColor, tonalElevation = tonalElevation, properties = properties
    )
}

@Composable
internal fun SplateDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    shape: Shape = SplateShapes.medium,
    containerColor: Color = LocalAppThemeColors.current.surfaceElevated,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 8.dp,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded, onDismissRequest = onDismissRequest,
        modifier = modifier
            .clip(shape)
            .background(containerColor.copy(alpha = 1f))
            .liquidGlass(cornerRadius = 16, shape = shape, elevated = true),
        offset = offset, scrollState = scrollState, properties = properties,
        shape = shape, containerColor = Color.Transparent, tonalElevation = tonalElevation,
        shadowElevation = shadowElevation, border = border,
        content = content
    )
}
