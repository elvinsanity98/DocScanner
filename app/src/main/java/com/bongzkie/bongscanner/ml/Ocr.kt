package com.bongzkie.bongscanner.ml

import android.graphics.Bitmap
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.imaging.ImageOps
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device text recognition (works offline, the model ships inside the app). */
object Ocr {
    class Line(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** [width] x [height] is the size of the picture the line boxes refer to. */
    class Result(val text: String, val lines: List<Line>, val width: Int, val height: Int)

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): Result = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { text -> if (cont.isActive) cont.resume(toResult(text, bitmap.width, bitmap.height)) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    /** Reads a stored page and remembers its text so the document becomes searchable. */
    suspend fun recognizePage(docId: String, pageId: String): Result {
        val bitmap = withContext(Dispatchers.IO) { ImageOps.decodeFile(Library.pageFile(docId, pageId), 2000) }
            ?: throw IOException("Page image is missing")
        try {
            val result = recognize(bitmap)
            withContext(Dispatchers.Main) { Library.setOcr(docId, pageId, result.text) }
            return result
        } finally {
            bitmap.recycle()
        }
    }

    private fun toResult(text: Text, width: Int, height: Int): Result {
        val lines = ArrayList<Line>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                lines += Line(line.text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            }
        }
        return Result(text.text, lines, width, height)
    }
}
