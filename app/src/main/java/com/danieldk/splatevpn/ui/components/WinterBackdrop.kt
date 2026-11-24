package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.AppThemeColors
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

internal data class WinterArtwork(val environment: Int, val forest: Int)
internal fun winterArtworkFor(isDark: Boolean) = if (isDark) WinterArtwork(
    R.drawable.theme_winter_night_environment, R.drawable.theme_winter_night_forest)
else WinterArtwork(R.drawable.theme_winter_day_environment, R.drawable.theme_winter_day_forest)

/** A full-height environment, two independently moving forest planes and separate weather. */
@Composable
internal fun WinterBackdrop(colors: AppThemeColors, modifier: Modifier, phase: () -> Float) {
    BoxWithConstraints(modifier.clipToBounds().background(Brush.verticalGradient(listOf(colors.background, colors.backgroundDeep)))) {
        val preview = maxHeight < maxWidth
        val artwork = winterArtworkFor(colors.isDark)
        val environment by rememberThemeBitmap(artwork.environment, preview)
        val forest by rememberThemeBitmap(artwork.forest, preview)
        val landscape = environment
        val trees = forest
        if (landscape != null && trees != null) {
            Box(Modifier.fillMaxSize().testTag(if (colors.isDark) "winterLayerScene:dark" else "winterLayerScene:light").drawWithCache {
                val w = size.width
                val h = size.height
                val fit = max(w / landscape.width, h / landscape.height) * 1.04f
                val scenerySize = IntSize((landscape.width * fit).roundToInt(), (landscape.height * fit).roundToInt())
                val sceneryOrigin = Offset((w - scenerySize.width) * .5f,
                    if (preview) h * .5f - scenerySize.height * .64f else (h - scenerySize.height) * .5f)
                val forestHeight = h * if (preview) 1.85f else 1.08f
                val forestWidth = forestHeight * (trees.width / 2f) / trees.height
                val treeSize = IntSize(forestWidth.roundToInt(), forestHeight.roundToInt())
                val treeSource = IntSize(trees.width / 2, trees.height)
                val left = Offset(-forestWidth * .42f, -forestHeight * .035f)
                val right = Offset(w - forestWidth * .54f, -forestHeight * .035f)
                val softSky = Brush.verticalGradient(listOf(colors.background.copy(alpha = if (colors.isDark) .52f else .30f),
                    Color.Transparent), 0f, h * .22f)
                val lowerShade = Brush.verticalGradient(listOf(Color.Transparent,
                    colors.backgroundDeep.copy(alpha = if (colors.isDark) .25f else .12f)), h * .84f, h)
                onDrawBehind {
                    if (w <= 0f || h <= 0f) return@onDrawBehind
                    val a = phase() * 2f * PI.toFloat()
                    withTransform({ translate(sceneryOrigin.x + sin(a) * w * .008f, sceneryOrigin.y + sin(a + .8f) * h * .002f) }) {
                        drawImage(landscape, IntOffset.Zero, IntSize(landscape.width, landscape.height), IntOffset.Zero,
                            scenerySize, filterQuality = FilterQuality.Medium)
                    }
                    withTransform({
                        translate(left.x, left.y)
                        rotate(if (preview) 0f else sin(a * 2f) * .32f, Offset(forestWidth * .2f, forestHeight * .96f))
                    }) {
                        drawImage(trees, IntOffset.Zero, treeSource, IntOffset.Zero, treeSize,
                            alpha = if (colors.isDark) .86f else .92f, filterQuality = FilterQuality.Medium)
                    }
                    withTransform({
                        translate(right.x, right.y)
                        rotate(if (preview) 0f else sin(a * 2f + 1.2f) * -.38f, Offset(forestWidth * .82f, forestHeight * .96f))
                    }) {
                        drawImage(trees, IntOffset(trees.width / 2, 0), treeSource, IntOffset.Zero, treeSize,
                            alpha = if (colors.isDark) .88f else .94f, filterQuality = FilterQuality.Medium)
                    }
                    drawRect(softSky)
                    drawRect(lowerShade)
                }
            })
        }
        Box(Modifier.fillMaxSize().drawWithCache {
            val random = Random(1203)
            val snow = List(if (preview) 12 else 74) { Offset(random.nextFloat(), random.nextFloat()) }
            val tint = if (colors.isDark) Color(0xFFEBF5FF) else Color(0xFF6F92B4)
            onDrawBehind {
                val unit = size.minDimension
                if (unit <= 0f) return@onDrawBehind
                val p = phase()
                val a = p * 2f * PI.toFloat()
                snow.forEachIndexed { i, seed ->
                    val depth = (i % 3 + 1) / 3f
                    val y = (seed.y + p * (1 + i % 3)) % 1f
                    val drift = sin(a + i) * .03f * depth
                    val x = ((seed.x + drift + 1f) % 1f) * size.width
                    val fade = sin(y * PI.toFloat()).coerceAtLeast(0f)
                    drawCircle(tint.copy(alpha = fade * (.12f + depth * if (colors.isDark) .34f else .18f)),
                        unit * (.0012f + depth * .002f), Offset(x, y * size.height))
                }
            }
        })
    }
}
