package com.bongzkie.bongscanner

import android.app.Application
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Prefs
import com.bongzkie.bongscanner.export.Exporter

object BuildInfo {
    var VERSION: String = "1.0.0"
        internal set
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Library.init(this)
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()?.let { BuildInfo.VERSION = it }
        runCatching { Exporter.clearOldExports(this) }
    }
}
