package com.bongzkie.bongscanner.imaging

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the sheet of paper in a photo.
 *
 * Works on a small copy of the picture: edges are detected, the area the outside can "leak" into is
 * flood-filled from the picture border, and the biggest region left over (enclosed by edges) is taken
 * as the page. Its four extreme points become the crop corners.
 */
object EdgeDetector {
    private const val SIDE = 240

    /** @return normalised corners TL, TR, BR, BL (x,y pairs), or null when no clear page is found. */
    fun detect(src: Bitmap): List<Float>? = try {
        detectInternal(src)
    } catch (e: Throwable) {
        null
    }

    private fun detectInternal(src: Bitmap): List<Float>? {
        var cur = src
        while (max(cur.width, cur.height) >= SIDE * 2) {
            val half = Bitmap.createScaledBitmap(cur, max(1, cur.width / 2), max(1, cur.height / 2), true)
            if (cur !== src) cur.recycle()
            cur = half
        }
        val k = SIDE.toFloat() / max(cur.width, cur.height)
        val small = if (k < 1f) {
            Bitmap.createScaledBitmap(
                cur, max(16, (cur.width * k).roundToInt()), max(16, (cur.height * k).roundToInt()), true,
            )
        } else {
            cur
        }
        if (cur !== src && cur !== small) cur.recycle()

        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        return detectPixels(px, w, h)
    }

