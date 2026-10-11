package com.bongzkie.bongscanner.data

import org.json.JSONArray
import org.json.JSONObject

enum class FilterType(val label: String) {
    ORIGINAL("Original"),
    MAGIC("Magic Color"),
    LIGHTEN("Lighten"),
    GRAY("Grayscale"),
    BW_SOFT("B&W Soft"),
    BW("B&W"),
}

enum class SortMode(val label: String) {
    MODIFIED("Last modified"),
    CREATED("Date created"),
    NAME("Name (A-Z)"),
    PAGES("Page count"),
}

/**
 * One scanned page. The untouched capture is kept on disk next to the rendered result, so crop,
 * rotation and filter stay editable at any time.
 */
data class Page(
    val id: String,
    /** Crop quad on the original image: TL, TR, BR, BL as normalised x,y pairs. Null = whole image. */
    val corners: List<Float>? = null,
    val rotation: Int = 0,
    val filter: FilterType = FilterType.ORIGINAL,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val ocrText: String? = null,
    /** Changes whenever the rendered image changes; used as an image-cache key. */
    val stamp: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        corners?.let { c -> put("corners", JSONArray().apply { c.forEach { put(it.toDouble()) } }) }
        put("rotation", rotation)
        put("filter", filter.name)
        put("brightness", brightness.toDouble())
        put("contrast", contrast.toDouble())
        ocrText?.let { put("ocr", it) }
        put("stamp", stamp)
    }

    companion object {
        fun fromJson(o: JSONObject): Page {
            val c = o.optJSONArray("corners")
            val corners = if (c != null && c.length() == 8) List(8) { c.getDouble(it).toFloat() } else null
            return Page(
                id = o.getString("id"),
                corners = corners,
                rotation = o.optInt("rotation", 0),
                filter = runCatching { FilterType.valueOf(o.optString("filter")) }.getOrDefault(FilterType.ORIGINAL),
                brightness = o.optDouble("brightness", 0.0).toFloat(),
                contrast = o.optDouble("contrast", 0.0).toFloat(),
                ocrText = if (o.has("ocr")) o.getString("ocr") else null,
                stamp = o.optLong("stamp", 0L),
            )
        }
    }
}

data class Doc(
    val id: String,
    val title: String,
    val created: Long,
    val modified: Long,
    val pages: List<Page> = emptyList(),
    val folderId: String? = null,
    val starred: Boolean = false,
    /** Created by ID Card mode: exports two sides per A4 sheet by default. */
    val idCard: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("created", created)
        put("modified", modified)
        folderId?.let { put("folder", it) }
        put("starred", starred)
        put("idCard", idCard)
        put("pages", JSONArray().apply { pages.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Doc {
            val arr = o.optJSONArray("pages") ?: JSONArray()
            return Doc(
                id = o.getString("id"),
                title = o.optString("title", "Untitled"),
                created = o.optLong("created", 0L),
                modified = o.optLong("modified", 0L),
                pages = List(arr.length()) { Page.fromJson(arr.getJSONObject(it)) },
                folderId = if (o.has("folder")) o.getString("folder") else null,
                starred = o.optBoolean("starred", false),
                idCard = o.optBoolean("idCard", false),
            )
        }
    }
}

data class Folder(val id: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)

    companion object {
        fun fromJson(o: JSONObject) = Folder(o.getString("id"), o.optString("name", "Folder"))
    }
}
