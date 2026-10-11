package com.bongzkie.bongscanner.imaging

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** In-memory cache of decoded thumbnails and previews. */
object BitmapCache {
    private val cache = object : LruCache<String, Bitmap>(
        min(Runtime.getRuntime().maxMemory() / 6, 64L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    fun clear() = cache.evictAll()
}

/**
 * Loads [file] off the main thread, sub-sampled to about [maxSide] pixels. [stamp] must change whenever
 * the file content changes so stale images are never shown.
 */
@Composable
fun rememberBitmap(file: File, stamp: Long, maxSide: Int): ImageBitmap? {
    val key = "${file.path}|$stamp|$maxSide"
    var bitmap by remember(key) { mutableStateOf(BitmapCache.get(key)) }
    LaunchedEffect(key) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    ImageOps.decodeFile(file, maxSide)?.also { BitmapCache.put(key, it) }
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
    val current = bitmap
    return remember(current) { current?.asImageBitmap() }
}
