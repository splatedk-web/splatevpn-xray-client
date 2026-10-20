package com.danieldk.splatevpn.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A shared capsule follows the finger; releasing commits the hovered tab. Taps remain on each tab. */
@Composable
internal fun SlidingTabStrip(
    selectedIndex: Int,
    accents: List<Color>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.(highlightedIndex: Int) -> Unit
) {
    val count = accents.size
    require(count > 0)
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val select by rememberUpdatedState(onSelect)
    var width by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    val gapPx = with(density) { 4.dp.toPx() }
    val slotWidth = ((width - gapPx * (count - 1)) / count).coerceAtLeast(0f)
    val stride = slotWidth + gapPx
    val hovered = if (width > 0) ((fingerX + gapPx / 2f) / stride).toInt().coerceIn(0, count - 1) else selectedIndex
    val highlighted = if (dragging) hovered else selectedIndex
    val targetX = if (dragging) {
        (fingerX - slotWidth / 2f).coerceIn(0f, (width - slotWidth).coerceAtLeast(0f))
    } else selectedIndex * stride
    val position = remember { Animatable(0f) }
    var positionedWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(targetX, dragging, width) {
        if (width <= 0) return@LaunchedEffect
        if (dragging || positionedWidth != width) {
            position.snapTo(targetX)
            positionedWidth = width
        } else {
            // Always continue from the position actually displayed under the finger.
            // A bounded curve avoids spring recoil when the direction changes rapidly.
            position.animateTo(targetX, tween(220, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier.fillMaxWidth().liquidGlass(cornerRadius = 14, innerSheen = true).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().height(48.dp).onSizeChanged { width = it.width }
            .pointerInput(count, gapPx) {
                var lastHovered = -1
                fun indexAt(x: Float): Int {
                    val itemWidth = (size.width - gapPx * (count - 1)) / count
                    return ((x + gapPx / 2f) / (itemWidth + gapPx).coerceAtLeast(1f))
                        .toInt().coerceIn(0, count - 1)
                }
                detectHorizontalDragGestures(
                    onDragStart = { position ->
                        fingerX = position.x.coerceIn(0f, size.width.toFloat())
                        dragging = true
                        lastHovered = indexAt(fingerX)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        fingerX = change.position.x.coerceIn(0f, size.width.toFloat())
                        val index = indexAt(fingerX)
                        if (index != lastHovered) {
                            lastHovered = index
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    },
                    onDragEnd = {
                        select(lastHovered.coerceIn(0, count - 1))
                        dragging = false
                    },
                    onDragCancel = { dragging = false }
                )
            }) {
            if (slotWidth > 0) {
                val shape = RoundedCornerShape(10.dp)
                val accent = accents[highlighted]
                Box(Modifier.graphicsLayer { translationX = position.value }
                    .width(with(density) { slotWidth.toDp() }).fillMaxHeight()
                    .testTag("categoryCapsule")
                    .clip(shape).background(accent.copy(alpha = if (dragging) 0.28f else 0.22f))
                    .border(1.dp, accent.copy(alpha = 0.6f), shape))
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) { content(highlighted) }
        }
    }
}
