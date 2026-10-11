package com.bongzkie.bongscanner.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.bongzkie.bongscanner.export.PageSize

/** App settings, backed by SharedPreferences and observable from Compose. */
object Prefs {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private lateinit var sp: SharedPreferences

    private val themeState = mutableIntStateOf(THEME_SYSTEM)
    private val filterState = mutableStateOf(FilterType.MAGIC)
    private val smartScanState = mutableStateOf(true)
    private val autoCropState = mutableStateOf(true)
    private val gridState = mutableStateOf(true)
    private val sortState = mutableStateOf(SortMode.MODIFIED)
    private val pageSizeState = mutableStateOf(PageSize.AUTO)
    private val qualityState = mutableIntStateOf(0)

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        themeState.intValue = sp.getInt("theme", THEME_SYSTEM)
        filterState.value = enumOr(sp.getString("filter", null), FilterType.MAGIC)
        smartScanState.value = sp.getBoolean("smartScan", true)
        autoCropState.value = sp.getBoolean("autoCrop", true)
        gridState.value = sp.getBoolean("grid", true)
        sortState.value = enumOr(sp.getString("sort", null), SortMode.MODIFIED)
        pageSizeState.value = enumOr(sp.getString("pageSize", null), PageSize.AUTO)
        qualityState.intValue = sp.getInt("quality", 0)
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        if (name == null) fallback else runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

    var themeMode: Int
        get() = themeState.intValue
        set(v) {
            themeState.intValue = v
            sp.edit().putInt("theme", v).apply()
        }

    /** Filter applied to pages captured with the built-in camera. */
    var defaultFilter: FilterType
        get() = filterState.value
        set(v) {
            filterState.value = v
            sp.edit().putString("filter", v.name).apply()
        }

    /** Use Google's Smart Scan screen for the main scan button when the device supports it. */
    var smartScan: Boolean
        get() = smartScanState.value
        set(v) {
            smartScanState.value = v
            sp.edit().putBoolean("smartScan", v).apply()
        }

    /** Detect page edges automatically for built-in camera captures. */
    var autoCrop: Boolean
        get() = autoCropState.value
        set(v) {
            autoCropState.value = v
            sp.edit().putBoolean("autoCrop", v).apply()
        }

    var gridView: Boolean
        get() = gridState.value
        set(v) {
            gridState.value = v
            sp.edit().putBoolean("grid", v).apply()
        }

    var sortMode: SortMode
        get() = sortState.value
        set(v) {
            sortState.value = v
            sp.edit().putString("sort", v.name).apply()
        }

    var pageSize: PageSize
        get() = pageSizeState.value
        set(v) {
            pageSizeState.value = v
            sp.edit().putString("pageSize", v.name).apply()
        }

    /** 0 = original, 1 = medium, 2 = small file. */
    var exportQuality: Int
        get() = qualityState.intValue
        set(v) {
            qualityState.intValue = v
            sp.edit().putInt("quality", v).apply()
        }
}
