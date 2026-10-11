package com.bongzkie.bongscanner.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.DeflaterOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One line of recognised text, in pixel coordinates of its page image (origin top-left). */
class PdfTextLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** A JPEG file that becomes one picture on a sheet. [lines] adds an invisible, searchable text layer. */
class PdfImage(val jpeg: File, val width: Int, val height: Int, val lines: List<PdfTextLine> = emptyList())

enum class PageSize(val label: String, val w: Float, val h: Float) {
    AUTO("Fit to scan", 0f, 0f),
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
    LEGAL("Legal", 612f, 1008f),
}

class PdfOptions(
    val pageSize: PageSize = PageSize.AUTO,
    val margin: Float = 0f,
    /** Two pictures per A4 sheet, sized like a photocopied ID card. */
    val idCardLayout: Boolean = false,
    val watermark: String? = null,
    val password: String? = null,
    val title: String = "",
)

/**
 * Minimal PDF 1.4 writer. JPEG pages are embedded as-is (DCTDecode), so nothing is re-compressed
 * and memory use stays flat no matter how many pages a document has.
 */
object PdfWriter {
    private const val CATALOG = 1
    private const val PAGES = 2
    private const val FONT = 3
    private const val GSTATE = 4
    private const val INFO = 5
    private const val MAX_PAGE_PT = 14400f

    fun write(out: OutputStream, images: List<PdfImage>, opt: PdfOptions) {
        require(images.isNotEmpty()) { "A PDF needs at least one page" }
        val sink = Sink(out)
        val fileId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val crypt = opt.password?.takeIf { it.isNotEmpty() }?.let { Crypt(it, fileId) }

        val encryptId = if (crypt != null) 6 else 0
        var next = if (crypt != null) 7 else 6
        val sheets: List<List<Int>> =
            if (opt.idCardLayout) images.indices.chunked(2) else images.indices.map { listOf(it) }
        val imageIds = IntArray(images.size) { next++ }
        val pageIds = IntArray(sheets.size) { next++ }
        val contentIds = IntArray(sheets.size) { next++ }
        val offsets = LongArray(next)

        fun begin(id: Int) {
            offsets[id] = sink.pos
            sink.ascii("$id 0 obj\n")
        }

        fun end() = sink.ascii("\nendobj\n")

        sink.ascii("%PDF-1.4\n")
        sink.bytes(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        begin(CATALOG)
        sink.ascii("<< /Type /Catalog /Pages $PAGES 0 R >>")
        end()

        begin(PAGES)
        sink.ascii("<< /Type /Pages /Count ${sheets.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>")
        end()

        begin(FONT)
        sink.ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        end()

        begin(GSTATE)
        sink.ascii("<< /Type /ExtGState /ca 0.22 /CA 0.22 >>")
        end()

        begin(INFO)
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        sink.ascii(
            "<< /Title ${textString(opt.title, INFO, crypt)}" +
                " /Producer ${textString("BongScanner by Bongzkie", INFO, crypt)}" +
                " /Creator ${textString("BongScanner", INFO, crypt)}" +
                " /CreationDate ${byteString("D:$stamp".toByteArray(Charsets.ISO_8859_1), INFO, crypt)} >>"
        )
        end()

        if (crypt != null) {
            begin(encryptId)
            sink.ascii(
                "<< /Filter /Standard /V 2 /R 3 /Length 128 /P ${Crypt.PERMISSIONS}" +
                    " /O <${hex(crypt.o)}> /U <${hex(crypt.u)}> >>"
            )
            end()
        }

        val buf = ByteArray(64 * 1024)
        images.forEachIndexed { i, img ->
            val id = imageIds[i]
            begin(id)
            sink.ascii(
                "<< /Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height}" +
                    " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode" +
                    " /Length ${img.jpeg.length()} >>\nstream\n"
            )
            val cipher = crypt?.cipherFor(id)
            img.jpeg.inputStream().use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    cipher?.process(buf, n)
                    sink.bytes(buf, n)
                }
            }
            sink.ascii("\nendstream")
            end()
        }

