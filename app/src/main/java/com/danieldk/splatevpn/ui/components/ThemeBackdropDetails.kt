package com.danieldk.splatevpn.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Scene geometry is built once per size/palette. Animation only transforms cached shapes. */
internal fun createBackdropDetails(c: AppThemeColors, w: Float, h: Float): DrawScope.(Float) -> Unit {
    val unit = minOf(w, h)
    val random = Random(4217)
    val motes = List(32) { Offset(random.nextFloat(), random.nextFloat()) }
    val petal = Path().apply {
        moveTo(0f, -unit * 0.012f)
        cubicTo(unit * 0.020f, -unit * 0.010f, unit * 0.018f, unit * 0.009f, 0f, unit * 0.014f)
        cubicTo(-unit * 0.010f, unit * 0.006f, -unit * 0.014f, -unit * 0.005f, 0f, -unit * 0.012f)
        close()
    }
    val ribbons = if (c.backdrop in listOf(BackdropStyle.OCEAN, BackdropStyle.PASTEL)) List(5) { i ->
        val y = h * (0.38f + i * 0.12f)
        Path().apply {
            moveTo(-w * 0.15f, y)
            cubicTo(w * 0.22f, y - h * 0.15f, w * 0.60f, y + h * 0.12f, w * 1.15f, y - h * 0.07f)
            lineTo(w * 1.15f, h * 1.15f); lineTo(-w * 0.15f, h * 1.15f); close()
        }
    } else emptyList()
    val ribbonBrushes = ribbons.mapIndexed { i, _ ->
        Brush.verticalGradient(listOf(
            (if (i % 2 == 0) c.accentCyan else c.ambientB).copy(alpha = if (c.isDark) 0.11f else 0.06f),
            c.backgroundDeep.copy(alpha = 0.03f)), h * (0.30f + i * 0.12f), h)
    }
    val beams = if (c.backdrop == BackdropStyle.OCEAN) List(4) { i ->
        Path().apply {
            moveTo(w * (0.52f + i * 0.15f), -h * 0.08f)
            lineTo(w * (0.60f + i * 0.15f), -h * 0.08f)
            lineTo(w * (0.12f + i * 0.26f), h * 0.75f)
            lineTo(w * (-0.30f + i * 0.26f), h * 0.75f)
            close()
        }
    } else emptyList()
    val beamBrush = Brush.verticalGradient(listOf(c.accentCyan.copy(alpha = 0.14f), c.ambientC.copy(alpha = 0.045f), Color.Transparent), 0f, h * 0.75f)
    val moon = Offset(w * 0.79f, h * 0.23f)
    val moonGlow = Brush.radialGradient(listOf(c.accentSecondary.copy(alpha = 0.17f), Color.Transparent), moon, unit * 0.4f)
    val branches = if (c.backdrop == BackdropStyle.SAKURA) listOf(
        Path().apply {
            moveTo(w * 1.05f, h * 0.59f)
            cubicTo(w * 0.91f, h * 0.46f, w * 0.95f, h * 0.22f, w * 0.66f, h * 0.13f)
            moveTo(w * 0.94f, h * 0.36f)
            cubicTo(w * 0.80f, h * 0.29f, w * 0.72f, h * 0.30f, w * 0.57f, h * 0.23f)
            moveTo(w * 0.88f, h * 0.25f)
            cubicTo(w * 0.99f, h * 0.19f, w * 0.97f, h * 0.13f, w * 1.01f, h * 0.09f)
        },
        Path().apply {
            moveTo(-w * 0.06f, h * 0.94f)
            cubicTo(w * 0.07f, h * 0.83f, w * 0.12f, h * 0.76f, w * 0.34f, h * 0.72f)
            moveTo(w * 0.09f, h * 0.83f)
            cubicTo(w * 0.03f, h * 0.78f, w * 0.06f, h * 0.72f, w * 0.02f, h * 0.69f)
        }) else emptyList()
    val blossoms = listOf(Offset(.68f,.14f), Offset(.77f,.18f), Offset(.88f,.24f), Offset(.70f,.28f),
        Offset(.58f,.235f), Offset(.97f,.17f), Offset(.31f,.73f), Offset(.20f,.76f), Offset(.06f,.77f))
    val skyline = if (c.backdrop == BackdropStyle.GRID) List(24) { i ->
        val height = h * (0.018f + random.nextFloat() * 0.065f)
        Offset(i * w / 24f, h * 0.61f - height) to Size(w / 24f * 0.78f, height)
    } else emptyList()
    val filaments = if (c.backdrop == BackdropStyle.AURORA) List(18) { i ->
        val y = h * (0.17f + i * 0.018f)
        Path().apply {
            moveTo(-w * 0.1f, y + h * .13f)
            cubicTo(w * .2f, y - h * .12f, w * .44f, y + h * .24f, w * .70f, y)
            cubicTo(w * .9f, y - h * .13f, w, y - h * .02f, w * 1.1f, y - h * .15f)
        }
    } else emptyList()

    return { progress ->
        val angle = progress * 2f * PI.toFloat()
        when (c.backdrop) {
            BackdropStyle.OCEAN -> {
                beams.forEachIndexed { i, path ->
                    translate(left = sin(angle + i * .7f) * unit * .035f) { drawPath(path, beamBrush) }
                }
                ribbons.forEachIndexed { i, path ->
                    translate(top = sin(angle + i) * unit * .025f) { drawPath(path, ribbonBrushes[i]) }
                }
                motes.forEachIndexed { i, point ->
                    val y = (point.y - progress + 1f) % 1f
                    val center = Offset(point.x * w + sin(angle + i) * unit * .025f, y * h)
                    val opacity = sin(y * PI.toFloat()).coerceAtLeast(0f) * .28f
                    drawCircle(c.accentCyan.copy(alpha = opacity), unit * (.0025f + i % 4 * .0015f), center,
                        style = Stroke(width = unit * .001f))
                    if (i % 4 == 0) drawCircle(c.textPrimary.copy(alpha = opacity * .7f), unit * .0015f, center)
                }
            }
            BackdropStyle.SAKURA -> {
                drawCircle(moonGlow, unit * .4f, moon)
                drawCircle(c.accentSecondary.copy(alpha = .16f), unit * .11f, moon)
                drawArc(c.textPrimary.copy(alpha = .18f), 200f, 130f, false,
                    Offset(moon.x - unit * .11f, moon.y - unit * .11f), Size(unit * .22f, unit * .22f),
                    style = Stroke(unit * .0015f))
                branches.forEach { drawPath(it, c.accentSecondary.copy(alpha = .20f), style = Stroke(unit * .006f, cap = StrokeCap.Round)) }
                blossoms.forEachIndexed { i, point ->
                    val center = Offset(point.x * w, point.y * h)
                    for (leaf in 0..4) {
                        val a = leaf * 2f * PI.toFloat() / 5f + i
                        drawCircle(c.accentCyan.copy(alpha = .25f), unit * .010f,
                            Offset(center.x + cos(a) * unit * .010f, center.y + sin(a) * unit * .010f))
                    }
                    drawCircle(c.accentAmber.copy(alpha = .5f), unit * .0028f, center)
                }
                motes.forEachIndexed { i, point ->
                    val y = (point.y + progress) % 1f
                    val x = point.x * w + sin(angle + i * .8f) * unit * .07f
                    val opacity = sin(y * PI.toFloat()).coerceAtLeast(0f) * (.25f + i % 3 * .1f)
                    translate(left = x, top = y * h) {
                        rotate(progress * 360f + i * 37f, pivot = Offset.Zero) {
                            drawPath(petal, (if (i % 3 == 0) c.accentSecondary else c.accentCyan).copy(alpha = opacity))
                        }
                    }
                }
            }
            BackdropStyle.GRID -> skyline.forEachIndexed { i, (position, extent) ->
                drawRect(c.backgroundDeep.copy(alpha = .72f), position, extent)
                drawLine(c.ambientB.copy(alpha = .22f), position, Offset(position.x + extent.width, position.y), unit * .0015f)
                if (i % 3 == 0) drawLine(c.ambientC.copy(alpha = .18f), Offset(position.x + extent.width * .5f, position.y + extent.height * .2f),
                    Offset(position.x + extent.width * .5f, position.y + extent.height * .7f), unit * .002f)
            }
            BackdropStyle.AURORA -> filaments.forEachIndexed { i, path ->
                translate(top = sin(angle + i * .1f) * unit * .04f) {
                    drawPath(path, (if (i % 3 == 0) c.ambientB else c.accentCyan).copy(alpha = .045f),
                        style = Stroke(unit * .0016f, cap = StrokeCap.Round))
                }
            }
            BackdropStyle.STARS -> {
                for (i in 0..6) {
                    val a = motes[i * 2]; val b = motes[i * 2 + 1]
                    // Keep constellations local instead of drawing lines across the entire screen.
                    val start = Offset(a.x * w, a.y * h)
                    val end = Offset(start.x + (b.x - .5f) * unit * .2f, start.y + (b.y - .5f) * unit * .2f)
                    drawLine(c.accentSecondary.copy(alpha = .10f), start, end, unit * .001f)
                    drawCircle(c.textSecondary.copy(alpha = .3f), unit * .0017f, end)
                }
            }
            BackdropStyle.RINGS -> {
                val center = Offset(w * .96f, h * .28f)
                for (i in 0..2) {
                    val a = angle + i * 2.1f
                    val radius = unit * (.38f + i * .20f)
                    drawCircle(c.accentCyan.copy(alpha = .36f), unit * .005f,
                        Offset(center.x + cos(a) * radius, center.y + sin(a) * radius))
                }
            }
            BackdropStyle.SOFT -> {
                for (i in 0..3) {
                    val radius = unit * (.50f + i * .15f)
                    val center = Offset(w * 1.02f, h * .30f)
                    drawArc((if (i % 2 == 0) c.ambientB else c.accentCyan).copy(alpha = .09f),
                        95f + sin(angle) * 8f, 155f, false, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2),
                        style = Stroke(unit * .0015f))
                }
            }
            BackdropStyle.PASTEL -> ribbons.forEachIndexed { i, path ->
                translate(top = sin(angle + i) * unit * .03f) { drawPath(path, ribbonBrushes[i]) }
            }
            BackdropStyle.PONY, BackdropStyle.BLOOM -> {
                motes.forEachIndexed { i, point ->
                    val center = Offset(point.x * w, ((point.y + progress * .08f) % 1f) * h)
                    val color = (if (i % 2 == 0) c.accentCyan else c.accentSecondary).copy(alpha = .12f)
                    val radius = unit * (.002f + i % 3 * .001f)
                    drawLine(color, center - Offset(radius, 0f), center + Offset(radius, 0f), unit * .0013f)
                    drawLine(color, center - Offset(0f, radius), center + Offset(0f, radius), unit * .0013f)
                }
            }
            BackdropStyle.SUNSET -> Unit
        }
    }
}
