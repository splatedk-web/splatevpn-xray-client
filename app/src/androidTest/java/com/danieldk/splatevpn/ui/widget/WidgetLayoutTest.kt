package com.danieldk.splatevpn.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.vpn.VpnState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetLayoutTest {
    @Test
    fun currentThemeAtlasesRenderInWidgets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        instrumentation.runOnMainSync {
            for (theme in listOf("sunset", "ocean", "sakura", "cyberpunk", "aurora", "oneplus")) {
                val prefsName = "widget_artwork_test_$theme"
                val prefs = app.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                prefs.edit().putString("app_theme", theme).putString("app_language", "en").commit()
                try {
                    val context = object : ContextWrapper(app) {
                        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                            if (name == "splate_prefs") prefs else super.getSharedPreferences(name, mode)
                    }
                    val options = Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 400)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
                    }
                    val root = SplateAppWidgetProvider.createViews(context, options).apply(context, FrameLayout(context))
                    root.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, 800, 360)
                    val bitmap = Bitmap.createBitmap(800, 360, Bitmap.Config.ARGB_8888)
                    root.draw(Canvas(bitmap))
                    File(app.getExternalFilesDir(null), "widget-artwork-$theme.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                } finally { app.deleteSharedPreferences(prefsName) }
            }
        }
    }

    @Test
    fun compactWidgetsFitAndRemoteViewsSupportEveryState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.5f)) {
                val context = app.createConfigurationContext(Configuration(app.resources.configuration).apply {
                    this.fontScale = fontScale
                    orientation = Configuration.ORIENTATION_PORTRAIT
                })
                for ((width, height) in listOf(180 to 64, 300 to 128, 400 to 220, 40 to 40, 64 to 80, 140 to 140)) {
                    val quick = width < 180
                    val options = Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                    }
                    for (state in VpnState.entries) {
                        val views = if (quick) SplateQuickWidgetProvider.createViews(context, options)
                            else SplateAppWidgetProvider.createViews(context, options)
                        val appearance = WidgetAppearance(context)
                        if (quick) appearance.applyQuick(views, state, width, height)
                        else appearance.applyFull(views, state, width, height)
                        val root = views.apply(context, FrameLayout(context))
                        val density = context.resources.displayMetrics.density
                        val pxWidth = (width * density).toInt()
                        val pxHeight = (height * density).toInt()
                        root.measure(View.MeasureSpec.makeMeasureSpec(pxWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(pxHeight, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, pxWidth, pxHeight)
                        val control = root.findViewById<View>(if (quick) R.id.quick_widget_ring else R.id.widget_action_btn)
                        val position = IntArray(2)
                        control.getLocationOnScreen(position)
                        assertTrue("$width × $height, font $fontScale: control clipped at top", position[1] >= 0)
                        assertTrue("$width × $height, font $fontScale: control clipped at bottom", position[1] + control.height <= pxHeight)
                        assertTrue("$width × $height, font $fontScale: control clipped at right", position[0] + control.width <= pxWidth)
                        val bitmap = Bitmap.createBitmap(pxWidth, pxHeight, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(bitmap))
                        if (fontScale == 1f && state == VpnState.DISCONNECTED) {
                            File(app.getExternalFilesDir(null), "widget-$width-$height.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        }
                        bitmap.recycle()
                    }
                }
            }
            // Refresh the launcher through the actual providers as well.
            val manager = AppWidgetManager.getInstance(app)
            manager.getAppWidgetIds(ComponentName(app, SplateAppWidgetProvider::class.java)).forEach {
                SplateAppWidgetProvider.updateWidget(app, manager, it)
            }
            manager.getAppWidgetIds(ComponentName(app, SplateQuickWidgetProvider::class.java)).forEach {
                SplateQuickWidgetProvider.updateWidget(app, manager, it)
            }
        }
    }
}
