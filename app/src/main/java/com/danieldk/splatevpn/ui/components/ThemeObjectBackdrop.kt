package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

internal fun hasThemeObject(style: BackdropStyle) = style in setOf(
    BackdropStyle.SAKURA, BackdropStyle.GRID, BackdropStyle.OCEAN,
    BackdropStyle.SUNSET, BackdropStyle.AURORA, BackdropStyle.RINGS
)

/** Transparent object art leaves the interface's own background visible between silhouettes. */
@Composable
internal fun ThemeObjectBackdrop(c: AppThemeColors, modifier: Modifier, phase: () -> Float, glow: Color) {
    val motes = remember { val random = Random(1927); List(22) { Offset(random.nextFloat(), random.nextFloat()) } }
    val petal = remember { Path().apply {
        moveTo(0f, -1f); cubicTo(1.25f, -.8f, 1f, .7f, 0f, 1f)
        cubicTo(-.7f, .4f, -.8f, -.5f, 0f, -1f); close()
    } }
    BoxWithConstraints(modifier.clipToBounds().background(Brush.verticalGradient(listOf(c.background, c.backgroundDeep)))) {
        val preview = maxHeight < maxWidth
        ThemeAtmosphere(c, Modifier.fillMaxSize(), phase, preview)
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * .93f, size.height * .68f)
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = if(c.backdrop == BackdropStyle.RINGS) .035f else .09f), Color.Transparent), center, size.minDimension * .8f), size.minDimension * .8f, center)
        }
        ThemeLayeredObjects(c.backdrop, Modifier.fillMaxSize(), phase, preview)
        Canvas(Modifier.fillMaxSize()) {
            val p = phase()
            val unit = size.minDimension
            motes.take(if (preview) 6 else 22).forEachIndexed { i, point ->
                val fall = c.backdrop == BackdropStyle.SAKURA
                val y = if (fall) (point.y + p) % 1f else (point.y - p + 1f) % 1f
                val x = (point.x + sin(p * 2f * PI.toFloat() + i) * .035f).coerceIn(0f, 1f)
                val center = Offset(x * size.width, y * size.height)
                val fade = sin(y * PI.toFloat()).coerceAtLeast(0f)
                if (fall) {
                    val radius = unit * (.004f + i % 3 * .002f)
                    rotate(p * 360f + i * 31f, center) {
                        // Reuse one small normalized path; drawing has no per-frame geometry allocation.
                        withTransform({ translate(center.x, center.y); scale(radius, radius, Offset.Zero) }) {
                            drawPath(petal, c.accentSecondary.copy(alpha = fade * .45f))
                        }
                    }
                } else if (c.backdrop == BackdropStyle.OCEAN) {
                    drawCircle(c.accentCyan.copy(alpha = fade * .22f), unit * (.003f + i % 4 * .002f), center, style = Stroke(unit * .0012f))
                } else {
                    drawCircle(c.accentSecondary.copy(alpha = fade * .28f), unit * (.0015f + i % 3 * .0007f), center)
                }
            }
        }
    }
}
