package com.danieldk.splatevpn.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.danieldk.splatevpn.ui.theme.LocalUiPerformanceSettings

/** Share Material's press lifecycle so scrolling, cancellation and remote activation keep working. */
@Composable
internal fun Modifier.elasticPressFeedback(source: MutableInteractionSource, enabled: Boolean,
    pressedScale: Float = .975f): Modifier {
    if (!LocalUiPerformanceSettings.current.animationsEnabled) return this
    val pressed by source.collectIsPressedAsState()
    var point by remember { mutableStateOf<Offset?>(null) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Press) point = it.pressPosition }
    }
    val scale = animateFloatAsState(if (enabled && pressed) pressedScale else 1f,
        spring(dampingRatio = .80f, stiffness = Spring.StiffnessMedium), label = "elasticPress")
    return onSizeChanged { bounds = it }.graphicsLayer {
        scaleX = scale.value
        scaleY = 1f - (1f - scale.value) * .72f
        transformOrigin = point?.takeIf { bounds.width > 0 && bounds.height > 0 && it.x.isFinite() && it.y.isFinite() }
            ?.let { TransformOrigin((it.x / bounds.width).coerceIn(.2f, .8f), (it.y / bounds.height).coerceIn(.2f, .8f)) }
            ?: TransformOrigin.Center
    }
}
