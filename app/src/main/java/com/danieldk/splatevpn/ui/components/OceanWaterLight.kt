package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Subtle light shafts illuminate open water without stripes through the central controls. */
@Composable
internal fun OceanWaterLight(colors: AppThemeColors, modifier: Modifier, phase: () -> Float) {
    Box(modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val origin = Offset(w * 1.02f, -h * .08f)
        val beams = List(4) { i -> Path().apply {
            moveTo(origin.x - w * (.02f + i * .05f), origin.y)
            lineTo(origin.x + w * .035f, origin.y)
            lineTo(w * (.39f + i * .21f), h * .74f)
            lineTo(w * (.06f + i * .20f), h * .74f)
            close()
        } }
        val brushes = List(4) { i -> Brush.verticalGradient(listOf(
            colors.accentCyan.copy(alpha = .045f + i % 2 * .015f),
            colors.accentSecondary.copy(alpha = .015f), Color.Transparent), 0f, h * .74f) }
        val glowCenter = Offset(w * .80f, h * .72f)
        val radius = size.minDimension * .95f
        val glow = Brush.radialGradient(listOf(colors.ambientC.copy(alpha = .09f), Color.Transparent), glowCenter, radius)
        onDrawBehind {
            if (size.minDimension <= 0f) return@onDrawBehind
            val a = phase() * 2f * PI.toFloat()
            drawCircle(glow, radius, glowCenter)
            beams.forEachIndexed { i, path ->
                rotate(sin(a + i * .65f) * 2f, origin) {
                    drawPath(path, brushes[i], alpha = .68f + .22f * sin(a * 2f + i))
                }
            }
        }
    })
}

/** Connected, irregular light patches travel over the lower reef as a separate foreground layer. */
@Composable
internal fun OceanCaustics(colors: AppThemeColors, modifier: Modifier, phase: () -> Float) {
    Box(modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val cells = List(15) { i ->
            val row = i / 5
            val x = w * (-.05f + (i % 5) * .25f + row * .06f)
            val y = h * (.79f + row * .09f)
            val width = w * (.19f + i % 3 * .025f)
            val height = h * (.025f + row * .012f)
            Path().apply {
                moveTo(x, y)
                cubicTo(x + width * .30f, y - height, x + width * .65f, y - height * .55f, x + width, y)
                cubicTo(x + width * 1.12f, y + height * .55f, x + width * .65f, y + height * 1.25f, x + width * .37f, y + height)
                cubicTo(x + width * .12f, y + height * .75f, x - width * .12f, y + height * .40f, x, y)
                close()
            }
        }
        val stroke = Stroke(size.minDimension * .0014f, cap = StrokeCap.Round)
        val paint = Brush.verticalGradient(listOf(Color.Transparent, colors.accentCyan.copy(alpha = .10f)), h * .72f, h)
        onDrawBehind {
            val a = phase() * 2f * PI.toFloat()
            cells.forEachIndexed { i, path ->
                translate(sin(a * 2f + i * .6f) * w * .015f, cos(a + i) * h * .003f) {
                    drawPath(path, paint, style = stroke, alpha = .55f + sin(a * 2f + i) * .30f)
                }
            }
        }
    })
}
