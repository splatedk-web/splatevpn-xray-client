package com.danieldk.splatevpn.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import com.danieldk.splatevpn.ui.theme.LocalAppThemeColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private fun Color.opacity(value: Float) = copy(alpha = value.coerceIn(0f, 1f))

private val STATIC_PREVIEW_PHASE: () -> Float = { 0.18f }

/** Theme cards use the same artwork as the full-screen background. */
@Composable
fun ThemeBackdropPreview(colors: AppThemeColors, modifier: Modifier = Modifier) {
    BackdropArtwork(colors, colors.accentCyan, modifier.clipToBounds(), phase = STATIC_PREVIEW_PHASE)
}

/** Cached geometry and gradients; animation state is read only during drawing. */
@Composable
private fun BackdropArtwork(
    colors: AppThemeColors,
    glow: Color,
    modifier: Modifier,
    intensity: Float = 1f,
    phase: () -> Float
) {
    if (hasThemeObject(colors.backdrop)) {
        ThemeObjectBackdrop(colors, modifier, phase, glow)
        return
    }
    Box(modifier.drawWithCache {
        val w = size.width
        val h = size.height
        val unit = size.minDimension
        val drawDetails = createBackdropDetails(colors, w, h)
        val k = colors.ambientIntensity * intensity
        val base = Brush.verticalGradient(listOf(colors.background, colors.backgroundDeep))
        val dim = if (colors.isDark) 0.13f else 0.06f
        val atmosphere = when (colors.backdrop) {
            BackdropStyle.STARS -> 0.06f
            BackdropStyle.SUNSET -> 0.35f
            else -> 1f
        }
        val glowCenter = Offset(w * 0.86f, h * 0.13f)
        val glowRadius = unit * 1.05f
        val glowBrush = Brush.radialGradient(
            listOf(glow.opacity(dim * k * atmosphere), Color.Transparent), glowCenter, glowRadius
        )
        val lowerCenter = Offset(w * 0.04f, h * 0.86f)
        val lowerRadius = unit * 0.95f
        val lowerBrush = Brush.radialGradient(
            listOf(colors.ambientB.opacity(dim * k * 0.72f * atmosphere), Color.Transparent),
            lowerCenter, lowerRadius
        )

        // Broad cubic curtains with a feathered tail, rather than thick polygonal strokes.
        val auroraPaths = if (colors.backdrop == BackdropStyle.AURORA) List(3) { i ->
            val y = h * (0.12f + i * 0.12f)
            Path().apply {
                moveTo(-w * 0.16f, y + h * 0.18f)
                cubicTo(w * 0.18f, y - h * 0.14f, w * 0.42f, y + h * 0.30f, w * 0.68f, y + h * 0.04f)
                cubicTo(w * 0.86f, y - h * 0.12f, w * 1.02f, y + h * 0.02f, w * 1.16f, y - h * 0.16f)
                lineTo(w * 1.16f, y + h * 0.12f)
                cubicTo(w * 0.88f, y + h * 0.22f, w * 0.84f, y + h * 0.06f, w * 0.68f, y + h * 0.25f)
                cubicTo(w * 0.42f, y + h * 0.46f, w * 0.16f, y + h * 0.08f, -w * 0.16f, y + h * 0.38f)
                close()
            }
        } else emptyList()
        val auroraBrushes = if (colors.backdrop == BackdropStyle.AURORA) listOf(colors.accentCyan, colors.ambientC, colors.ambientB).mapIndexed { i, tone ->
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.28f to tone.opacity(0.03f * k),
                0.45f to tone.opacity(0.18f * k),
                0.70f to tone.opacity(0.055f * k),
                1f to Color.Transparent,
                startY = h * (0.02f + i * 0.12f), endY = h * (0.50f + i * 0.12f)
            )
        } else emptyList()

        val horizon = h * 0.69f
        val sunCenter = Offset(w * 0.72f, horizon - unit * 0.17f)
        val sunRadius = unit * 0.18f
        val sunDisc = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(sunCenter - Offset(sunRadius, sunRadius), Size(sunRadius * 2, sunRadius * 2)))
        }
        val sunHaloRadius = sunRadius * 2.7f
        val sunHalo = Brush.radialGradient(
            listOf(colors.accentSecondary.opacity(0.20f * k), colors.accentCyan.opacity(0.075f * k), Color.Transparent),
            sunCenter, sunHaloRadius
        )
        val sunBrush = Brush.verticalGradient(
            listOf(Color(0xFFFFDF94).opacity(0.58f), colors.accentSecondary.opacity(0.43f), colors.accentCyan.opacity(0.28f)),
            sunCenter.y - sunRadius, sunCenter.y + sunRadius
        )
        val mountains = if (colors.backdrop == BackdropStyle.SUNSET) List(2) { i ->
            val y = horizon + h * i * 0.025f
            Path().apply {
                moveTo(0f, y + h * 0.025f)
                cubicTo(w * 0.16f, y - h * 0.10f, w * 0.24f, y + h * 0.045f, w * 0.42f, y)
                cubicTo(w * 0.62f, y - h * 0.08f, w * 0.74f, y + h * 0.055f, w, y - h * 0.045f)
                lineTo(w, h); lineTo(0f, h); close()
            }
        } else emptyList()
        val mountainBrushes = if (colors.backdrop == BackdropStyle.SUNSET) List(2) { i ->
            Brush.verticalGradient(
                listOf(lerp(colors.backgroundDeep, colors.ambientC, if (i == 0) 0.16f else 0.08f), colors.backgroundDeep),
                horizon, h
            )
        } else emptyList()
        // Wave crests cover the water, not just a stack of strokes beneath the sun.
        val waterTop = horizon + h * 0.045f
        val oceanWaves = if (colors.backdrop == BackdropStyle.SUNSET) List(20) { Path() } else emptyList()
        val waveStroke = if (colors.backdrop == BackdropStyle.SUNSET) List(20) { i -> Stroke(width = unit * (0.001f + i / 20f * 0.0018f), cap = StrokeCap.Round) } else emptyList()
        val waterBrush = Brush.verticalGradient(
            listOf(colors.backgroundDeep.opacity(0f), colors.backgroundDeep.opacity(0.8f)), waterTop, h)
        val coolWaveBrushes = if (colors.backdrop == BackdropStyle.SUNSET) List(20) { i ->
            val shift = sin(i * 1.8f) * w * 0.24f
            Brush.horizontalGradient(
                0f to Color.Transparent,
                0.17f to colors.accentViolet.opacity(0.10f),
                0.29f to Color.Transparent,
                0.48f to colors.accentSecondary.opacity(0.055f),
                0.62f to Color.Transparent,
                0.82f to colors.accentViolet.opacity(0.10f),
                1f to Color.Transparent, startX = -w * 0.15f + shift, endX = w * 1.15f + shift)
        } else emptyList()
        val reflectionBrushes = if (colors.backdrop == BackdropStyle.SUNSET) List(20) { i ->
            val t = (i + 1f) / 21f
            val half = unit * (0.025f + t * 0.30f)
            Brush.horizontalGradient(
                listOf(Color.Transparent, colors.accentSecondary.opacity((0.48f - t * 0.26f) * k), Color.Transparent),
                sunCenter.x - half, sunCenter.x + half)
        } else emptyList()
        val cloudCenters = if (colors.backdrop == BackdropStyle.SUNSET) List(3) { i -> Offset(w * (0.12f + i * 0.36f), h * (0.18f + i * 0.055f)) } else emptyList()
        val cloudRadius = unit * 0.55f
        val cloudBrushes = cloudCenters.map { center -> Brush.radialGradient(
            listOf(colors.accentViolet.opacity(0.08f * k), colors.accentSecondary.opacity(0.025f * k), Color.Transparent), center, cloudRadius) }
        val silkBrush = Brush.horizontalGradient(listOf(Color.Transparent, colors.accentSecondary.opacity(0.06f * k), Color.Transparent))
        val silkPath = Path().apply {
            moveTo(-w * 0.2f, h * 0.26f)
            cubicTo(w * 0.24f, h * 0.54f, w * 0.44f, h * 0.12f, w * 1.2f, h * 0.50f)
        }
        // All perspective rays share one vanishing point.
        val gridHorizon = h * 0.61f
        val gridOrigin = Offset(w * 0.66f, gridHorizon)
        val gridBrush = Brush.verticalGradient(
            listOf(Color.Transparent, colors.ambientB.opacity(0.12f * k), colors.ambientC.opacity(0.17f * k)),
            gridHorizon, h
        )
        val gridRays = if (colors.backdrop == BackdropStyle.GRID) List(15) { i -> Offset(w * (-1.3f + i * 0.25f), h) } else emptyList()
        val gridRows = if (colors.backdrop == BackdropStyle.GRID) List(11) { i ->
            val t = (i + 1f) / 12f
            gridHorizon + (h - gridHorizon) * t * t
        } else emptyList()
        val gridHaloCenter = Offset(w * 0.66f, gridHorizon)
        val gridHaloRadius = unit * 0.65f
        val gridHalo = Brush.radialGradient(
            listOf(colors.ambientB.opacity(0.14f * k), Color.Transparent), gridHaloCenter, gridHaloRadius
        )

        val orbitCenter = Offset(w * 0.96f, h * 0.28f)
        val orbitBrush = Brush.sweepGradient(
            listOf(Color.Transparent, colors.accentSecondary.opacity(0.05f), colors.accentCyan.opacity(0.20f * k), Color.Transparent),
            orbitCenter
        )
        val orbitStroke = Stroke(width = unit * 0.0025f)
        val orbitHaloRadius = unit * 0.78f
        val orbitHalo = Brush.radialGradient(
            listOf(colors.accentCyan.opacity(0.11f * k), Color.Transparent), orbitCenter, orbitHaloRadius
        )
        val random = Random(7341)
        val stars = if (colors.backdrop == BackdropStyle.STARS) List(90) { Offset(random.nextFloat() * w, random.nextFloat() * h) } else emptyList()
        val starRadii = if (colors.backdrop == BackdropStyle.STARS) List(90) { i -> unit * if (i % 13 == 0) 0.0023f else 0.0012f } else emptyList()
        val fineStroke = Stroke(width = unit * 0.0015f, cap = StrokeCap.Round)
        val readability = Brush.verticalGradient(
            0f to colors.background.opacity(if (colors.isDark) 0.10f else 0.05f),
            0.45f to Color.Transparent,
            1f to colors.backgroundDeep.opacity(if (colors.isDark) 0.30f else 0.10f)
        )

        onDrawBehind {
            if (w <= 0f || h <= 0f) return@onDrawBehind
            val progress = phase()
            val angle = progress * 2f * PI.toFloat()
            val drift = sin(angle)
            drawRect(base)
            translate(left = drift * unit * 0.075f, top = cos(angle) * unit * 0.04f) {
                drawCircle(glowBrush, glowRadius, glowCenter)
                drawCircle(lowerBrush, lowerRadius, lowerCenter)
            }
            drawDetails(progress)
            when (colors.backdrop) {
                BackdropStyle.OCEAN, BackdropStyle.SAKURA, BackdropStyle.PONY, BackdropStyle.BLOOM -> Unit
                BackdropStyle.AURORA -> auroraPaths.forEachIndexed { i, path ->
                    translate(left = cos(angle + i) * unit * 0.045f, top = sin(angle + i * 1.2f) * unit * 0.045f) {
                        drawPath(path, auroraBrushes[i])
                    }
                }
                BackdropStyle.SUNSET -> {
                    cloudCenters.forEachIndexed { i, center ->
                        translate(left = sin(angle + i) * unit * 0.065f) {
                            scale(scaleX = 1.4f, scaleY = 0.32f, pivot = center) {
                                drawCircle(cloudBrushes[i], cloudRadius, center)
                            }
                        }
                    }
                    drawCircle(sunHalo, sunHaloRadius * (1f + drift * 0.045f), sunCenter)
                    drawCircle(sunBrush, sunRadius, sunCenter)
                    clipPath(sunDisc) {
                        for (band in 0..6) {
                            drawRect(colors.background.copy(alpha = 0.52f),
                                topLeft = Offset(sunCenter.x - sunRadius, sunCenter.y + sunRadius * (band / 8f)),
                                size = Size(sunRadius * 2f, sunRadius * (0.025f + band * 0.009f)))
                        }
                    }
                    mountains.forEachIndexed { i, path -> drawPath(path, mountainBrushes[i]) }
                    drawRect(waterBrush, topLeft = Offset(0f, waterTop), size = Size(w, h - waterTop))
                    oceanWaves.forEachIndexed { i, path ->
                        val t = (i + 1f) / 21f
                        val y = waterTop + (h - waterTop) * t * t
                        val amplitude = unit * (0.001f + t * t * 0.007f)
                        path.rewind()
                        // Smooth cubic interpolation of traveling ripples, with perspective-scaled amplitude.
                        fun waveY(x: Float): Float = y + amplitude *
                            (sin(x * 2f * PI.toFloat() * (1.4f + t) - angle * 2f + i * 0.65f) +
                                0.35f * sin(x * 2f * PI.toFloat() * 3f + angle + i))
                        path.moveTo(0f, waveY(0f))
                        for (segment in 0 until 12) {
                            val x = segment / 12f
                            val next = (segment + 1f) / 12f
                            path.cubicTo((x + 1f / 36f) * w, waveY(x + 1f / 36f),
                                (next - 1f / 36f) * w, waveY(next - 1f / 36f), next * w, waveY(next))
                        }
                        drawPath(path, coolWaveBrushes[i], style = waveStroke[i])
                        drawPath(path, reflectionBrushes[i], style = waveStroke[i])
                    }
                }
                BackdropStyle.GRID -> {
                    drawCircle(gridHalo, gridHaloRadius, gridHaloCenter)
                    clipRect(top = gridHorizon) {
                        gridRays.forEach { drawLine(gridBrush, gridOrigin, it, unit * 0.0018f) }
                        for (row in 0 until 11) {
                            val t = (row / 11f + progress) % 1f
                            val y = gridHorizon + (h - gridHorizon) * t * t
                            drawLine(gridBrush, Offset(0f, y), Offset(w, y), unit * 0.0018f, alpha = sin(t * PI.toFloat()))
                        }
                    }
                }
                BackdropStyle.RINGS -> {
                    drawCircle(orbitHalo, orbitHaloRadius, orbitCenter)
                    for (i in 0..2) {
                        rotate(degrees = progress * 360f + i * 90f, pivot = orbitCenter) {
                            drawCircle(orbitBrush, unit * (0.38f + i * 0.20f + drift * 0.025f), orbitCenter, style = orbitStroke)
                        }
                    }
                }
                BackdropStyle.STARS -> stars.forEachIndexed { i, center ->
                    val twinkle = 0.12f + 0.12f * (sin(angle * (1 + i % 3) + i * 2.1f) + 1f)
                    val shifted = Offset(center.x + drift * unit * 0.025f, center.y + cos(angle) * unit * 0.025f)
                    drawCircle(if (i % 13 == 0) colors.accentSecondary.opacity(twinkle) else Color(0xFFD7E3F4).opacity(twinkle), starRadii[i], shifted)
                }
                BackdropStyle.SOFT, BackdropStyle.PASTEL -> {
                    translate(top = drift * unit * 0.05f) {
                        drawPath(silkPath, silkBrush, style = Stroke(width = unit * 0.12f))
                        drawPath(silkPath, silkBrush, style = fineStroke)
                    }
                }
            }
            drawRect(readability)
        }
    }) {
        ThemeCharacterArtwork(colors, Modifier.fillMaxSize(), phase)
        ThemeCharacterMagic(colors, Modifier.fillMaxSize(), phase)
    }
}

