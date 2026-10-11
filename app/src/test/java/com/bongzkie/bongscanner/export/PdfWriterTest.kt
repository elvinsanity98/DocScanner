package com.bongzkie.bongscanner.export

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Writes sample PDFs into app/build/pdf-test so they can be opened and checked by hand.
 * Needs sample pictures p1.jpg (portrait), p2.jpg (landscape) and p3.jpg (card) in that folder;
 * the test is skipped when they are not there.
 */
class PdfWriterTest {
    private val dir = File("build/pdf-test")

    /** Reads width and height from the JPEG start-of-frame header. */
    private fun jpegSize(file: File): Pair<Int, Int> {
        val b = file.readBytes()
        var i = 2
        while (i + 9 < b.size) {
            if (b[i].toInt() and 0xFF != 0xFF) {
                i++
                continue
            }
            val marker = b[i + 1].toInt() and 0xFF
            val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + length
        }
        error("Not a JPEG: $file")
    }

    private fun page(name: String, label: String): PdfImage {
        val file = File(dir, name)
        val (w, h) = jpegSize(file)
        val line = PdfTextLine(label, w * 0.1f, h * 0.45f, w * 0.8f, h * 0.5f)
        return PdfImage(file, w, h, listOf(line))
    }

    private fun write(name: String, images: List<PdfImage>, opt: PdfOptions) {
        val file = File(dir, name)
        file.outputStream().use { PdfWriter.write(it, images, opt) }
        assertTrue(file.length() > 500)
        assertTrue(String(file.readBytes().copyOf(8), Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
    }

    @Test
    fun writesSamples() {
        assumeTrue(File(dir, "p1.jpg").exists() && File(dir, "p2.jpg").exists() && File(dir, "p3.jpg").exists())
        val portrait = page("p1.jpg", "Hello BongScanner page one")
        val landscape = page("p2.jpg", "Landscape (page) two \\ test")
        val card = page("p3.jpg", "ID CARD SIDE")

        write("plain.pdf", listOf(portrait, landscape), PdfOptions(title = "Plain sample"))
        write(
            "a4-watermark.pdf", listOf(portrait, landscape),
            PdfOptions(pageSize = PageSize.A4, margin = 24f, watermark = "CONFIDENTIAL", title = "Wm éñ sample"),
        )
        write("idcard.pdf", listOf(card, card, card), PdfOptions(idCardLayout = true, title = "ID"))
        write(
            "locked.pdf", listOf(portrait, landscape),
            PdfOptions(pageSize = PageSize.LETTER, watermark = "LOCKED", password = "bong123", title = "Locked sample"),
        )
    }
}
