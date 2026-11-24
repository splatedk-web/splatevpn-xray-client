package com.danieldk.splatevpn.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.BackdropStyle
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class ArtPart(val source: Rect, val mask: List<Offset> = emptyList())
internal data class ArtLayer(
    val part: Int, val x: Float, val y: Float, val width: Float, val height: Float,
    val anchorX: Float = .5f, val anchorY: Float = 1f,
    val pivotX: Float = .5f, val pivotY: Float = 1f,
    val moveX: Float = 0f, val moveY: Float = 0f, val turn: Float = 0f,
    val cycles: Int = 1, val shift: Float = 0f, val alpha: Float = .75f,
    val pulse: Float = 0f, val breathe: Float = 0f
)
internal data class LayerArt(val resource: Int, val aspect: Float, val parts: List<ArtPart>, val layers: List<ArtLayer>)
private fun part(l: Float, t: Float, r: Float, b: Float, polygon: List<Offset> = emptyList()) = ArtPart(Rect(l,t,r,b), polygon)

internal fun themeLayerArtFor(style: BackdropStyle): LayerArt = when(style) {
    BackdropStyle.GRID -> LayerArt(R.drawable.theme_cyberpunk_layers_v2, 1.50f, listOf(
        part(0f,0f,.60f,.58f), part(.60f,0f,1f,.57f),
        part(0f,.58f,.61f,1f), part(.53f,.64f,1f,.82f)
    ), listOf(
        ArtLayer(1,.88f,.94f,.58f,.92f,alpha=.65f,moveX=.004f,cycles=1,shift=.8f),
        ArtLayer(0,.56f,1.03f,1.18f,.88f,alpha=.84f,moveX=.006f,cycles=1),
        ArtLayer(2,.70f,1.06f,1.16f,.35f,alpha=.25f,moveX=.025f,cycles=1,shift=1.4f),
        ArtLayer(3,.73f,.43f,.23f,.12f,alpha=.78f,moveX=.085f,moveY=.012f,cycles=2,shift=.6f)
    ))
    BackdropStyle.SAKURA -> LayerArt(R.drawable.theme_sakura_object, 1.6f, listOf(
        part(.09f,0f,.45f,.495f), part(.50f,.04f,.98f,.49f),
        part(0f,.56f,.49f,.91f), part(.58f,.59f,.91f,.91f)
    ), listOf(
        ArtLayer(0,.82f,1f,.76f,.96f,alpha=.70f),
        ArtLayer(1,.88f,.46f,.88f,.45f,anchorX=.95f,pivotX=.95f,pivotY=.95f,turn=3.8f,cycles=3,shift=.6f),
        ArtLayer(2,.86f,.70f,.79f,.35f,anchorX=.95f,pivotX=.95f,pivotY=.94f,turn=5f,cycles=2,shift=2.2f),
        ArtLayer(1,.89f,.40f,.65f,.32f,anchorX=.95f,pivotX=.95f,pivotY=.94f,turn=4.4f,cycles=2,shift=1.3f,alpha=.65f),
        ArtLayer(3,.41f,.72f,.14f,.14f,moveX=.045f,moveY=.055f,turn=24f,cycles=2,shift=1.1f,alpha=.7f)
    ))
    BackdropStyle.OCEAN -> LayerArt(R.drawable.theme_ocean_layers_v2, 1.35f, listOf(
        part(0f,.12f,.50f,.46f), part(.50f,0f,1f,.50f),
        part(0f,.50f,.50f,1f), part(.50f,.52f,1f,.96f)
    ), listOf(
        ArtLayer(0,.57f,1.10f,1.16f,.46f,alpha=.78f),
        ArtLayer(1,.89f,1.02f,.47f,.88f,pivotX=.42f,pivotY=.95f,turn=2.8f,cycles=2,shift=.4f,alpha=.76f),
        ArtLayer(2,.13f,1.06f,.36f,.56f,pivotY=.96f,turn=1.2f,cycles=2,shift=1.8f,alpha=.62f),
        ArtLayer(3,.52f,.44f,.19f,.23f,pivotY=.5f,moveX=.065f,moveY=.020f,turn=2f,cycles=2,shift=2.2f,alpha=.62f)
    ))
    BackdropStyle.SUNSET -> LayerArt(R.drawable.theme_sunset_object,.90f,listOf(
        part(0f,0f,.49f,.49f),part(.48f,.16f,.99f,.45f),
        part(0f,.60f,.62f,.98f, listOf(Offset(0f,.60f),Offset(.49f,.60f),Offset(.49f,.71f),Offset(.54f,.75f),Offset(.62f,.82f),Offset(.62f,.98f),Offset(0f,.98f))),
        part(.51f,.50f,.99f,.97f, listOf(Offset(.51f,.50f),Offset(.99f,.50f),Offset(.99f,.97f),Offset(.64f,.97f),Offset(.64f,.82f),Offset(.51f,.72f)))
    ),listOf(
        ArtLayer(0,.75f,.58f,.52f,.56f,moveY=.008f,alpha=.67f,pulse=.07f,breathe=.015f),
        ArtLayer(1,.52f,.82f,.97f,.58f,moveX=.011f,cycles=1,alpha=.57f),
        ArtLayer(2,.53f,1f,1.04f,.57f,moveX=.022f,cycles=1,shift=.7f,alpha=.83f),
        ArtLayer(3,.83f,.86f,.45f,.59f,pivotX=.57f,pivotY=.96f,turn=1.8f,cycles=3,shift=.4f,alpha=.78f)
    ))
    BackdropStyle.AURORA -> LayerArt(R.drawable.theme_aurora_object,1.65f,listOf(
        part(.14f,.03f,.44f,.50f),part(.62f,.03f,.91f,.50f),part(.02f,.60f,.50f,.91f)
    ),listOf(
        // Broad mountain silhouette anchors the scene; aurora curtains are drawn in the sky.
        ArtLayer(2,.50f,1f,1.20f,.58f,moveX=.003f,cycles=1,alpha=.82f)
    ))
    else -> LayerArt(R.drawable.theme_red_object,1.25f,listOf(
        part(.02f,.01f,.50f,.51f),part(.55f,.02f,.98f,.51f),part(.06f,.54f,.46f,.97f)
    ),listOf(
        // Two smaller sculptures at the edge leave the central controls and left labels quiet.
        ArtLayer(1,.84f,.57f,.43f,.45f,pivotY=.5f,moveX=.022f,moveY=.028f,turn=14f,cycles=2,shift=1.8f,alpha=.64f,pulse=.035f,breathe=.025f),
        ArtLayer(0,.79f,.98f,.47f,.48f,pivotY=.5f,moveX=.018f,moveY=.022f,turn=-12f,cycles=2,shift=.3f,alpha=.72f,breathe=.018f)
    ))
}

