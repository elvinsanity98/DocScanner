package com.bongzkie.bongscanner.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.bongzkie.bongscanner.data.FilterType
import com.bongzkie.bongscanner.data.Page
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Decoding, perspective correction and the document filters. Everything here is plain CPU work. */
object ImageOps {
    /** Longest side kept for stored pages (about 300 dpi for an A4 sheet). */
    const val MAX_SIDE = 2800

    /** Serialises heavy bitmap work so two full-size pages are never in memory at once. */
    val heavy = Mutex()

    val FULL_FRAME: List<Float> = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    // ------------------------------------------------------------ decoding

    /** Largest power-of-two sample size that still leaves the image at least [target] px long. */
    fun sampleFor(w: Int, h: Int, target: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= target) s *= 2
        return s
    }

    fun bounds(file: File): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, o)
        return o.outWidth to o.outHeight
    }

    /** Decodes one of the app's own JPEGs, sub-sampled to roughly [maxSide]. */
    fun decodeFile(file: File, maxSide: Int, mutable: Boolean = false): Bitmap? {
        if (!file.exists()) return null
        val (w, h) = bounds(file)
        if (w <= 0 || h <= 0) return null
        val o = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(w, h, maxSide)
            inMutable = mutable
        }
        return BitmapFactory.decodeFile(file.path, o)
    }

    /** Like [decodeFile], but never larger than [maxSide] (for on-screen editing previews). */
    fun decodeFit(file: File, maxSide: Int): Bitmap? = decodeFile(file, maxSide)?.let { scaleToFit(it, maxSide) }

    /** Decodes any image the user picked or captured: upright (EXIF applied) and at most [maxSide] long. */
    fun decodeUri(context: Context, uri: Uri, maxSide: Int = MAX_SIDE): Bitmap? {
        val cr = context.contentResolver
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, probe) }
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        val o = BitmapFactory.Options().apply { inSampleSize = sampleFor(probe.outWidth, probe.outHeight, maxSide) }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return null
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (e: Exception) {
            null
        } ?: ExifInterface.ORIENTATION_NORMAL
        // Shrink first, then turn upright: keeps peak memory low for big camera photos.
        return applyExif(scaleToFit(raw, maxSide), orientation)
    }

    private fun applyExif(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** Returns [src] itself when it already fits; otherwise a smaller copy (and recycles [src]). */
    fun scaleToFit(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val k = maxSide.toFloat() / longest
        val out = Bitmap.createScaledBitmap(
            src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true,
        )
        if (out !== src) src.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    // ------------------------------------------------------------ geometry

    fun isFullFrame(c: List<Float>?): Boolean {
        if (c == null || c.size != 8) return true
        for (i in 0 until 8) if (abs(c[i] - FULL_FRAME[i]) > 0.002f) return false
        return true
    }

    /** True when the four corners (TL, TR, BR, BL) form a convex quad that is not degenerate. */
    fun isValidQuad(c: List<Float>): Boolean {
        if (c.size != 8) return false
        var sign = 0
        for (i in 0 until 4) {
            val ax = c[i * 2]
            val ay = c[i * 2 + 1]
            val bx = c[(i + 1) % 4 * 2]
            val by = c[(i + 1) % 4 * 2 + 1]
            val cx = c[(i + 2) % 4 * 2]
            val cy = c[(i + 2) % 4 * 2 + 1]
            val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            if (abs(cross) < 1e-4f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return sign > 0
    }

    /** Exact quarter-turn rotation; always returns a new mutable bitmap. */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src.copy(Bitmap.Config.ARGB_8888, true)
        val swap = d == 90 || d == 270
        val out = Bitmap.createBitmap(
            if (swap) src.height else src.width, if (swap) src.width else src.height, Bitmap.Config.ARGB_8888,
        )
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postRotate(d.toFloat())
        m.postTranslate(out.width / 2f, out.height / 2f)
        Canvas(out).drawBitmap(src, m, Paint())
        return out
    }

    /** Straightens the quad [c] (normalised TL, TR, BR, BL) into a flat rectangle. */
    fun warp(src: Bitmap, c: List<Float>): Bitmap {
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val p = FloatArray(8) { if (it % 2 == 0) c[it] * w else c[it] * h }
        val top = hypot(p[2] - p[0], p[3] - p[1])
        val bottom = hypot(p[4] - p[6], p[5] - p[7])
        val left = hypot(p[6] - p[0], p[7] - p[1])
        val right = hypot(p[4] - p[2], p[5] - p[3])
        var outW = max(top, bottom)
        var outH = max(left, right)
        val k = min(1f, MAX_SIDE / max(outW, outH))
        outW = max(8f, outW * k)
        outH = max(8f, outH * k)
        val ow = outW.roundToInt()
        val oh = outH.roundToInt()
        val dst = floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat())
        val m = Matrix()
        if (!m.setPolyToPoly(p, 0, dst, 0, 4)) return src.copy(Bitmap.Config.ARGB_8888, true)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Crop + rotation only. Always a new mutable bitmap; [orig] is left untouched. */
    fun geometry(orig: Bitmap, corners: List<Float>?, rotation: Int): Bitmap {
        val flat = if (corners != null && !isFullFrame(corners)) warp(orig, corners) else null
        if (((rotation % 360) + 360) % 360 == 0) return flat ?: orig.copy(Bitmap.Config.ARGB_8888, true)
        val turned = rotate(flat ?: orig, rotation)
        flat?.recycle()
        return turned
    }

    /** Full pipeline for a page: crop, rotate, filter. Always a new mutable bitmap. */
    fun render(orig: Bitmap, page: Page): Bitmap {
        val out = geometry(orig, page.corners, page.rotation)
        applyFilter(out, page.filter, page.brightness, page.contrast)
        return out
    }

    // ------------------------------------------------------------ filters

    /**
     * Applies a document filter in place ([b] must be mutable).
     *
     * The document looks (Magic, Lighten, B&W) first divide the picture by an estimate of the paper
     * colour around each pixel, which removes shadows and uneven lighting, then stretch the levels.
     */
    fun applyFilter(b: Bitmap, filter: FilterType, brightness: Float = 0f, contrast: Float = 0f) {
        if (filter == FilterType.ORIGINAL && brightness == 0f && contrast == 0f) return
        val w = b.width
        val h = b.height
        val normalize = filter == FilterType.MAGIC || filter == FilterType.LIGHTEN ||
            filter == FilterType.BW || filter == FilterType.BW_SOFT
        val gray = filter == FilterType.GRAY || filter == FilterType.BW || filter == FilterType.BW_SOFT
        val saturation = if (filter == FilterType.MAGIC) 320 else 256 // fixed point, 256 = unchanged
        val lut = buildLut(filter, brightness, contrast)

        val paper = if (normalize) estimatePaper(b) else null
        val bandRows = max(8, min(h, 262144 / max(1, w)))
        val px = IntArray(w * bandRows)
        val bg = if (normalize) IntArray(w * bandRows) else null
        val bandBitmap = if (normalize) Bitmap.createBitmap(w, bandRows, Bitmap.Config.ARGB_8888) else null
        val bandCanvas = bandBitmap?.let { Canvas(it) }
        val smooth = Paint(Paint.FILTER_BITMAP_FLAG)
        val m = Matrix()

        var y = 0
        while (y < h) {
            val rows = min(bandRows, h - y)
            val count = w * rows
            b.getPixels(px, 0, w, 0, y, w, rows)
            if (paper != null && bg != null && bandBitmap != null && bandCanvas != null) {
                m.setScale(w / paper.width.toFloat(), h / paper.height.toFloat())
                m.postTranslate(0f, -y.toFloat())
                bandCanvas.drawBitmap(paper, m, smooth)
                bandBitmap.getPixels(bg, 0, w, 0, 0, w, rows)
            }
            for (i in 0 until count) {
                val c = px[i]
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var bl = c and 0xFF
                if (bg != null) {
                    val k = bg[i]
                    val kr = max((k shr 16) and 0xFF, 40)
                    val kg = max((k shr 8) and 0xFF, 40)
                    val kb = max(k and 0xFF, 40)
                    r = min(255, r * 255 / kr)
                    g = min(255, g * 255 / kg)
                    bl = min(255, bl * 255 / kb)
                }
                if (gray) {
                    val v = lut[(r * 77 + g * 150 + bl * 29) shr 8]
                    px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                } else {
                    r = lut[r]
                    g = lut[g]
                    bl = lut[bl]
                    if (saturation != 256) {
                        val l = (r * 77 + g * 150 + bl * 29) shr 8
                        r = clamp(l + (((r - l) * saturation) shr 8))
                        g = clamp(l + (((g - l) * saturation) shr 8))
                        bl = clamp(l + (((bl - l) * saturation) shr 8))
                    }
                    px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            b.setPixels(px, 0, w, 0, y, w, rows)
            y += rows
        }
        paper?.recycle()
        bandBitmap?.recycle()
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun buildLut(filter: FilterType, brightness: Float, contrast: Float): IntArray {
        val lo: Int
        val hi: Int
        when (filter) {
            FilterType.MAGIC -> { lo = 45; hi = 225 }
            FilterType.LIGHTEN -> { lo = 12; hi = 236 }
            FilterType.BW_SOFT -> { lo = 60; hi = 215 }
            FilterType.BW -> { lo = 150; hi = 200 }
            else -> { lo = 0; hi = 255 }
        }
        val gain = 1f + contrast.coerceIn(-100f, 100f) / 100f * 0.8f
        val lift = brightness.coerceIn(-100f, 100f) / 100f * 80f
        return IntArray(256) { v ->
            var x = ((v - lo) * 255f / (hi - lo)).coerceIn(0f, 255f)
            if (filter == FilterType.MAGIC) {
                // gentle S-curve: deeper ink, cleaner paper
                val t = x / 255f
                x = 255f * (0.5f * t + 0.5f * t * t * (3f - 2f * t))
            }
            x = (x - 128f) * gain + 128f + lift
            x.roundToInt().coerceIn(0, 255)
        }
    }

    /** Low-resolution map of the paper colour: text is erased with a max filter, then smoothed. */
    private fun estimatePaper(b: Bitmap): Bitmap {
        var cur = b
        while (max(cur.width, cur.height) > 400) {
            val half = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            if (cur !== b) cur.recycle()
            cur = half
        }
        val sw = cur.width
        val sh = cur.height
        val n = sw * sh
        val px = IntArray(n)
        cur.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (cur !== b) cur.recycle()

        val r = IntArray(n)
        val g = IntArray(n)
        val bl = IntArray(n)
        for (i in 0 until n) {
            r[i] = (px[i] shr 16) and 0xFF
            g[i] = (px[i] shr 8) and 0xFF
            bl[i] = px[i] and 0xFF
        }
        val radius = max(2, max(sw, sh) / 48)
        val tmp = IntArray(n)
        for (ch in arrayOf(r, g, bl)) {
            maxFilter(ch, tmp, sw, sh, radius)
            boxBlur(ch, tmp, sw, sh, radius)
        }
        for (i in 0 until n) px[i] = (0xFF shl 24) or (r[i] shl 16) or (g[i] shl 8) or bl[i]
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, sw, 0, 0, sw, sh)
        return out
    }

    private fun maxFilter(a: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var best = 0
                val from = max(0, x - r)
                val to = min(w - 1, x + r)
                for (k in from..to) if (a[row + k] > best) best = a[row + k]
                tmp[row + x] = best
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var best = 0
                val from = max(0, y - r)
                val to = min(h - 1, y + r)
                for (k in from..to) if (tmp[k * w + x] > best) best = tmp[k * w + x]
                a[y * w + x] = best
            }
        }
    }

    private fun boxBlur(a: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var sum = 0
                val from = max(0, x - r)
                val to = min(w - 1, x + r)
                for (k in from..to) sum += a[row + k]
                tmp[row + x] = sum / (to - from + 1)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var sum = 0
                val from = max(0, y - r)
                val to = min(h - 1, y + r)
                for (k in from..to) sum += tmp[k * w + x]
                a[y * w + x] = sum / (to - from + 1)
            }
        }
    }
}