@Composable
fun LiquidAmbientBackground(
    modifier: Modifier = Modifier,
    accentGlow: Color? = null,
    isConnected: Boolean = false,
    isConnecting: Boolean = false,
    glassTransparency: Float = 0f,
    glassBlurEnabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    val colors = LocalAppThemeColors.current
    val sourceLayer = rememberGraphicsLayer()
    val blurredLayer = rememberGraphicsLayer()
    val view = LocalView.current
    val backdrop = remember(blurredLayer, view) { GlassBackdrop(blurredLayer, view) }
    val location = remember { IntArray(2) }
    val blurEnabled = glassBlurEnabled && glassTransparency > .001f && android.os.Build.VERSION.SDK_INT >= 31
    val blurRadius = with(LocalDensity.current) { (14 + glassTransparency * 14).dp.toPx() }
    blurredLayer.renderEffect = if (blurEnabled) BlurEffect(blurRadius, blurRadius, TileMode.Clamp) else null
    val capture = if (blurEnabled) Modifier.onGloballyPositioned { coordinates ->
        view.getLocationOnScreen(location)
        backdrop.origin = coordinates.positionInRoot() + Offset(location[0].toFloat(), location[1].toFloat())
    }.drawWithContent {
        sourceLayer.record { this@drawWithContent.drawContent() }
        blurredLayer.record { drawLayer(sourceLayer) }
        drawLayer(sourceLayer)
        backdrop.ready = true
        backdrop.revision++
    } else Modifier
    val glow = animateColorAsState(accentGlow ?: colors.accentCyan, tween(300), label = "ambient_glow")
    val transition = rememberInfiniteTransition(label = "ambient_motion")
    val phase = transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(28000, easing = LinearEasing), RepeatMode.Restart),
        label = "ambient_phase"
    )
    CompositionLocalProvider(LocalGlassTransparency provides glassTransparency,
        LocalGlassBlurEnabled provides glassBlurEnabled,
        LocalGlassBackdrop provides if (blurEnabled) backdrop else null) {
    Box(modifier.fillMaxSize()) {
        BackdropArtwork(
            colors = colors,
            glow = glow.value,
            modifier = Modifier.fillMaxSize().then(capture),
            intensity = if (isConnected) 1.08f else if (isConnecting) 1.04f else 1f,
            phase = { phase.value }
        )
        content()
    }
    }
}