        sheets.forEachIndexed { s, idx ->
            val sheetImages = idx.map { images[it] }
            val layout = layoutSheet(sheetImages, opt)
            val data = deflate(buildContent(layout, sheetImages, opt).toByteArray(Charsets.ISO_8859_1))
            crypt?.cipherFor(contentIds[s])?.process(data, data.size)

            begin(contentIds[s])
            sink.ascii("<< /Length ${data.size} /Filter /FlateDecode >>\nstream\n")
            sink.bytes(data, data.size)
            sink.ascii("\nendstream")
            end()

            val xobjects = idx.mapIndexed { k, imageIndex -> "/Im$k ${imageIds[imageIndex]} 0 R" }.joinToString(" ")
            begin(pageIds[s])
            sink.ascii(
                "<< /Type /Page /Parent $PAGES 0 R /MediaBox [0 0 ${num(layout.pageW)} ${num(layout.pageH)}]" +
                    " /Resources << /XObject << $xobjects >> /Font << /F1 $FONT 0 R >>" +
                    " /ExtGState << /GS1 $GSTATE 0 R >> >> /Contents ${contentIds[s]} 0 R >>"
            )
            end()
        }

        val xref = sink.pos
        sink.ascii("xref\n0 $next\n")
        sink.ascii("0000000000 65535 f \n")
        for (id in 1 until next) sink.ascii(String.format(Locale.US, "%010d 00000 n \n", offsets[id]))
        sink.ascii("trailer\n<< /Size $next /Root $CATALOG 0 R /Info $INFO 0 R /ID [<${hex(fileId)}> <${hex(fileId)}>]")
        if (crypt != null) sink.ascii(" /Encrypt $encryptId 0 R")
        sink.ascii(" >>\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    // ---------------------------------------------------------------- layout

    private class Placed(val x: Float, val y: Float, val w: Float, val h: Float)
    private class Layout(val pageW: Float, val pageH: Float, val slots: List<Placed>)

    private fun layoutSheet(imgs: List<PdfImage>, opt: PdfOptions): Layout {
        if (opt.idCardLayout) {
            val pw = PageSize.A4.w
            val ph = PageSize.A4.h
            val maxW = pw * 0.62f
            val maxH = ph * 0.34f
            val centers = floatArrayOf(ph * 0.72f, ph * 0.28f)
            val slots = imgs.mapIndexed { i, im ->
                val s = min(maxW / im.width, maxH / im.height)
                val w = im.width * s
                val h = im.height * s
                Placed((pw - w) / 2f, centers[i] - h / 2f, w, h)
            }
            return Layout(pw, ph, slots)
        }

        val im = imgs[0]
        val landscape = im.width > im.height
        if (opt.pageSize == PageSize.AUTO) {
            var pw = if (landscape) PageSize.A4.h else PageSize.A4.w
            var ph = pw * im.height / im.width
            val longest = max(pw, ph)
            if (longest > MAX_PAGE_PT) {
                val k = MAX_PAGE_PT / longest
                pw *= k
                ph *= k
            }
            return Layout(pw, ph, listOf(Placed(0f, 0f, pw, ph)))
        }
        val pw = if (landscape) opt.pageSize.h else opt.pageSize.w
        val ph = if (landscape) opt.pageSize.w else opt.pageSize.h
        val m = opt.margin.coerceIn(0f, 120f)
        val s = min((pw - 2 * m) / im.width, (ph - 2 * m) / im.height)
        val w = im.width * s
        val h = im.height * s
        return Layout(pw, ph, listOf(Placed((pw - w) / 2f, (ph - h) / 2f, w, h)))
    }

    private fun buildContent(layout: Layout, imgs: List<PdfImage>, opt: PdfOptions): String {
        val sb = StringBuilder()
        imgs.forEachIndexed { k, im ->
            val p = layout.slots[k]
            sb.append("q ${num(p.w)} 0 0 ${num(p.h)} ${num(p.x)} ${num(p.y)} cm /Im$k Do Q\n")
            if (im.lines.isNotEmpty()) {
                // Render mode 3 = invisible: the text can be searched and copied but is never painted.
                val s = p.w / im.width
                sb.append("q BT 3 Tr\n")
                for (ln in im.lines) {
                    val bytes = encode(ln.text)
                    val boxW = (ln.right - ln.left) * s
                    val boxH = (ln.bottom - ln.top) * s
                    if (bytes.isEmpty() || boxW <= 0.5f || boxH <= 0.5f) continue
                    val fontSize = boxH * 0.86f
                    val natural = textWidth(bytes) * fontSize / 1000f
                    if (natural <= 0f) continue
                    val stretch = (boxW / natural * 100f).coerceIn(5f, 2000f)
                    val x = p.x + ln.left * s
                    val y = p.y + (im.height - ln.bottom) * s + boxH * 0.2f
                    sb.append("/F1 ${num(fontSize)} Tf ${num(stretch)} Tz 1 0 0 1 ${num(x)} ${num(y)} Tm ${literal(bytes)} Tj\n")
                }
                sb.append("ET Q\n")
            }
        }

        val mark = encode(opt.watermark?.trim().orEmpty())
        if (mark.isNotEmpty()) {
            val pw = layout.pageW
            val ph = layout.pageH
            val unit = textWidth(mark) / 1000f
            if (unit > 0f) {
                val diagonal = sqrt(pw * pw + ph * ph)
                val fontSize = min(0.7f * diagonal / unit, min(pw, ph) * 0.16f)
                val textW = unit * fontSize
                val angle = atan2(ph, pw)
                val c = cos(angle)
                val s = sin(angle)
                val tx = pw / 2f - textW / 2f * c + fontSize * 0.35f * s
                val ty = ph / 2f - textW / 2f * s - fontSize * 0.35f * c
                sb.append(
                    "q /GS1 gs 0.35 g BT /F1 ${num(fontSize)} Tf " +
                        "${num(c)} ${num(s)} ${num(-s)} ${num(c)} ${num(tx)} ${num(ty)} Tm ${literal(mark)} Tj ET Q\n"
                )
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- text helpers

    private val winAnsi: Charset = try {
        Charset.forName("windows-1252")
    } catch (e: Exception) {
        Charsets.ISO_8859_1
    }

    /** Helvetica advance widths for ASCII 32..126, in 1/1000 em. */
    private val helvetica = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    )

    private fun textWidth(bytes: ByteArray): Float {
        var total = 0
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            total += if (c in 32..126) helvetica[c - 32] else 556
        }
        return total.toFloat()
    }

    private fun encode(text: String): ByteArray {
        val clean = StringBuilder(text.length)
        for (ch in text) {
            if (ch == '\n' || ch == '\r' || ch == '\t') clean.append(' ') else if (ch >= ' ') clean.append(ch)
        }
        return clean.toString().toByteArray(winAnsi)
    }

    private fun literal(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size + 2).append('(')
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            when {
                c == '('.code || c == ')'.code || c == '\\'.code -> sb.append('\\').append(c.toChar())
                c < 32 || c > 126 -> sb.append('\\').append(String.format(Locale.US, "%03o", c))
                else -> sb.append(c.toChar())
            }
        }
        return sb.append(')').toString()
    }

