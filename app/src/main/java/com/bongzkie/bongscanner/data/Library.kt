package com.bongzkie.bongscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bongzkie.bongscanner.imaging.ImageOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * All documents, pages and folders. Metadata lives in one JSON file; every page keeps two JPEGs:
 * `<id>_o.jpg` (the untouched capture) and `<id>.jpg` (cropped + filtered result).
 *
 * State is read by Compose, so every mutation happens on the main thread.
 */
object Library {
    private const val TAG = "Library"

    private lateinit var root: File
    private lateinit var meta: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()
    private var saveJob: Job? = null

    var docs by mutableStateOf<List<Doc>>(emptyList())
        private set
    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set

    fun init(context: Context) {
        root = File(context.filesDir, "docs").apply { mkdirs() }
        meta = File(context.filesDir, "library.json")
        try {
            if (meta.exists()) {
                val o = JSONObject(meta.readText())
                val f = o.optJSONArray("folders") ?: JSONArray()
                val d = o.optJSONArray("docs") ?: JSONArray()
                folders = List(f.length()) { Folder.fromJson(f.getJSONObject(it)) }
                docs = List(d.length()) { Doc.fromJson(d.getJSONObject(it)) }.filter { it.pages.isNotEmpty() }
            } else {
                docs = recoverFromFiles()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read library, rebuilding it from the stored pages", e)
            docs = recoverFromFiles()
            if (docs.isNotEmpty()) changed()
        }
    }

    /** Last resort when the metadata file is lost or unreadable: one document per page folder. */
    private fun recoverFromFiles(): List<Doc> = try {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US)
        root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            val pages = dir.listFiles().orEmpty()
                .filter { it.name.endsWith(".jpg") && !it.name.endsWith("_o.jpg") }
                .sortedBy { it.lastModified() }
                .map { Page(id = it.name.removeSuffix(".jpg"), stamp = it.lastModified()) }
            if (pages.isEmpty()) {
                null
            } else {
                val time = pages.first().stamp
                Doc(dir.name, "Recovered ${stamp.format(Date(time))}", time, dir.lastModified(), pages)
            }
        }.sortedByDescending { it.modified }
    } catch (e: Exception) {
        emptyList()
    }

    /** Removes documents that ended up without any page (for example after a failed import). */
    fun pruneEmpty() {
        val empty = docs.filter { it.pages.isEmpty() }.map { it.id }.toSet()
        if (empty.isNotEmpty()) deleteDocs(empty)
    }

    // ------------------------------------------------------------ persistence

    private fun toJson(d: List<Doc>, f: List<Folder>): String = JSONObject().apply {
        put("version", 1)
        put("folders", JSONArray().apply { f.forEach { put(it.toJson()) } })
        put("docs", JSONArray().apply { d.forEach { put(it.toJson()) } })
    }.toString()

    private fun write(json: String) = synchronized(writeLock) {
        val tmp = File(meta.parentFile, meta.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(meta)) {
            meta.writeText(json)
            tmp.delete()
        }
    }

    private fun changed() {
        val d = docs
        val f = folders
        saveJob?.cancel()
        saveJob = io.launch {
            delay(200)
            try {
                write(toJson(d, f))
            } catch (e: Exception) {
                Log.e(TAG, "Could not save library", e)
            }
        }
    }

    /** Writes pending changes right away (called when the app goes to the background). */
    fun flush() {
        saveJob?.cancel()
        try {
            write(toJson(docs, folders))
        } catch (e: Exception) {
            Log.e(TAG, "Could not save library", e)
        }
    }

    // ------------------------------------------------------------ lookups

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    fun docDir(docId: String) = File(root, docId)
    fun origFile(docId: String, pageId: String) = File(docDir(docId), "${pageId}_o.jpg")
    fun pageFile(docId: String, pageId: String) = File(docDir(docId), "$pageId.jpg")

    fun doc(id: String): Doc? = docs.firstOrNull { it.id == id }
    fun page(docId: String, pageId: String): Page? = doc(docId)?.pages?.firstOrNull { it.id == pageId }

    fun defaultTitle(prefix: String = "Scan"): String =
        "$prefix ${SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())}"

    fun matches(doc: Doc, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return doc.title.contains(q, ignoreCase = true) ||
            doc.pages.any { it.ocrText?.contains(q, ignoreCase = true) == true }
    }

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ------------------------------------------------------------ documents

    fun createDoc(title: String? = null, folderId: String? = null, idCard: Boolean = false): Doc {
        val now = System.currentTimeMillis()
        val doc = Doc(
            id = newId(),
            title = title?.trim()?.takeIf { it.isNotEmpty() } ?: defaultTitle(if (idCard) "ID Card" else "Scan"),
            created = now,
            modified = now,
            folderId = folderId,
            idCard = idCard,
        )
        docDir(doc.id).mkdirs()
        docs = listOf(doc) + docs
        changed()
        return doc
    }

    private fun updateDoc(id: String, touch: Boolean = true, f: (Doc) -> Doc) {
        var hit = false
        docs = docs.map {
            if (it.id == id) {
                hit = true
                val n = f(it)
                if (touch) n.copy(modified = System.currentTimeMillis()) else n
            } else {
                it
            }
        }
        if (hit) changed()
    }

    fun rename(id: String, title: String) {
        val t = title.trim()
        if (t.isNotEmpty()) updateDoc(id) { it.copy(title = t) }
    }

    fun setStarred(id: String, starred: Boolean) = updateDoc(id, touch = false) { it.copy(starred = starred) }

    fun moveToFolder(ids: Set<String>, folderId: String?) {
        docs = docs.map { if (it.id in ids) it.copy(folderId = folderId) else it }
        changed()
    }

    fun deleteDocs(ids: Set<String>) {
        if (ids.isEmpty()) return
        docs = docs.filterNot { it.id in ids }
        changed()
        io.launch { ids.forEach { docDir(it).deleteRecursively() } }
    }

    /** Appends the pages of every other document to the first one. @return id of the merged document. */
    fun mergeDocs(ids: List<String>): String? {
        val list = ids.mapNotNull { doc(it) }
        if (list.size < 2) return null
        val target = list.first()
        val pages = target.pages.toMutableList()
        val dir = docDir(target.id).apply { mkdirs() }
        for (other in list.drop(1)) {
            for (p in other.pages) {
                origFile(other.id, p.id).renameTo(File(dir, "${p.id}_o.jpg"))
                pageFile(other.id, p.id).renameTo(File(dir, "${p.id}.jpg"))
                pages += p
            }
        }
        val gone = list.drop(1).map { it.id }.toSet()
        docs = docs.filterNot { it.id in gone }
            .map { if (it.id == target.id) it.copy(pages = pages, modified = System.currentTimeMillis()) else it }
        changed()
        io.launch { gone.forEach { docDir(it).deleteRecursively() } }
        return target.id
    }

    /** Moves the chosen pages into a new document. @return id of the new document. */
    fun extractPages(docId: String, pageIds: Set<String>): String? {
        val source = doc(docId) ?: return null
        val moving = source.pages.filter { it.id in pageIds }
        if (moving.isEmpty()) return null
        val created = createDoc(title = "${source.title} (part)", folderId = source.folderId)
        val dir = docDir(created.id).apply { mkdirs() }
        for (p in moving) {
            origFile(docId, p.id).renameTo(File(dir, "${p.id}_o.jpg"))
            pageFile(docId, p.id).renameTo(File(dir, "${p.id}.jpg"))
        }
        val left = source.pages.filterNot { it.id in pageIds }
        docs = docs.mapNotNull {
            when (it.id) {
                created.id -> it.copy(pages = moving)
                docId -> if (left.isEmpty()) null else it.copy(pages = left, modified = System.currentTimeMillis())
                else -> it
            }
        }
        changed()
        if (left.isEmpty()) io.launch { docDir(docId).deleteRecursively() }
        return created.id
    }

    // ------------------------------------------------------------ pages

    /** Stores [bitmap] as a new page (the bitmap is not recycled). */
    suspend fun addPage(
        docId: String,
        bitmap: Bitmap,
        corners: List<Float>? = null,
        filter: FilterType = FilterType.ORIGINAL,
    ): Page {
        val page = Page(id = newId(), corners = corners?.takeUnless { ImageOps.isFullFrame(it) }, filter = filter)
        withContext(Dispatchers.Default) {
            ImageOps.heavy.withLock {
                ImageOps.saveJpeg(bitmap, origFile(docId, page.id), 95)
                val out = ImageOps.render(bitmap, page)
                try {
                    ImageOps.saveJpeg(out, pageFile(docId, page.id), 90)
                } finally {
                    out.recycle()
                }
            }
        }
        withContext(Dispatchers.Main) { updateDoc(docId) { it.copy(pages = it.pages + page) } }
        return page
    }

    /** Applies [change] to a page and re-renders its image from the original when needed. */
    suspend fun editPage(docId: String, pageId: String, change: (Page) -> Page) {
        val old = page(docId, pageId) ?: return
        val edited = change(old)
        val geometryChanged = edited.corners != old.corners
        val renderNeeded = geometryChanged || edited.rotation != old.rotation || edited.filter != old.filter ||
            edited.brightness != old.brightness || edited.contrast != old.contrast
        if (!renderNeeded) return
        val updated = edited.copy(
            stamp = System.currentTimeMillis(),
            ocrText = if (geometryChanged) null else edited.ocrText,
        )
        withContext(Dispatchers.Default) {
            ImageOps.heavy.withLock {
                val orig = ImageOps.decodeFile(origFile(docId, pageId), ImageOps.MAX_SIDE)
                    ?: throw IOException("The original image of this page is missing")
                try {
                    val out = ImageOps.render(orig, updated)
                    try {
                        ImageOps.saveJpeg(out, pageFile(docId, pageId), 90)
                    } finally {
                        out.recycle()
                    }
                } finally {
                    orig.recycle()
                }
            }
        }
        withContext(Dispatchers.Main) { replacePage(docId, updated) }
    }

    /** Bakes [bitmap] (for example a page with pen strokes) in as the new original of the page. */
    suspend fun flattenPage(docId: String, pageId: String, bitmap: Bitmap) {
        val old = page(docId, pageId) ?: return
        withContext(Dispatchers.Default) {
            ImageOps.heavy.withLock {
                ImageOps.saveJpeg(bitmap, origFile(docId, pageId), 95)
                ImageOps.saveJpeg(bitmap, pageFile(docId, pageId), 90)
            }
        }
        val updated = old.copy(
            corners = null, rotation = 0, filter = FilterType.ORIGINAL, brightness = 0f, contrast = 0f,
            ocrText = null, stamp = System.currentTimeMillis(),
        )
        withContext(Dispatchers.Main) { replacePage(docId, updated) }
    }

    private fun replacePage(docId: String, page: Page) =
        updateDoc(docId) { d -> d.copy(pages = d.pages.map { if (it.id == page.id) page else it }) }

    fun setOcr(docId: String, pageId: String, text: String) = updateDoc(docId, touch = false) { d ->
        d.copy(pages = d.pages.map { if (it.id == pageId) it.copy(ocrText = text) else it })
    }

    /** Removes pages; a document left without pages is removed as well. */
    fun deletePages(docId: String, pageIds: Set<String>) {
        val d = doc(docId) ?: return
        val left = d.pages.filterNot { it.id in pageIds }
        if (left.isEmpty()) {
            deleteDocs(setOf(docId))
            return
        }
        updateDoc(docId) { it.copy(pages = left) }
        io.launch {
            pageIds.forEach {
                origFile(docId, it).delete()
                pageFile(docId, it).delete()
            }
        }
    }

    fun movePage(docId: String, from: Int, to: Int) = updateDoc(docId) { d ->
        if (from !in d.pages.indices || to !in d.pages.indices || from == to) {
            d
        } else {
            d.copy(pages = d.pages.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    // ------------------------------------------------------------ folders

    fun addFolder(name: String): Folder? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val folder = Folder(newId(), n)
        folders = folders + folder
        changed()
        return folder
    }

    fun renameFolder(id: String, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        folders = folders.map { if (it.id == id) it.copy(name = n) else it }
        changed()
    }

    /** Deletes the folder only; its documents move back to "All". */
    fun deleteFolder(id: String) {
        folders = folders.filterNot { it.id == id }
        docs = docs.map { if (it.folderId == id) it.copy(folderId = null) else it }
        changed()
    }
}
