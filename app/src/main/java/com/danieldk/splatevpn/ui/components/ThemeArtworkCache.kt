package com.danieldk.splatevpn.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val themeBitmaps = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().allocationByteCount
}

/** Small gallery thumbnails decode off the UI thread; the active scene retains its source quality. */
@Composable
internal fun rememberThemeBitmap(resource: Int, preview: Boolean): State<ImageBitmap?> {
    val resources = LocalContext.current.resources
    val key = "$resource:$preview"
    val bitmap = remember(key) { mutableStateOf(themeBitmaps.get(key)) }
    LaunchedEffect(key) {
        bitmap.value = themeBitmaps.get(key) ?: withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inScaled = false; inSampleSize = 1 }
            if (preview) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeResource(resources, resource, options)
                options.inJustDecodeBounds = false
                while (maxOf(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= 512) {
                    options.inSampleSize *= 2
                }
            }
            BitmapFactory.decodeResource(resources, resource, options).asImageBitmap().also {
                themeBitmaps.put(key, it)
            }
        }
    }
    return bitmap
}