    /** Unicode text string (UTF-16BE with BOM) as a hex string, encrypted when the file is protected. */
    private fun textString(text: String, objId: Int, crypt: Crypt?): String {
        val body = text.toByteArray(Charsets.UTF_16BE)
        val bytes = ByteArray(body.size + 2)
        bytes[0] = 0xFE.toByte()
        bytes[1] = 0xFF.toByte()
        System.arraycopy(body, 0, bytes, 2, body.size)
        return byteString(bytes, objId, crypt)
    }

    private fun byteString(bytes: ByteArray, objId: Int, crypt: Crypt?): String {
        crypt?.cipherFor(objId)?.process(bytes, bytes.size)
        return "<${hex(bytes)}>"
    }

    private fun num(v: Float): String {
        val s = String.format(Locale.US, "%.3f", v)
        return if (s == "-0.000") "0.000" else s
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789ABCDEF"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size / 2 + 64)
        DeflaterOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private class Sink(private val out: OutputStream) {
        var pos = 0L
            private set

        fun bytes(b: ByteArray, len: Int = b.size) {
            out.write(b, 0, len)
            pos += len
        }

        fun ascii(s: String) = bytes(s.toByteArray(Charsets.ISO_8859_1))
    }

    // ---------------------------------------------------------------- password protection

    private class Rc4(key: ByteArray) {
        private val s = IntArray(256) { it }
        private var i = 0
        private var j = 0

        init {
            var k = 0
            for (n in 0 until 256) {
                k = (k + s[n] + (key[n % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[n]
                s[n] = s[k]
                s[k] = t
            }
        }

        /** Encrypts the first [len] bytes of [buf] in place; can be called repeatedly for a stream. */
        fun process(buf: ByteArray, len: Int) {
            for (n in 0 until len) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val t = s[i]
                s[i] = s[j]
                s[j] = t
                buf[n] = (buf[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
        }
    }

    /** PDF standard security handler, revision 3 (128-bit RC4). The open password is also the owner password. */
    private class Crypt(password: String, fileId: ByteArray) {
        val o: ByteArray
        val u: ByteArray
        private val key: ByteArray

        init {
            val user = pad(password)

            var digest = md5(user)
            repeat(50) { digest = md5(digest) }
            val ownerKey = digest.copyOf(16)
            var x = rc4(ownerKey, user)
            for (n in 1..19) x = rc4(xorKey(ownerKey, n), x)
            o = x

            val md = MessageDigest.getInstance("MD5")
            md.update(user)
            md.update(o)
            md.update(
                byteArrayOf(
                    PERMISSIONS.toByte(),
                    (PERMISSIONS shr 8).toByte(),
                    (PERMISSIONS shr 16).toByte(),
                    (PERMISSIONS shr 24).toByte(),
                )
            )
            md.update(fileId)
            var k = md.digest()
            repeat(50) { k = md5(k) }
            key = k.copyOf(16)

            val md2 = MessageDigest.getInstance("MD5")
            md2.update(PAD)
            md2.update(fileId)
            var y = rc4(key, md2.digest())
            for (n in 1..19) y = rc4(xorKey(key, n), y)
            u = y + ByteArray(16)
        }

        fun cipherFor(objId: Int): Rc4 {
            val md = MessageDigest.getInstance("MD5")
            md.update(key)
            md.update(byteArrayOf(objId.toByte(), (objId shr 8).toByte(), (objId shr 16).toByte(), 0, 0))
            return Rc4(md.digest())
        }

        companion object {
            /** Everything allowed once the document is opened. */
            const val PERMISSIONS = -4

            private val PAD = byteArrayOf(
                0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
                0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
                0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
                0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A,
            )

            private fun pad(password: String): ByteArray {
                val raw = password.toByteArray(Charsets.ISO_8859_1)
                val n = min(raw.size, 32)
                val out = ByteArray(32)
                System.arraycopy(raw, 0, out, 0, n)
                System.arraycopy(PAD, 0, out, n, 32 - n)
                return out
            }

            private fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

            private fun rc4(key: ByteArray, data: ByteArray): ByteArray {
                val out = data.copyOf()
                Rc4(key).process(out, out.size)
                return out
            }

            private fun xorKey(key: ByteArray, n: Int): ByteArray = ByteArray(key.size) { (key[it].toInt() xor n).toByte() }
        }
    }
}
