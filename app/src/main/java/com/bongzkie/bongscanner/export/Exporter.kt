package com.bongzkie.bongscanner.export

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.bongzkie.bongscanner.data.Doc
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Page
import com.bongzkie.bongscanner.imaging.ImageOps
import com.bongzkie.bongscanner.ml.Ocr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

class PdfRequest(
    val pageSize: PageSize = PageSize.AUTO,
    /** 0 = original, 1 = medium, 2 = small file. */
    val quality: Int = 0,
    val idCardLayout: Boolean = false,
    val watermark: String = "",
    val password: String = "",
    val searchable: Boolean = false,
)

/** Turns documents into shareable files and hands them to other apps, storage or a printer. */
object Exporter {

    private fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    fun safeName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifEmpty { "BongScanner" }

    /** Removes exports left over from earlier sessions. */
    fun clearOldExports(context: Context) {
        val limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        shareDir(context).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    suspend fun buildPdf(
        context: Context,
        doc: Doc,
        pages: List<Page>,
        request: PdfRequest,
        progress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw IOException("Nothing to export")
        val dir = shareDir(context)
        val out = File(dir, safeName(doc.title) + ".pdf")
        val temp = ArrayList<File>()
        try {
            val images = pages.mapIndexed { i, page ->
                progress(i / pages.size.toFloat())
                var file = Library.pageFile(doc.id, page.id)
                var (w, h) = ImageOps.bounds(file)
                if (w <= 0 || h <= 0) throw IOException("The image of page ${i + 1} is missing")
                if (request.quality > 0) {
                    val maxSide = if (request.quality == 1) 1754 else 1240
                    val jpegQuality = if (request.quality == 1) 75 else 60
                    val decoded = ImageOps.decodeFile(file, maxSide) ?: throw IOException("Cannot read page ${i + 1}")
                    val small = ImageOps.scaleToFit(decoded, maxSide)
                    val reduced = File(dir, "tmp_${page.id}.jpg")
                    ImageOps.saveJpeg(small, reduced, jpegQuality)
                    w = small.width
                    h = small.height
                    small.recycle()
                    temp += reduced
                    file = reduced
                }
                val lines = if (request.searchable) textLayer(doc.id, page, w) else emptyList()
                PdfImage(file, w, h, lines)
            }
            progress(0.97f)
            FileOutputStream(out).buffered().use { stream ->
                PdfWriter.write(
                    stream, images,
                    PdfOptions(
                        pageSize = request.pageSize,
                        margin = if (request.pageSize == PageSize.AUTO) 0f else 18f,
                        idCardLayout = request.idCardLayout,
                        watermark = request.watermark.trim().ifEmpty { null },
                        password = request.password.ifEmpty { null },
                        title = doc.title,
                    ),
                )
            }
        } finally {
            temp.forEach { it.delete() }
        }
        progress(1f)
        out
    }

    private suspend fun textLayer(docId: String, page: Page, pdfImageWidth: Int): List<PdfTextLine> = try {
        val r = Ocr.recognizePage(docId, page.id)
        val k = pdfImageWidth.toFloat() / r.width
        r.lines.map { PdfTextLine(it.text, it.left * k, it.top * k, it.right * k, it.bottom * k) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    suspend fun buildJpegs(context: Context, doc: Doc, pages: List<Page>): List<File> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) throw IOException("Nothing to export")
        val dir = shareDir(context)
        val base = safeName(doc.title)
        pages.mapIndexed { i, page ->
            val target = File(dir, String.format(Locale.US, "%s_%02d.jpg", base, i + 1))
            Library.pageFile(doc.id, page.id).copyTo(target, overwrite = true)
            target
        }
    }

    suspend fun buildTextFile(context: Context, title: String, text: String): File = withContext(Dispatchers.IO) {
        File(shareDir(context), safeName(title) + ".txt").apply { writeText(text) }
    }

    // ------------------------------------------------------------ share

    fun share(context: Context, files: List<File>, mime: String) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = mime
        send.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply {
            for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(send, "Share with"))
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        start(context, Intent.createChooser(send, "Share with"))
    }

    private fun start(context: Context, intent: Intent) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ------------------------------------------------------------ save to shared storage

    /** Android 9 and older need the storage permission before [savePublic] can work. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * Copies [files] to Download/BongScanner (documents) or Pictures/BongScanner (images).
     * @return the folder the user can find them in.
     */
    suspend fun savePublic(context: Context, files: List<File>, mime: String, pictures: Boolean): String =
        withContext(Dispatchers.IO) {
            val parent = if (pictures) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
            val relative = "$parent/BongScanner"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (pictures) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val resolver = context.contentResolver
                for (file in files) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values) ?: throw IOException("Could not create ${file.name}")
                    try {
                        val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not write ${file.name}")
                        stream.use { o -> FileInputStream(file).use { it.copyTo(o) } }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(parent), "BongScanner")
                if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create $relative")
                val written = files.map { file ->
                    var target = File(dir, file.name)
                    var n = 1
                    while (target.exists()) {
                        target = File(dir, "${file.nameWithoutExtension} (${n++}).${file.extension}")
                    }
                    file.copyTo(target)
                    target.path
                }
                MediaScannerConnection.scanFile(context, written.toTypedArray(), null, null)
            }
            relative
        }

    // ------------------------------------------------------------ print

    fun print(activity: Activity, pdf: File, jobName: String) {
        val manager = activity.getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(jobName, PdfPrintAdapter(pdf, jobName), null)
    }

    private class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?,
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("$name.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, newAttributes != oldAttributes)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback,
        ) {
            try {
                FileInputStream(file).use { input ->
                    FileOutputStream(destination.fileDescriptor).use { output -> input.copyTo(output) }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }
    }
}
