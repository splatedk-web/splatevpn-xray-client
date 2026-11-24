package com.danieldk.splatevpn.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.localizedResources
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.ScanOptions
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

/** Opens the actual camera Activity: decoder-only tests cannot detect Activity resource crashes. */
class QrScannerActivityTest {
    @Test fun explicitLanguageCameraOpensRecreatesAndReleasesPreview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val intent = ScanOptions().setCaptureActivity(QrScannerActivity::class.java)
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false).setOrientationLocked(false)
            .addExtra(QrScannerActivity.EXTRA_LANGUAGE, "ru")
            .createScanIntent(context)
        val expectedClose = localizedResources(context, Locale.forLanguageTag("ru")).getString(R.string.qr_close)
        var lastPreview: BarcodeView? = null
        val scenario = ActivityScenario.launch<QrScannerActivity>(intent)
        try {
            repeat(2) { iteration ->
                waitForPreview(scenario)
                scenario.onActivity { activity ->
                    assertFalse(activity.isFinishing)
                    val close = activity.findViewById<View>(R.id.qr_camera_close)
                    assertEquals(expectedClose, close.contentDescription)
                    assertTrue(close.isClickable)
                    assertTrue(close.width >= (48 * activity.resources.displayMetrics.density).toInt())
                    assertTrue(activity.findViewById<View>(R.id.qr_camera_gallery).isShown)
                    assertTrue(activity.findViewById<View>(R.id.qr_camera_zoom).isShown)
                    val scanner = descendants(activity.window.decorView).filterIsInstance<DecoratedBarcodeView>().single()
                    assertTrue(scanner.cameraSettings.isAutoFocusEnabled)
                    assertTrue(scanner.barcodeView.isPreviewActive)
                    lastPreview = scanner.barcodeView
                }
                if (iteration == 0) scenario.recreate()
            }
        } finally { scenario.close() }
        instrumentation.waitForIdleSync()
        assertTrue("Closing the scanner must release its camera", requireNotNull(lastPreview).isCameraClosed)
    }

    @Test fun galleryControlReturnsToSharedOfflineImageImporter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val intent = ScanOptions().setCaptureActivity(QrScannerActivity::class.java)
            .setBeepEnabled(false).createScanIntent(context)
        val scenario = ActivityScenario.launchActivityForResult<QrScannerActivity>(intent)
        try {
            waitForPreview(scenario)
            scenario.onActivity { it.findViewById<View>(R.id.qr_camera_gallery).performClick() }
            val result = scenario.result
            assertEquals(android.app.Activity.RESULT_CANCELED, result.resultCode)
            assertTrue(result.resultData?.getBooleanExtra(QrScannerActivity.EXTRA_OPEN_IMAGE, false) == true)
        } finally { scenario.close() }
    }

    private fun waitForPreview(scenario: ActivityScenario<QrScannerActivity>) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        var started = false
        while (!started && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { activity ->
                started = descendants(activity.window.decorView).filterIsInstance<DecoratedBarcodeView>()
                    .firstOrNull()?.barcodeView?.isPreviewActive == true
            }
            if (!started) SystemClock.sleep(50)
        }
        assertTrue("The real camera preview did not start", started)
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
