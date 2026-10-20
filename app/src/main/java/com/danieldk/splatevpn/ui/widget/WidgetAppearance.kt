package com.danieldk.splatevpn.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale
import android.graphics.*
import android.util.LruCache
import android.widget.RemoteViews
import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.*
import com.danieldk.splatevpn.vpn.VpnState
import com.danieldk.splatevpn.ui.components.hasThemeObject
import com.danieldk.splatevpn.ui.components.themeLayerArtFor
import kotlin.math.roundToInt

/** RemoteViews share the app palette; cached native drawing keeps periodic traffic updates cheap. */
internal class WidgetAppearance(context: Context) {
    private val resources = context.resources
    private val themeKey = context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE).getString("app_theme", "dark") ?: "dark"
    private val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val palette = resolveThemeColors(themeKey, systemDark)
    private val key = "$themeKey:$systemDark"
    private val surface = palette.surface.toArgb()
    private val primary = palette.textPrimary.toArgb()
    private val secondary = palette.textSecondary.toArgb()
    private val accent = palette.accentCyan.toArgb()
    private fun mix(base: Int, color: Int, amount: Float) = ColorUtils.blendARGB(base, color, amount)
    fun statusColor(state: VpnState): Int = when (state) {
        VpnState.CONNECTED -> palette.accentConnected.toArgb()
        VpnState.DISCONNECTED -> secondary
        VpnState.FAILED -> palette.accentCrimson.toArgb()
        else -> palette.accentAmber.toArgb()
    }

    fun applyFull(views: RemoteViews, state: VpnState, width: Int, height: Int) {
        views.setImageViewBitmap(R.id.widget_backdrop, backdrop(width, height))
        views.setTextColor(R.id.widget_brand_title, secondary)
        views.setTextColor(R.id.widget_server, primary)
        views.setTextColor(R.id.widget_status, statusColor(state))
        views.setTextColor(R.id.widget_status_dot, statusColor(state))
        views.setTextColor(R.id.widget_ping, secondary)
        views.setTextColor(R.id.widget_stats, secondary)
        views.setTextColor(R.id.widget_download_label, secondary)
        views.setTextColor(R.id.widget_upload_label, secondary)
        views.setTextColor(R.id.widget_download, palette.accentConnected.toArgb())
        views.setTextColor(R.id.widget_upload, palette.accentSecondary.toArgb())
        views.setInt(R.id.widget_metrics_divider, "setBackgroundColor", palette.borderSubtle.toArgb())
        views.setImageViewBitmap(R.id.widget_action_btn, action(state))
        views.setImageViewBitmap(R.id.widget_servers_btn, servers())
        views.setImageViewBitmap(R.id.widget_icon, shield(state))
    }

    fun applyFullState(views: RemoteViews, state: VpnState) {
        views.setTextColor(R.id.widget_status, statusColor(state))
        views.setTextColor(R.id.widget_status_dot, statusColor(state))
        views.setImageViewBitmap(R.id.widget_action_btn, action(state))
        views.setImageViewBitmap(R.id.widget_icon, shield(state))
    }

    fun applyQuick(views: RemoteViews, state: VpnState, width: Int, height: Int) {
        views.setImageViewBitmap(R.id.quick_widget_backdrop, backdrop(width, height))
        views.setImageViewBitmap(R.id.quick_widget_disc, disc(state))
        views.setInt(R.id.quick_widget_icon, "setColorFilter", statusColor(state))
        views.setTextColor(R.id.quick_widget_state_dot, statusColor(state))
        views.setTextColor(R.id.quick_widget_label, statusColor(state))
        views.setTextColor(R.id.quick_widget_detail, secondary)
    }

    fun applyQuickState(views: RemoteViews, state: VpnState) {
        views.setImageViewBitmap(R.id.quick_widget_disc, disc(state))
        views.setInt(R.id.quick_widget_icon, "setColorFilter", statusColor(state))
        views.setTextColor(R.id.quick_widget_state_dot, statusColor(state))
        views.setTextColor(R.id.quick_widget_label, statusColor(state))
    }

    private fun image(suffix: String, w: Int, h: Int, draw: (Canvas, Paint) -> Unit): Bitmap = synchronized(cache) {
        val cacheKey = "$key:$suffix:$w:$h"
        cache.get(cacheKey)?.let { return@synchronized it }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            draw(Canvas(it), Paint(Paint.ANTI_ALIAS_FLAG))
            cache.put(cacheKey, it)
        }
    }

    private fun backdrop(widthDp: Int, heightDp: Int): Bitmap {
        val scale = minOf(2f, 720f / widthDp.coerceAtLeast(1), 400f / heightDp.coerceAtLeast(1))
        val w = (widthDp * scale).toInt().coerceAtLeast(48)
        val h = (heightDp * scale).toInt().coerceAtLeast(48)
        return image("backdrop", w, h) { canvas, paint ->
            val width = w.toFloat(); val height = h.toFloat()
            val radius = minOf(48f, height * .3f)
            val bounds = RectF(1f, 1f, width - 1f, height - 1f)
            canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })
            paint.shader = LinearGradient(0f, 0f, width, height, palette.surfaceElevated.toArgb(), palette.backgroundDeep.toArgb(), Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = RadialGradient(width * .1f, 0f, width.coerceAtLeast(height) * .65f,
                intArrayOf(ColorUtils.setAlphaComponent(accent, if (palette.isDark) 38 else 18), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawRect(bounds, paint)
            paint.shader = null
            paint.color = ColorUtils.setAlphaComponent(palette.accentSecondary.toArgb(), if (palette.isDark) 30 else 18)
            when {
                hasThemeObject(palette.backdrop) -> drawThemeArtwork(canvas, paint, width, height)
                palette.backdrop == BackdropStyle.STARS -> repeat(20) { i ->
                    paint.color = ColorUtils.setAlphaComponent(primary, 25 + i % 3 * 12)
                    canvas.drawCircle((i * 79 % w).toFloat(), (i * 47 % h).toFloat(), 1f + i % 2, paint)
                }
                else -> {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = height * .06f
                    repeat(3) { i ->
                        val path = Path().apply {
                            moveTo(width * .5f, height * (1.2f + i * .2f))
                            cubicTo(width * .65f, -height * .5f, width * .8f, height * 1.5f, width * 1.1f, height * (-.4f + i * .2f))
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
            character()?.let { character ->
                val artworkScale = minOf(width * .48f / character.width, height * .88f / character.height)
                val artWidth = character.width * artworkScale
                val artHeight = character.height * artworkScale
                val left = width * .94f - artWidth
                val top = (height - artHeight) / 2
                paint.style = Paint.Style.FILL; paint.shader = null
                paint.alpha = if (widthDp >= 220) 95 else 45
                paint.isFilterBitmap = true
                canvas.drawBitmap(character, null, RectF(left, top, left + artWidth, top + artHeight), paint)
                paint.alpha = 255
            }
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            paint.color = palette.borderMedium.toArgb()
            canvas.drawRoundRect(bounds, radius, radius, paint)
        }
    }

    /** Freeze the same atlas, crops and masks used by the in-app theme renderer. */
    private fun drawThemeArtwork(canvas: Canvas, paint: Paint, width: Float, height: Float) {
        val art = themeLayerArtFor(palette.backdrop)
        val bitmap = artwork(art.resource) ?: return
        val sceneWidth = width * .76f
        val sceneHeight = minOf(height * .98f, sceneWidth * art.aspect)
        val sceneLeft = width - sceneWidth
        val sceneTop = height - sceneHeight
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.isFilterBitmap = true
        paint.alpha = if (width > height * 2f) 155 else 110
        for (layer in art.layers) {
            val part = art.parts[layer.part]
            val source = Rect((part.source.left * bitmap.width).roundToInt(), (part.source.top * bitmap.height).roundToInt(),
                (part.source.right * bitmap.width).roundToInt(), (part.source.bottom * bitmap.height).roundToInt())
            val fit = minOf(sceneWidth * layer.width / source.width(), sceneHeight * layer.height / source.height())
            val artWidth = source.width() * fit
            val artHeight = source.height() * fit
            val left = sceneLeft + layer.x * sceneWidth - artWidth * layer.anchorX
            val top = sceneTop + layer.y * sceneHeight - artHeight * layer.anchorY
            val saved = canvas.save()
            if (part.mask.isNotEmpty()) {
                canvas.clipPath(Path().apply {
                    part.mask.forEachIndexed { index, point ->
                        val x = left + (point.x - part.source.left) / part.source.width * artWidth
                        val y = top + (point.y - part.source.top) / part.source.height * artHeight
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                })
            }
            canvas.drawBitmap(bitmap, source, RectF(left, top, left + artWidth, top + artHeight), paint)
            canvas.restoreToCount(saved)
        }
        paint.alpha = 255
        // Keep labels legible over the right-aligned artwork.
        paint.shader = LinearGradient(0f, 0f, width, 0f,
            intArrayOf(palette.surfaceElevated.toArgb(), ColorUtils.setAlphaComponent(surface, 180), Color.TRANSPARENT),
            floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null
    }

    private fun action(state: VpnState) = image("action:$state", 96, 96) { canvas, paint ->
        val startable = state == VpnState.DISCONNECTED || state == VpnState.FAILED
        val color = if (startable) accent else statusColor(state)
        paint.color = if (startable) color else mix(surface, color, .16f)
        canvas.drawRoundRect(RectF(1f, 1f, 95f, 95f), 32f, 32f, paint)
        if (!startable) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = mix(surface, color, .45f)
            canvas.drawRoundRect(RectF(1f, 1f, 95f, 95f), 32f, 32f, paint)
        }
        paint.color = if (startable) contentColorOn(palette.accentCyan).toArgb() else color
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(RectF(28f, 28f, 68f, 68f), -48f, 276f, false, paint)
        canvas.drawLine(48f, 25f, 48f, 45f, paint)
    }

    private fun servers() = image("servers", 80, 88) { canvas, paint ->
        paint.color = mix(surface, accent, .06f)
        canvas.drawRoundRect(RectF(1f, 1f, 79f, 87f), 25f, 25f, paint)
        paint.color = secondary
        repeat(2) { row -> repeat(2) { col ->
            canvas.drawRoundRect(RectF(25f + col * 18, 29f + row * 18, 37f + col * 18, 41f + row * 18), 3f, 3f, paint)
        } }
    }

    private fun shield(state: VpnState) = image("shield:$state", 60, 60) { canvas, paint ->
        canvas.scale(2f, 2f); canvas.translate(3f, 3f)
        val color = statusColor(state)
        val shape = Path().apply { moveTo(12f, 2f); lineTo(3f, 6f); lineTo(3f, 12f); cubicTo(3f, 18f, 7f, 22f, 12f, 24f); cubicTo(17f, 22f, 21f, 18f, 21f, 12f); lineTo(21f, 6f); close() }
        paint.color = mix(surface, color, .13f); canvas.drawPath(shape, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.4f; paint.color = color
        canvas.drawPath(shape, paint)
        if (state == VpnState.CONNECTED) {
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(Path().apply { moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 10f) }, paint)
        }
    }

    private fun disc(state: VpnState) = image("disc:$state", 88, 88) { canvas, paint ->
        val color = statusColor(state)
        paint.color = mix(surface, color, if (state == VpnState.CONNECTED) .18f else .07f)
        canvas.drawCircle(44f, 44f, 42f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = mix(surface, color, .55f)
        canvas.drawCircle(44f, 44f, 41f, paint)
    }

    private fun character(): Bitmap? {
        val resource = when (palette.backdrop) {
            BackdropStyle.PONY -> R.drawable.theme_pony_characters
            BackdropStyle.BLOOM -> R.drawable.theme_bloom_character
            else -> return null
        }
        return artwork(resource)
    }

    private fun artwork(resource: Int): Bitmap? = synchronized(artworks) {
            artworks.get(resource) ?: BitmapFactory.decodeResource(resources, resource,
                BitmapFactory.Options().apply { inSampleSize = 2; inScaled = false })?.also { artworks.put(resource, it) }
    }

    companion object {
        private val cache = LruCache<String, Bitmap>(24)
        private val artworks = LruCache<Int, Bitmap>(3)
    }
}

internal fun widgetCanStop(context: Context): Boolean = runCatching {
    val entry = dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, SplateAppWidgetProvider.WidgetEntryPoint::class.java)
    entry.vpnStatusManager().state.value.let { it != VpnState.DISCONNECTED && it != VpnState.FAILED }
}.getOrDefault(com.danieldk.splatevpn.vpn.SplateVpnService.isServiceActive)

/** Widget options contain portrait/landscape extrema, not the current size directly. */
internal fun widgetDimensions(context: Context, options: Bundle, fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
    val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val width = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        .takeIf { it > 0 } ?: fallbackWidth
    val height = options.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        .takeIf { it > 0 } ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: fallbackHeight
    return width to height
}

internal fun widgetResources(context: Context): Resources {
    val language = context.getSharedPreferences("splate_prefs", Context.MODE_PRIVATE)
        .getString("app_language", "system")
    if (language != "ru" && language != "en") return context.resources
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(language))
    return context.createConfigurationContext(configuration).resources
}
