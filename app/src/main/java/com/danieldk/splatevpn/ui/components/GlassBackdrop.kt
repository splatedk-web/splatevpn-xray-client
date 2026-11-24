package com.danieldk.splatevpn.ui.components

import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView

internal val LocalGlassTransparency = compositionLocalOf { 0f }
internal val LocalGlassBlurEnabled = compositionLocalOf { true }
internal val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

/** Only the decorative backdrop is recorded: foreground controls/text are never blurred. */
internal class GlassBackdrop(val blurred: GraphicsLayer, val ownerView: android.view.View, val cropToSample: Boolean = false) {
    var origin by mutableStateOf(Offset.Zero)
    var revision by mutableIntStateOf(0)
        private set
    var ready by mutableStateOf(false)
    var sampleBounds by mutableStateOf<Rect?>(null)
    private var frameNumber = 0

    fun markRecorded() {
        ready = true
        // Reading revision here would subscribe the producer to its own writes, redrawing forever.
        revision = ++frameNumber
    }
}

/** Keep the complete blur kernel around the visible dock, at native resolution. */
internal fun dockBlurRegion(sample: Rect?, origin: Offset, size: Size, radius: Float): Rect {
    val bounds = Rect(Offset.Zero, size)
    val requested = sample?.translate(-origin) ?: return bounds
    val padded = requested.inflate(ceil(radius * 3f)).intersect(bounds)
    if (padded.isEmpty) return bounds
    return Rect(floor(padded.left), floor(padded.top), ceil(padded.right), ceil(padded.bottom))
}

/** Records the screen below the dock, excluding the dock itself to avoid feedback. */
@Composable
internal fun rememberDockBackdrop(): Pair<GlassBackdrop?, Modifier> {
    val transparency = LocalGlassTransparency.current
    val blurEnabled = LocalGlassBlurEnabled.current
    if (!blurEnabled || Build.VERSION.SDK_INT < 31 || transparency <= .001f) return null to Modifier
    val view = LocalView.current
    val artwork = LocalGlassBackdrop.current?.takeIf { it.ownerView.rootView === view.rootView }
    val source = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val backdrop = remember(blurred, view) { GlassBackdrop(blurred, view, cropToSample = true) }
    val location = remember { IntArray(2) }
    var sourceOrigin by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { (14 + transparency * 14).dp.toPx() }
    blurred.renderEffect = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    val capture = Modifier.onGloballyPositioned { coordinates ->
        view.getLocationOnScreen(location)
        sourceOrigin = coordinates.positionInRoot() + Offset(location[0].toFloat(), location[1].toFloat())
    }.drawWithContent {
        // Artwork motion also refreshes the composite sampled by the dock.
        @Suppress("UNUSED_VARIABLE") val artworkRevision = artwork?.revision
        val crop = dockBlurRegion(backdrop.sampleBounds, sourceOrigin, size, radius)
        source.record { this@drawWithContent.drawContent() }
        blurred.record(size = IntSize(crop.width.toInt(), crop.height.toInt())) {
            translate(-crop.left, -crop.top) {
            if (artwork?.ready == true) {
                val delta = artwork.origin - sourceOrigin
                translate(delta.x, delta.y) { drawLayer(artwork.blurred) }
            }
            drawLayer(source)
            }
        }
        drawLayer(source)
        backdrop.origin = sourceOrigin + crop.topLeft
        backdrop.markRecorded()
    }
    return backdrop to capture
}

internal fun Modifier.sampleGlassBackdrop(): Modifier = composed {
    if (!LocalGlassBlurEnabled.current) return@composed this
    val backdrop = LocalGlassBackdrop.current
    if (backdrop == null || Build.VERSION.SDK_INT < 31) return@composed this
    val view = LocalView.current
    // Dialogs inherit composition locals, but their opaque window hides this source.
    // Replaying another window's layer paints unrelated artwork inside dialog controls.
    if (backdrop.ownerView.rootView !== view.rootView) return@composed this
    val location = remember { IntArray(2) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    this.onGloballyPositioned { coordinates ->
        view.getLocationOnScreen(location)
        origin = coordinates.positionInRoot() + Offset(location[0].toFloat(), location[1].toFloat())
        if (backdrop.cropToSample) backdrop.sampleBounds = Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
    }.drawWithContent {
        @Suppress("UNUSED_VARIABLE") val revision = backdrop.revision
        if (backdrop.ready) {
            val delta = backdrop.origin - origin
            translate(delta.x, delta.y) { drawLayer(backdrop.blurred) }
        }
        drawContent()
    }
}
