package com.bongzkie.bongscanner.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.bongzkie.bongscanner.data.FilterType
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.imaging.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** Brings pictures and PDF files from outside the app into the library. */
object Importer {

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Adds each image as a page. A new document is created on the first image that can be read when
     * [docId] is null.
     * @return the document id, or null when nothing could be imported.
     */
    suspend fun importImages(
        context: Context,
        uris: List<Uri>,
        docId: String?,
        title: String? = null,
        filter: FilterType = FilterType.ORIGINAL,
        progress: (Float) -> Unit = {},
    ): String? {
        var target = docId
        uris.forEachIndexed { i, uri ->
            progress(i / uris.size.toFloat())
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    ImageOps.decodeUri(context, uri)
                } catch (e: Exception) {
                    null
                }
            } ?: return@forEachIndexed
            try {
                val id = target ?: withContext(Dispatchers.Main) { Library.createDoc(title).id }.also { target = it }
                Library.addPage(id, bitmap, filter = filter)
            } finally {
                bitmap.recycle()
            }
        }
        return target
    }

    /** Renders every page of a PDF into a new document. @return the new document id. */
    suspend fun importPdf(context: Context, uri: Uri, progress: (Float) -> Unit = {}): String {
        val name = displayName(context, uri)?.removeSuffix(".pdf")?.removeSuffix(".PDF")
        val docId = withContext(Dispatchers.Main) { Library.createDoc(name).id }
        var added = 0
        try {
            withContext(Dispatchers.IO) {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IOException("Cannot open this PDF")
                descriptor.use { pfd ->
                    val renderer = try {
                        PdfRenderer(pfd)
                    } catch (e: SecurityException) {
                        throw IOException("This PDF is password protected")
                    }
                    try {
                        val count = renderer.pageCount
                        for (i in 0 until count) {
                            progress(i / count.toFloat())
                            val page = renderer.openPage(i)
                            val bitmap = try {
                                val k = min(2400f / max(page.width, page.height), 4f)
                                val target = Bitmap.createBitmap(
                                    max(1, (page.width * k).toInt()), max(1, (page.height * k).toInt()),
                                    Bitmap.Config.ARGB_8888,
                                )
                                target.eraseColor(Color.WHITE)
                                page.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                target
                            } finally {
                                page.close()
                            }
                            try {
                                Library.addPage(docId, bitmap)
                                added++
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } finally {
                        renderer.close()
                    }
                }
            }
        } finally {
            if (added == 0) withContext(Dispatchers.Main) { Library.deleteDocs(setOf(docId)) }
        }
        return docId
    }
}
