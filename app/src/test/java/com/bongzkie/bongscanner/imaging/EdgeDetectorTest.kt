package com.bongzkie.bongscanner.imaging

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Runs the page finder on synthetic "sheet of paper on a desk" pictures. */
class EdgeDetectorTest {
    private val w = 240
    private val h = 320

    private fun inQuad(x: Float, y: Float, q: FloatArray): Boolean {
        for (i in 0 until 4) {
            val ax = q[i * 2]
            val ay = q[i * 2 + 1]
            val bx = q[(i + 1) % 4 * 2]
            val by = q[(i + 1) % 4 * 2 + 1]
            if ((bx - ax) * (y - ay) - (by - ay) * (x - ax) < 0f) return false
        }
        return true
    }

    private fun shrink(q: FloatArray, by: Float): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f
        val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        return FloatArray(8) { if (it % 2 == 0) cx + (q[it] - cx) * by else cy + (q[it] - cy) * by }
    }

    /** [desk] gives the background brightness for a pixel, so cluttered desks can be simulated. */
    private fun scene(quad: FloatArray, paper: Int, desk: (x: Int, y: Int) -> Int): IntArray {
        val rnd = Random(7)
        val textArea = shrink(quad, 0.8f)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val fx = x + 0.5f
                val fy = y + 0.5f
                var v = if (inQuad(fx, fy, quad)) paper else desk(x, y)
                if (inQuad(fx, fy, textArea) && y % 12 < 2 && x % 7 != 0) v = paper - 150
                v = (v + rnd.nextInt(9) - 4).coerceIn(0, 255)
                px[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return px
    }

    private fun assertFinds(name: String, quad: FloatArray, px: IntArray, tolerance: Float = 0.03f) {
        val found = EdgeDetector.detectPixels(px, w, h)
        assertNotNull("$name: no page found", found)
        for (i in 0 until 8) {
            val expected = if (i % 2 == 0) quad[i] / w else quad[i] / h
            val error = abs(found!![i] - expected)
            assertTrue("$name: corner value $i off by $error (found ${found[i]}, expected $expected)", error <= tolerance)
        }
    }

    @Test
    fun findsPaperOnDarkDesk() {
        val quad = floatArrayOf(50f, 40f, 200f, 60f, 190f, 280f, 40f, 260f)
        assertFinds("dark desk", quad, scene(quad, 235) { _, _ -> 60 })
    }

    @Test
    fun findsPaperOnLightDesk() {
        val quad = floatArrayOf(30f, 50f, 205f, 35f, 215f, 290f, 45f, 300f)
        assertFinds("light desk", quad, scene(quad, 245) { _, _ -> 190 })
    }

    @Test
    fun ignoresDeskEdgeBehindThePaper() {
        // The desk changes tone half way down, drawing a long straight line that touches the sheet.
        val quad = floatArrayOf(55f, 45f, 195f, 55f, 185f, 275f, 45f, 265f)
        assertFinds("two-tone desk", quad, scene(quad, 235) { _, y -> if (y < h / 2) 70 else 120 }, tolerance = 0.04f)
    }

    @Test
    fun findsDarkCardOnLightTable() {
        val quad = floatArrayOf(40f, 90f, 200f, 90f, 200f, 200f, 40f, 200f)
        assertFinds("dark card", quad, scene(quad, 200) { _, _ -> 240 })
    }

    @Test
    fun reportsNothingOnEmptyPicture() {
        val rnd = Random(3)
        val px = IntArray(w * h) {
            val v = 128 + rnd.nextInt(7) - 3
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertNull(EdgeDetector.detectPixels(px, w, h))
    }
}