    /** The detector itself, on plain ARGB pixels of an already small picture (about [SIDE] px long). */
    internal fun detectPixels(px: IntArray, w: Int, h: Int): List<Float>? {
        val n = w * h
        if (w < 16 || h < 16) return null

        val gray = IntArray(n)
        for (i in 0 until n) {
            val c = px[i]
            gray[i] = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        val tmp = IntArray(n)
        blur3(gray, tmp, w, h)
        blur3(gray, tmp, w, h)

        val mag = IntArray(n)
        val hist = IntArray(256)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = -gray[i - w - 1] - 2 * gray[i - 1] - gray[i + w - 1] +
                    gray[i - w + 1] + 2 * gray[i + 1] + gray[i + w + 1]
                val gy = -gray[i - w - 1] - 2 * gray[i - w] - gray[i - w + 1] +
                    gray[i + w - 1] + 2 * gray[i + w] + gray[i + w + 1]
                val v = min(255, (abs(gx) + abs(gy)) / 4)
                mag[i] = v
                hist[v]++
            }
        }
        val otsu = otsu(hist, (w - 2) * (h - 2))
        val thresholds = intArrayOf(
            max(6, (otsu * 0.75f).toInt()), max(6, (otsu * 0.5f).toInt()), max(6, (otsu * 0.3f).toInt()), 6,
        )
        var last = -1
        for (t in thresholds) {
            if (t == last) continue
            last = t
            val quad = tryThreshold(mag, w, h, t)
            if (quad != null) return quad
        }
        return null
    }

    private fun tryThreshold(mag: IntArray, w: Int, h: Int, threshold: Int): List<Float>? {
        val n = w * h
        // 1 = edge, 0 = free
        val edge = IntArray(n)
        for (i in 0 until n) if (mag[i] > threshold) edge[i] = 1
        val tmp = IntArray(n)
        morph(edge, tmp, w, h, 1, dilate = true)

        // Flood the outside, starting from every free pixel on the picture border.
        val outside = BooleanArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (!outside[i] && edge[i] == 0) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (i >= w) push(i - w)
            if (i < n - w) push(i + w)
        }

        // What the flood could not reach is enclosed by edges. Opening removes thin stray lines.
        val inside = IntArray(n)
        for (i in 0 until n) if (!outside[i]) inside[i] = 1
        val open = 4
        morph(inside, tmp, w, h, open, dilate = false)
        morph(inside, tmp, w, h, open, dilate = true)

        // Largest connected region and its extreme points.
        val seen = BooleanArray(n)
        var bestCount = 0
        var best: IntArray? = null
        for (start in 0 until n) {
            if (inside[start] == 0 || seen[start]) continue
            head = 0
            tail = 0
            queue[tail++] = start
            seen[start] = true
            var count = 0
            var minSum = Int.MAX_VALUE
            var maxSum = Int.MIN_VALUE
            var minDiff = Int.MAX_VALUE
            var maxDiff = Int.MIN_VALUE
            val pts = IntArray(4)
            while (head < tail) {
                val i = queue[head++]
                count++
                val x = i % w
                val y = i / w
                if (x + y < minSum) { minSum = x + y; pts[0] = i }
                if (x - y > maxDiff) { maxDiff = x - y; pts[1] = i }
                if (x + y > maxSum) { maxSum = x + y; pts[2] = i }
                if (x - y < minDiff) { minDiff = x - y; pts[3] = i }
                if (x > 0 && inside[i - 1] == 1 && !seen[i - 1]) { seen[i - 1] = true; queue[tail++] = i - 1 }
                if (x < w - 1 && inside[i + 1] == 1 && !seen[i + 1]) { seen[i + 1] = true; queue[tail++] = i + 1 }
                if (i >= w && inside[i - w] == 1 && !seen[i - w]) { seen[i - w] = true; queue[tail++] = i - w }
                if (i < n - w && inside[i + w] == 1 && !seen[i + w]) { seen[i + w] = true; queue[tail++] = i + w }
            }
            if (count > bestCount) {
                bestCount = count
                best = pts
            }
        }
        val pts = best ?: return null
        if (bestCount < n * 0.10f) return null

        val xs = FloatArray(4) { (pts[it] % w) + 0.5f }
        val ys = FloatArray(4) { (pts[it] / w) + 0.5f }

        // The region still includes the outer half of the page's edge band; pull corners in slightly.
        val cx = xs.average().toFloat()
        val cy = ys.average().toFloat()
        for (i in 0 until 4) {
            val dx = cx - xs[i]
            val dy = cy - ys[i]
            val d = hypot(dx, dy)
            if (d > 6f) {
                xs[i] += dx / d * 2f
                ys[i] += dy / d * 2f
            }
        }

        var area = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += xs[i] * ys[j] - xs[j] * ys[i]
        }
        area = abs(area) / 2f
        if (area < n * 0.10f || area > n * 0.985f) return null
        val fill = bestCount / area
        if (fill < 0.75f || fill > 1.25f) return null
        val minSide = min(w, h) * 0.12f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            if (hypot(xs[j] - xs[i], ys[j] - ys[i]) < minSide) return null
        }
        val quad = List(8) { if (it % 2 == 0) (xs[it / 2] / w).coerceIn(0f, 1f) else (ys[it / 2] / h).coerceIn(0f, 1f) }
        return if (ImageOps.isValidQuad(quad)) quad else null
    }

    /** Square structuring element of radius [r]; dilate = max filter, erode = min filter. In place. */
    private fun morph(a: IntArray, tmp: IntArray, w: Int, h: Int, r: Int, dilate: Boolean) {
        val hit = if (dilate) 1 else 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var v = 1 - hit
                val from = max(0, x - r)
                val to = min(w - 1, x + r)
                for (k in from..to) if (a[row + k] == hit) { v = hit; break }
                tmp[row + x] = v
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var v = 1 - hit
                val from = max(0, y - r)
                val to = min(h - 1, y + r)
                for (k in from..to) if (tmp[k * w + x] == hit) { v = hit; break }
                a[y * w + x] = v
            }
        }
    }

    private fun blur3(a: IntArray, tmp: IntArray, w: Int, h: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = a[row + max(0, x - 1)]
                val r = a[row + min(w - 1, x + 1)]
                tmp[row + x] = (l + a[row + x] + r) / 3
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val u = tmp[max(0, y - 1) * w + x]
                val d = tmp[min(h - 1, y + 1) * w + x]
                a[y * w + x] = (u + tmp[y * w + x] + d) / 3
            }
        }
    }

    private fun otsu(hist: IntArray, total: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i].toDouble()
        var sumB = 0.0
        var wB = 0
        var best = 0.0
        var threshold = 0
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF <= 0) break
            sumB += t * hist[t].toDouble()
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val between = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }
}
