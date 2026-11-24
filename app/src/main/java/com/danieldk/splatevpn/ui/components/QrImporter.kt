package com.danieldk.splatevpn.ui.components

import com.danieldk.splatevpn.ui.components.SplateTextButton as TextButton

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.danieldk.splatevpn.R
import com.danieldk.splatevpn.ui.theme.LocalAppResources
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import com.danieldk.splatevpn.ui.components.SplateAlertDialog as AlertDialog

/** Shared offline import flow. Photo access uses the system picker, without storage permission. */
@Composable
fun rememberQrImporter(onResult: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val resources = LocalAppResources.current
    val currentResult by rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()
    var choose by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableIntStateOf(0) }
    var launching by rememberSaveable { mutableStateOf(false) }
    var openImageFromScanner by rememberSaveable { mutableStateOf(false) }
    var imageJob by remember { mutableStateOf<Job?>(null) }
    var candidates by remember { mutableStateOf(emptyList<String>()) }
    fun dispatchResult(payload: String) {
        if (payload.trim().startsWith("splate-transfer://", ignoreCase = true)) {
            val intent = android.content.Intent(context, com.danieldk.splatevpn.MainActivity::class.java)
                .setAction(android.content.Intent.ACTION_VIEW).setData(Uri.parse(payload))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (context !is android.app.Activity) intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else currentResult(payload)
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        launching = false
        if (result.contents != null) {
            val payload = validQrPayload(result.contents)
            if (payload != null) dispatchResult(payload) else error = R.string.qr_payload_error
        } else if (result.originalIntent?.getBooleanExtra(QrScannerActivity.EXTRA_OPEN_IMAGE, false) == true) {
            openImageFromScanner = true
        } else if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
            error = R.string.qr_camera_permission
        }
    }
    fun launchScanner() {
        try {
            launching = true
            scanner.launch(ScanOptions()
                .setCaptureActivity(QrScannerActivity::class.java)
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setBeepEnabled(false).setOrientationLocked(false)
                .addExtra(Intents.Scan.SCAN_TYPE, Intents.Scan.MIXED_SCAN)
                .addExtra(Intents.Scan.CHARACTER_SET, "UTF-8")
                .addExtra("TRY_HARDER", true)
                .setPrompt(resources.getString(R.string.qr_camera_prompt))
                .addExtra(QrScannerActivity.EXTRA_LANGUAGE, resources.configuration.locales[0].toLanguageTag()))
        } catch (_: android.content.ActivityNotFoundException) {
            launching = false
            error = R.string.qr_camera_unavailable
        } catch (_: SecurityException) {
            launching = false
            error = R.string.qr_camera_permission
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        launching = false
        if (granted) launchScanner() else error = R.string.qr_camera_permission
    }
    val image = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        launching = false
        if (uri != null && imageJob == null) {
            imageJob = scope.launch {
                try {
                    val decoded = withContext(Dispatchers.IO) { decodeQrImages(context, uri) }
                    when (decoded.size) {
                        0 -> error = R.string.qr_image_error
                        1 -> dispatchResult(decoded.single())
                        else -> candidates = decoded
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = R.string.qr_image_open_error
                } finally {
                    imageJob = null
                }
            }
        }
    }
    fun launchImage() {
        choose = false
        try {
            launching = true
            image.launch("image/*")
        } catch (_: android.content.ActivityNotFoundException) {
            launching = false
            error = R.string.qr_image_open_error
        }
    }
    LaunchedEffect(openImageFromScanner) {
        if (openImageFromScanner) {
            openImageFromScanner = false
            launchImage()
        }
    }
    if (choose) AlertDialog(
        onDismissRequest = { choose = false },
        title = { Text(resources.getString(R.string.qr_import_title)) },
        text = { Text(resources.getString(R.string.qr_import_help)) },
        confirmButton = { TextButton(onClick = {
            choose = false
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                error = R.string.qr_camera_unavailable
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchScanner()
            } else {
                launching = true
                permission.launch(Manifest.permission.CAMERA)
            }
        }) { Text(resources.getString(R.string.qr_camera)) } },
        dismissButton = { TextButton(onClick = { launchImage() }) { Text(resources.getString(R.string.qr_image)) } }
    )
    if (imageJob != null) AlertDialog(
        onDismissRequest = { imageJob?.cancel() },
        icon = { CircularProgressIndicator() },
        text = { Text(resources.getString(R.string.qr_reading)) },
        confirmButton = { TextButton(onClick = { imageJob?.cancel() }) { Text(resources.getString(android.R.string.cancel)) } }
    )
    if (candidates.isNotEmpty()) AlertDialog(
        onDismissRequest = { candidates = emptyList() },
        title = { Text(resources.getString(R.string.qr_choose_payload)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            candidates.forEachIndexed { index, value ->
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    candidates = emptyList()
                    dispatchResult(value)
                }) {
                    Text(resources.getString(R.string.qr_payload_number, index + 1))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { candidates = emptyList() }) { Text(resources.getString(android.R.string.cancel)) } }
    )
    if (error != 0) AlertDialog(
        onDismissRequest = { error = 0 },
        text = { Text(resources.getString(error)) },
        confirmButton = { TextButton(onClick = { error = 0; launchImage() }) { Text(resources.getString(R.string.qr_image)) } },
        dismissButton = { TextButton(onClick = { error = 0 }) { Text(resources.getString(android.R.string.cancel)) } }
    )
    return { if (!launching && imageJob == null) { error = 0; choose = true } }
}

internal suspend fun decodeQrImages(context: Context, uri: Uri): List<String> {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var previousSample = 0
    // Fast pass for screenshots, then preserve detail in a dense code on a large photo.
    for (pixelBudget in listOf(2_000_000L, 8_000_000L)) {
        coroutineContext.ensureActive()
        var sample = 1
        while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > pixelBudget) sample *= 2
        if (sample == previousSample) continue
        previousSample = sample
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            })
        } ?: error("Cannot open image")
        val decoded = try {
            // One row of ARGB, not another bitmap-sized IntArray.
            val luminance = ByteArray(bitmap.width * bitmap.height)
            val row = IntArray(bitmap.width)
            for (y in 0 until bitmap.height) {
                if (y % 64 == 0) coroutineContext.ensureActive()
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                for (x in row.indices) {
                    val color = row[x]
                    val alpha = color ushr 24
                    val gray = ((color shr 16 and 255) + 2 * (color shr 8 and 255) + (color and 255)) / 4
                    // Composite transparent QR backgrounds onto white.
                    luminance[y * bitmap.width + x] = ((gray * alpha + 255 * (255 - alpha)) / 255).toByte()
                }
            }
            decodeQrPayloads(QrLuminanceSource(bitmap.width, bitmap.height, luminance))
        } finally { bitmap.recycle() }
        if (decoded.isNotEmpty()) return decoded
    }
    return emptyList()
}

