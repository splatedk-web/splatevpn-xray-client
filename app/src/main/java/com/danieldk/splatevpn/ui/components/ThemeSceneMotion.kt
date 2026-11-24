package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Theme-specific movement uses cached paths and shaders, with no extra blur passes. */
@Composable
internal fun ThemeSceneMotion(c: AppThemeColors, modifier: Modifier, phase: () -> Float) {
    if (c.backdrop !in listOf(BackdropStyle.AURORA, BackdropStyle.OCEAN, BackdropStyle.RINGS)) return
    Box(modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val unit = size.minDimension
        val paths = List(3) { Path() }
        val auroraTones = listOf(Color(0xFF45E0B5), Color(0xFF59CFE8), Color(0xFF9B89F5))
        val curtains = auroraTones.mapIndexed { layer, tone -> Brush.verticalGradient(
            0f to Color.Transparent, .28f to tone.copy(alpha = .025f),
            .52f to tone.copy(alpha = .18f), .72f to tone.copy(alpha = .045f), 1f to Color.Transparent,
            startY = h * (.09f + layer * .065f), endY = h * (.52f + layer * .065f)) }
        val auroraStroke = Stroke(unit * .0016f, cap = StrokeCap.Round)
        val causticStroke = Stroke(unit * .0025f, cap = StrokeCap.Round)
        val caustic = Brush.horizontalGradient(listOf(Color.Transparent, c.accentCyan.copy(alpha = .11f), Color.Transparent))
        val redOrbit = Brush.sweepGradient(listOf(Color.Transparent, c.accentCyan.copy(alpha = .24f), Color.Transparent))
        val orbitStroke = Stroke(unit * .0018f, cap = StrokeCap.Round)
        onDrawBehind {
            if (unit <= 0f) return@onDrawBehind
            val p = phase()
            val a = p * 2f * PI.toFloat()
            when (c.backdrop) {
                BackdropStyle.AURORA -> {
                    paths.forEachIndexed { layer, path ->
                        path.reset()
                        // A continuous curtain sweeps across the upper sky; it never rises from a peak.
                        for (i in 0..32) {
                            val u = i / 32f
                            val x = w * (-.16f + u * 1.32f)
                            val y = h * (.15f + layer * .065f + .045f * sin(u * 5f + a + layer))
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        for (i in 32 downTo 0) {
                            val u = i / 32f
                            path.lineTo(w * (-.16f + u * 1.32f),
                                h * (.38f + layer * .07f + .075f * sin(u * 5f + a + layer + .35f)))
                        }
                        path.close()
                        drawPath(path, curtains[layer])
                    }
                    for (i in 0..52) {
                        val u = i / 52f
                        val x = w * u
                        val y = h * (.19f + .045f * sin(u * 5f + a))
                        val length = h * (.15f + .045f * sin(u * 8f + a + .8f))
                        val alpha = (.035f + .035f * sin(u * 9f + a * 2f)).coerceAtLeast(.005f)
                        drawLine(curtains[0], Offset(x, y),
                            Offset(x + w * .035f, y + length), auroraStroke.width * (1f + alpha), StrokeCap.Round)
                    }
                }
                BackdropStyle.OCEAN -> paths.forEachIndexed { layer, path ->
                    path.reset()
                    for (i in 0..32) {
                        val u = i / 32f
                        val x = u * w
                        val y = h * (.31f + layer * .19f) + sin(u * 8f + a * 2f + layer) * unit * .035f
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, caustic, style = causticStroke)
                }
                BackdropStyle.RINGS -> {
                    val center = Offset(w * .85f, h * .64f)
                    rotate(sin(a) * 12f - 25f, center) {
                        drawOval(redOrbit, Offset(w * .40f, h * .53f), Size(w * .90f, h * .18f), style = orbitStroke)
                    }
                    val glint = Offset(w * (.83f + sin(a * 2f) * .09f), h * (.58f + cos(a * 2f) * .05f))
                    drawCircle(c.accentCyan.copy(alpha = .12f), unit * .009f, glint)
                }
                else -> Unit
            }
        }
    })
}