private data class DrawLayer(val layer: ArtLayer, val source: IntOffset, val sourceSize: IntSize,
    val destination: IntSize, val origin: Offset, val pivot: Offset, val mask: Path?)

/** One atlas per theme, independent transforms per part; cached source regions and geometry. */
@Composable
internal fun ThemeLayeredObjects(style: BackdropStyle, modifier: Modifier, phase: () -> Float, preview: Boolean) {
    val art = remember(style) { themeLayerArtFor(style) }
    val bitmap by rememberThemeBitmap(art.resource, preview)
    val image = bitmap ?: return
    Box(modifier.testTag("themeLayerScene").drawWithCache {
        val sceneWidth = size.width * if (preview) 1f else if (style == BackdropStyle.AURORA) 1.1f else 1.02f
        val sceneHeight = if (preview) size.height * (if (style == BackdropStyle.AURORA) 1.75f else .98f)
            else min(size.height * .82f, sceneWidth * (if (style == BackdropStyle.OCEAN) 1.35f else art.aspect))
        val sceneLeft = size.width - sceneWidth
        val sceneTop = size.height - sceneHeight - if(preview) 0f else 74.dp.toPx()
        val plans = art.layers.map { layer ->
            val part = art.parts[layer.part]
            val source = IntOffset((part.source.left * image.width).roundToInt(),(part.source.top * image.height).roundToInt())
            val sourceSize = IntSize((part.source.width * image.width).roundToInt(),(part.source.height * image.height).roundToInt())
            val fit = min(sceneWidth * layer.width / sourceSize.width, sceneHeight * layer.height / sourceSize.height)
            val dst = IntSize((sourceSize.width * fit).roundToInt().coerceAtLeast(1),(sourceSize.height * fit).roundToInt().coerceAtLeast(1))
            val origin = Offset(sceneLeft + layer.x * sceneWidth - dst.width * layer.anchorX,sceneTop + layer.y * sceneHeight - dst.height * layer.anchorY)
            val mask = if(part.mask.isEmpty()) null else Path().apply {
                part.mask.forEachIndexed { index, point ->
                    val x = (point.x - part.source.left) / part.source.width * dst.width
                    val y = (point.y - part.source.top) / part.source.height * dst.height
                    if(index == 0) moveTo(x,y) else lineTo(x,y)
                }; close()
            }
            DrawLayer(layer,source,sourceSize,dst,origin,Offset(dst.width * layer.pivotX,dst.height * layer.pivotY),mask)
        }
        onDrawBehind {
            val a = phase() * 2f * PI.toFloat()
            plans.forEach { plan ->
                val layer = plan.layer
                val t = a * layer.cycles + layer.shift
                val wave = if(preview) 0f else sin(t)
                val crossWave = if(preview) 0f else sin(t + .9f)
                val opacity = if(preview) .95f else (layer.alpha + layer.pulse * wave).coerceIn(0f,1f)
                withTransform({
                    translate(plan.origin.x + wave * layer.moveX * sceneWidth, plan.origin.y + crossWave * layer.moveY * sceneHeight)
                    rotate(wave * layer.turn, plan.pivot)
                    scale(1f + wave * layer.breathe,1f - wave * layer.breathe * .5f,plan.pivot)
                }) {
                    fun drawPart() { drawImage(image,plan.source,plan.sourceSize,IntOffset.Zero,plan.destination,alpha=opacity,filterQuality=FilterQuality.Medium) }
                    if(plan.mask == null) drawPart() else clipPath(plan.mask) { drawPart() }
                }
            }
        }
    })
}
