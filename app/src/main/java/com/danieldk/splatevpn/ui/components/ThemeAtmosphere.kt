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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Low-frequency light, weather and depth behind the isolated theme objects. */
@Composable
internal fun ThemeAtmosphere(c: AppThemeColors, modifier: Modifier, phase: () -> Float, preview: Boolean) {
    Box(modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val unit = size.minDimension
        val style = c.backdrop
        val random = Random(817)
        val dust = List(64) { Offset(random.nextFloat(), random.nextFloat()) }
        val pink = Color(0xFFF29BC2)
        val cyan = Color(0xFF62DDF3)
        val primary = when(style) {
            BackdropStyle.SAKURA -> pink
            BackdropStyle.SUNSET -> Color(0xFFFFA169)
            BackdropStyle.RINGS -> Color(0xFFEF2248)
            BackdropStyle.AURORA -> Color(0xFF42D9AD)
            else -> cyan
        }
        val secondary = when(style) {
            BackdropStyle.GRID -> Color(0xFFBE48E9)
            BackdropStyle.SUNSET -> Color(0xFFBC6BC7)
            BackdropStyle.RINGS -> Color(0xFF6A1420)
            else -> c.ambientB
        }
        val atmosphereGain = if(style == BackdropStyle.RINGS) .55f else 1f
        val haloCenter = Offset(w * .85f, h * .24f)
        val haloRadius = unit * 1.3f
        val halo = Brush.radialGradient(listOf(primary.copy(alpha = .17f * atmosphereGain), primary.copy(alpha = .045f * atmosphereGain), Color.Transparent), haloCenter, haloRadius)
        val mistCenter = Offset(w * .14f, h * .67f)
        val mistRadius = unit * 1.15f
        val mist = Brush.radialGradient(listOf(secondary.copy(alpha = .15f * atmosphereGain), Color.Transparent), mistCenter, mistRadius)
        val lowerGlow = Brush.verticalGradient(listOf(Color.Transparent, primary.copy(alpha = .065f * atmosphereGain), c.backgroundDeep.copy(alpha = .35f)), h * .48f, h)
        val moonCenter = Offset(w * .77f, h * .20f)
        val moonRadius = unit * .052f
        val moonHalo = Brush.radialGradient(listOf(Color(0xFFEBD9FF).copy(alpha = .22f), Color.Transparent), moonCenter, moonRadius * 4.8f)
        val moon = Brush.radialGradient(listOf(Color(0xFFFFE9EE).copy(alpha = .63f), Color(0xFFCDBCE9).copy(alpha = .28f)), moonCenter, moonRadius)
        val ribbons = List(3) { i -> Path().apply {
            val y = h * (.20f + i * .15f)
            moveTo(-w * .3f, y + h * .09f)
            cubicTo(w * .18f, y - h * .12f, w * .48f, y + h * .13f, w * 1.3f, y - h * .16f)
        } }
        val ribbonBrushes = List(3) { i -> Brush.horizontalGradient(listOf(
            Color.Transparent, (if(i % 2 == 0) primary else secondary).copy(alpha = if(style == BackdropStyle.AURORA) .10f else .035f), Color.Transparent), -w * .3f, w * 1.3f) }
        val beams = List(5) { i -> Path().apply {
            moveTo(w * (.35f + i * .17f), -h * .08f)
            lineTo(w * (.39f + i * .17f), -h * .08f)
            lineTo(w * (-.35f + i * .32f), h * .88f)
            lineTo(w * (-.65f + i * .32f), h * .88f); close()
        } }
        val beamBrush = Brush.verticalGradient(listOf(cyan.copy(alpha = .11f), cyan.copy(alpha = .025f), Color.Transparent), 0f, h * .88f)
        val stroke = Stroke(unit * .08f, cap = StrokeCap.Round)
        val fineStroke = Stroke(unit * .002f, cap = StrokeCap.Round)
        onDrawBehind {
            if(unit <= 0f) return@onDrawBehind
            val p = phase()
            val a = p * 2f * PI.toFloat()
            translate(sin(a) * unit * .10f, cos(a) * unit * .035f) { drawCircle(halo, haloRadius, haloCenter) }
            translate(cos(a) * unit * .08f, sin(a) * unit * .025f) {
                scale(1.3f, .40f, mistCenter) { drawCircle(mist, mistRadius, mistCenter) }
            }
            drawRect(lowerGlow, Offset(0f, h * .48f), Size(w, h * .52f))
            if(style == BackdropStyle.SAKURA) {
                drawCircle(moonHalo, moonRadius * 4.8f, moonCenter)
                drawCircle(moon, moonRadius, moonCenter)
            }
            if(style == BackdropStyle.OCEAN) beams.forEachIndexed { i, path ->
                translate(sin(a + i) * unit * .08f) { drawPath(path, beamBrush) }
            }
            if(style != BackdropStyle.GRID && style != BackdropStyle.RINGS) ribbons.forEachIndexed { i, path ->
                translate(sin(a + i) * unit * .07f, cos(a + i) * unit * .025f) {
                    drawPath(path, ribbonBrushes[i], style = stroke)
                    if(style == BackdropStyle.AURORA) drawPath(path, ribbonBrushes[i], style = fineStroke)
                }
            }
            dust.take(if(preview) 12 else 64).forEachIndexed { i, point ->
                val depth = .4f + (i % 4) * .2f
                if(style == BackdropStyle.GRID) {
                    val y = ((point.y + p * (3 + i % 3)) % 1f) * h
                    val x = ((point.x - p + 1f) % 1f) * w
                    drawLine(cyan.copy(alpha = .055f + depth * .055f), Offset(x, y), Offset(x - unit * .012f * depth, y + unit * .04f * depth), unit * .001f)
                } else {
                    val y = ((point.y - p * (1 + i % 2) + 2f) % 1f) * h
                    val x = point.x * w + sin(a + i) * unit * .02f
                    val twinkle = (.5f + .5f * sin(a * 2f + i)) * sin(y / h * PI.toFloat()).coerceAtLeast(0f)
                    val tint = if(style == BackdropStyle.SAKURA && i % 4 == 0) Color(0xFFFFE3A3) else primary
                    drawCircle(tint.copy(alpha = twinkle * .25f), unit * (.001f + depth * .0012f), Offset(x, y))
                }
            }
        }
    })
}
