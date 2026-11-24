package com.danieldk.splatevpn.ui.components

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.toArgb
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.SplateApplication
import com.danieldk.splatevpn.ui.theme.localizedResources
import com.danieldk.splatevpn.ui.theme.resolveThemeColors
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Camera capture stays in CaptureActivity so permission and result handling share its lifecycle. */
class QrScannerActivity : CaptureActivity() {
    private lateinit var scanner: DecoratedBarcodeView
    private lateinit var guide: ScannerGuide
    private lateinit var torchButton: LinearLayout
    private lateinit var torchIcon: ImageView
    private var torchOn = false
    private var zoomStep = 0
    private val settings get() = (application as? SplateApplication)?.settingsManager
    private val animationsEnabled get() = settings?.performanceSettings?.value?.animationsEnabled != false
    private val palette by lazy { resolveThemeColors(settings?.getAppTheme() ?: "dark", true) }
    private val accent get() = palette.accentCyan.toArgb()
    private val foregroundColor = Color.WHITE
    private val scannerResources: Resources by lazy(LazyThreadSafetyMode.NONE) {
        intent.getStringExtra(EXTRA_LANGUAGE)?.takeIf { it.isNotBlank() }
            ?.let { localizedResources(this, Locale.forLanguageTag(it)) } ?: resources
    }

