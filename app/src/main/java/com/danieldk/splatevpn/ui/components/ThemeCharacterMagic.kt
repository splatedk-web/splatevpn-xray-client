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
import androidx.compose.ui.graphics.drawscope.translate
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Draw-only animation: geometry is cached; particles stay behind interactive content. */
@Composable
internal fun ThemeCharacterMagic(colors: AppThemeColors, modifier: Modifier, phase: () -> Float) {
    if (colors.backdrop != BackdropStyle.PONY && colors.backdrop != BackdropStyle.BLOOM) return
    Box(modifier.drawWithCache {
        val random = Random(if (colors.backdrop == BackdropStyle.PONY) 818 else 419)
        val particles = List(46) { floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat()) }
        val tones = listOf(colors.accentCyan, colors.accentSecondary, colors.ambientC)
        val radius = size.minDimension * .022f
        val glows = tones.map { tone -> Brush.radialGradient(listOf(tone.copy(alpha = .19f), Color.Transparent), Offset.Zero, radius) }
        val star = Path()
        val trail = Path()
        val unit = size.minDimension
        val width = size.width
        val height = size.height
        val trailStroke = Stroke(unit * .002f, cap = StrokeCap.Round)
        val trailBrush = Brush.horizontalGradient(listOf(Color.Transparent, colors.ambientC.copy(alpha = .22f),
            colors.accentSecondary.copy(alpha = .18f), Color.Transparent))
        onDrawBehind {
            val time = phase() * 2f * PI.toFloat()
            particles.forEachIndexed { index, p ->
                val life = (phase() * (2f + p[2] * 2f) + p[1]) % 1f
                val fade = sin(life * PI.toFloat()).coerceAtLeast(0f)
                val shimmer = .55f + .45f * sin(time * (6f + p[3] * 3f) + index)
                val x = width * (.04f + p[0] * .92f) + sin(time * 2 + index) * unit * .024f
                val y = height * (.98f - life * .46f)
                val center = Offset(x, y)
                val tone = tones[index % tones.size]
                val alpha = fade * (.25f + shimmer * .45f)
                val extent = unit * (.0028f + p[3] * .004f)
                if (index % 5 == 0) {
                    translate(x, y) { drawCircle(glows[index % glows.size], radius, Offset.Zero, alpha = fade * shimmer) }
                    star.rewind()
                    star.moveTo(x, y - extent * 2.4f)
                    star.lineTo(x + extent * .4f, y - extent * .4f)
                    star.lineTo(x + extent * 2.0f, y)
                    star.lineTo(x + extent * .4f, y + extent * .4f)
                    star.lineTo(x, y + extent * 2.4f)
                    star.lineTo(x - extent * .4f, y + extent * .4f)
                    star.lineTo(x - extent * 2.0f, y)
                    star.lineTo(x - extent * .4f, y - extent * .4f)
                    star.close()
                    drawPath(star, tone.copy(alpha = alpha))
                    drawCircle(Color.White.copy(alpha = fade * .85f), extent * .25f, center)
                } else {
                    drawCircle(tone.copy(alpha = alpha * .55f), extent * 1.8f, center)
                    drawCircle(Color.White.copy(alpha = fade * (.35f + shimmer * .50f)), extent * .65f, center)
                }
            }
            // A moving ribbon of light follows the group / Bloom's magic, with traveling sparkles.
            trail.rewind()
            repeat(31) { i ->
                val t = i / 30f
                val x = width * (.10f + t * .83f)
                val y = height * (if (colors.backdrop == BackdropStyle.PONY) .84f else .71f) -
                    sin(t * PI.toFloat() + time * .65f) * unit * .10f + cos(time + t * 5) * unit * .018f
                if (i == 0) trail.moveTo(x, y) else trail.lineTo(x, y)
                if (i % 5 == 0) drawCircle(tones[i % tones.size].copy(alpha = .25f + .15f * sin(time * 4 + i)), unit * .003f, Offset(x, y))
            }
            drawPath(trail, trailBrush, style = trailStroke)
        }
    })
}