    @Suppress("DEPRECATION")
    override fun initializeContent(): DecoratedBarcodeView {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutDirection = scannerResources.configuration.layoutDirection
        }
        scanner = DecoratedBarcodeView(this).apply {
            statusView?.visibility = View.GONE
            viewFinder.visibility = View.GONE
        }
        root.addView(scanner, FrameLayout.LayoutParams(-1, -1))
        guide = ScannerGuide()
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        scanner.barcodeView.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() { guide.invalidate() }
            override fun previewStarted() { guide.invalidate() }
            override fun previewStopped() = Unit
            override fun cameraError(error: Exception) = Unit
            override fun cameraClosed() = Unit
        })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(10), dp(12))
            background = panel()
        }
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(scannerText(R.string.qr_import_title), 20f, true))
            addView(label(scannerText(R.string.qr_camera_private), 12f).apply { alpha = .75f })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(iconControl(R.drawable.qr_close, scannerText(R.string.qr_close), R.id.qr_camera_close) { finish() })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), dp(16), dp(16), 0) })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(16), dp(12), dp(12))
            background = panel()
        }
        val hint = label(scannerText(R.string.qr_scanner_help), 14f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), dp(14))
        }
        bottom.addView(hint, LinearLayout.LayoutParams(-1, -2))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(iconControl(R.drawable.qr_image, scannerText(R.string.qr_image), R.id.qr_camera_gallery) {
            setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_OPEN_IMAGE, true))
            finish()
        })
        val zoom = label("1×", 16f, true).apply {
            id = R.id.qr_camera_zoom
            gravity = Gravity.CENTER
            contentDescription = scannerText(R.string.qr_zoom, 1)
        }
        decorateControl(zoom) {
            zoomStep = (zoomStep + 1) % 3
            scanner.changeCameraParameters { parameters ->
                if (parameters.isZoomSupported && !parameters.zoomRatios.isNullOrEmpty()) {
                    val ratios = parameters.zoomRatios
                    val best = ratios.indices.minByOrNull { abs(ratios[it] - (zoomStep + 1) * 100) } ?: 0
                    parameters.zoom = best
                    runOnUiThread {
                        zoom.text = scannerText(R.string.qr_camera_zoom_ratio, ratios[best] / 100f)
                        zoom.contentDescription = scannerText(R.string.qr_zoom_value, ratios[best] / 100f)
                    }
                } else runOnUiThread { zoom.isEnabled = false; zoom.alpha = .45f }
                parameters
            }
        }
        controls.addView(zoom, LinearLayout.LayoutParams(dp(64), dp(48)).apply { marginStart = dp(12); marginEnd = dp(12) })
        torchButton = LinearLayout(this).apply {
            id = R.id.qr_camera_torch
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), 0, dp(14), 0)
        }
        torchIcon = ImageView(this).apply { setImageResource(R.drawable.qr_flash); setColorFilter(foregroundColor) }
        torchButton.addView(torchIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        val torchLabel = label(scannerText(R.string.qr_camera_light), 13f, true)
        torchButton.addView(torchLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        decorateControl(torchButton) { if (torchOn) scanner.setTorchOff() else scanner.setTorchOn() }
        updateTorch(false)
        torchButton.visibility = if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) View.VISIBLE else View.GONE
        controls.addView(torchButton, LinearLayout.LayoutParams(-2, dp(48)))
        bottom.addView(controls, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(16)) })

        scanner.setTorchListener(object : DecoratedBarcodeView.TorchListener {
            override fun onTorchOn() = updateTorch(true)
            override fun onTorchOff() = updateTorch(false)
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (top.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, 0)
            }.also { top.layoutParams = it }
            (bottom.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(dp(16) + bars.left, 0, dp(16) + bars.right, dp(12) + bars.bottom)
            }.also { bottom.layoutParams = it }
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            hint.visibility = if (root.height < dp(440)) View.GONE else View.VISIBLE
            torchLabel.visibility = if (root.width < dp(360) || resources.configuration.fontScale > 1.4f) View.GONE else View.VISIBLE
            val occupied = maxOf(top.height + (top.layoutParams as FrameLayout.LayoutParams).topMargin,
                bottom.height + (bottom.layoutParams as FrameLayout.LayoutParams).bottomMargin) + dp(24)
            val side = min((root.width * .76f).toInt(), root.height - occupied * 2).coerceAtLeast(dp(96))
            if (scanner.barcodeView.framingRectSize?.width != side) {
                scanner.barcodeView.framingRectSize = Size(side, side)
                scanner.barcodeView.requestLayout()
            }
            guide.invalidate()
        }
        setContentView(root)
        return scanner
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner.cameraSettings.isAutoFocusEnabled = true
        scanner.cameraSettings.isContinuousFocusEnabled = true
    }
    override fun onResume() { super.onResume(); guide.start() }
    override fun onPause() { guide.stop(); super.onPause() }

    private fun updateTorch(enabled: Boolean) {
        torchOn = enabled
        torchButton.contentDescription = scannerText(if (enabled) R.string.qr_torch_off else R.string.qr_torch_on)
        torchButton.isSelected = enabled
        torchIcon.setColorFilter(if (enabled) accent else foregroundColor)
    }
    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(foregroundColor)
        typeface = if (scannerResources.configuration.locales[0].language in listOf("fa", "zh")) Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            else ResourcesCompat.getFont(this@QrScannerActivity, if (bold) R.font.onesans_semibold else R.font.onesans_regular)
    }
    private fun panel(focused: Boolean = false) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xEF30313A.toInt(), 0xE7151720.toInt())).apply {
        cornerRadius = dp(24).toFloat()
        setStroke(dp(if (focused) 2 else 1), if (focused) accent else 0x668B8E9F)
    }
    private fun iconControl(drawable: Int, description: String, controlId: Int, action: () -> Unit): ImageView = ImageView(this).apply {
        id = controlId
        setImageResource(drawable)
        setColorFilter(foregroundColor)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        decorateControl(this, action)
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Touch only animates; false preserves the View's accessible click handling.
    private fun decorateControl(control: View, action: () -> Unit) {
        control.background = RippleDrawable(ColorStateList.valueOf((accent and 0x00FFFFFF) or 0x33000000), panel(), null)
        control.isFocusable = true
        control.isClickable = true
        control.minimumHeight = dp(48)
        control.minimumWidth = dp(48)
        control.setOnClickListener { action() }
        control.setOnFocusChangeListener { _, focused -> control.background = panel(focused) }
        control.setOnTouchListener { _, event ->
            if (animationsEnabled) when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> control.animate().scaleX(.94f).scaleY(.94f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> control.animate().scaleX(1f).scaleY(1f).setDuration(170).start()
            }
            false
        }
    }
    private inner class ScannerGuide : View(this@QrScannerActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frame = RectF()
        private val dimPath = Path()
        private val lineColors = intArrayOf(Color.TRANSPARENT, accent, Color.TRANSPARENT)
        private var lineGradient: LinearGradient? = null
        private var layoutWidth = -1
        private var layoutHeight = -1
        private var scanPosition = .5f
        private var animator: ValueAnimator? = null
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun start() {
            if (!animationsEnabled || animator != null) return
            animator = ValueAnimator.ofFloat(.15f, .85f).apply {
                duration = 2400
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { scanPosition = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        fun stop() { animator?.cancel(); animator = null }
        override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
        private fun updateFrame(source: android.graphics.Rect) {
            if (frame.left == source.left.toFloat() && frame.top == source.top.toFloat() &&
                frame.right == source.right.toFloat() && frame.bottom == source.bottom.toFloat() &&
                layoutWidth == width && layoutHeight == height) return
            frame.set(source)
            layoutWidth = width; layoutHeight = height
            dimPath.rewind()
            dimPath.fillType = Path.FillType.EVEN_ODD
            dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            dimPath.addRoundRect(frame, dp(24).toFloat(), dp(24).toFloat(), Path.Direction.CW)
            lineGradient = LinearGradient(frame.left, 0f, frame.right, 0f, lineColors, null, Shader.TileMode.CLAMP)
        }
        override fun onDraw(canvas: Canvas) {
            updateFrame(scanner.barcodeView.framingRect ?: return)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = 0x66000000
            canvas.drawPath(dimPath, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = accent
            canvas.drawRoundRect(frame, dp(24).toFloat(), dp(24).toFloat(), paint)
            val y = frame.top + frame.height() * scanPosition
            paint.style = Paint.Style.FILL
            paint.shader = lineGradient
            canvas.drawRoundRect(frame.left + dp(16), y, frame.right - dp(16), y + dp(2), 2f, 2f, paint)
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun scannerText(id: Int, vararg arguments: Any): String = scannerResources.getString(id, *arguments)
    companion object {
        const val EXTRA_LANGUAGE = "com.danieldk.splatevpn.QR_LANGUAGE"
        const val EXTRA_OPEN_IMAGE = "com.danieldk.splatevpn.QR_OPEN_IMAGE"
    }
}
