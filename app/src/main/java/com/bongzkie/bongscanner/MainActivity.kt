package com.bongzkie.bongscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.export.Importer
import com.bongzkie.bongscanner.ui.AnnotateScreen
import com.bongzkie.bongscanner.ui.BongTheme
import com.bongzkie.bongscanner.ui.BusyOverlay
import com.bongzkie.bongscanner.ui.CameraScreen
import com.bongzkie.bongscanner.ui.CropScreen
import com.bongzkie.bongscanner.ui.DocumentScreen
import com.bongzkie.bongscanner.ui.FilterScreen
import com.bongzkie.bongscanner.ui.HomeScreen
import com.bongzkie.bongscanner.ui.Nav
import com.bongzkie.bongscanner.ui.QrScreen
import com.bongzkie.bongscanner.ui.Screen
import com.bongzkie.bongscanner.ui.SettingsScreen
import com.bongzkie.bongscanner.ui.TextScreen
import com.bongzkie.bongscanner.ui.ViewerScreen
import com.bongzkie.bongscanner.ui.launchBusy
import com.bongzkie.bongscanner.ui.toast

class MainActivity : ComponentActivity() {
    private val nav = Nav()
    private val incoming = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) incoming.value = intent
        setContent { AppRoot(nav, incoming) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }

    override fun onStop() {
        super.onStop()
        Library.flush()
    }
}

@Composable
private fun AppRoot(nav: Nav, incoming: MutableState<Intent?>) {
    BongTheme {
        val context = LocalContext.current

        // Pictures or a PDF shared to BongScanner from another app become a new document.
        val shared = incoming.value
        LaunchedEffect(shared) {
            val intent = shared ?: return@LaunchedEffect
            incoming.value = null
            val action = intent.action
            if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return@LaunchedEffect
            val uris: List<Uri> = if (action == Intent.ACTION_SEND) {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            } else {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
            }
            if (uris.isEmpty()) return@LaunchedEffect
            val pdf = intent.type == "application/pdf"
            launchBusy(context, "Importing…") { progress ->
                val id = if (pdf) {
                    Importer.importPdf(context, uris[0], progress)
                } else {
                    Importer.importImages(context, uris, null, progress = progress)
                }
                if (id != null) nav.openDocument(id) else toast(context, "Could not read the shared file")
            }
        }

        // Scroll positions are kept per screen and dropped once a screen leaves the back stack.
        val holder = rememberSaveableStateHolder()
        val keys = nav.stack.map { it.toString() }
        val known = remember { mutableSetOf<String>() }
        SideEffect {
            (known - keys.toSet()).forEach { holder.removeState(it) }
            known.clear()
            known.addAll(keys)
        }

        BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val screen = nav.current
            holder.SaveableStateProvider(screen.toString()) {
                when (screen) {
                    is Screen.Home -> HomeScreen(nav)
                    is Screen.Document -> DocumentScreen(nav, screen.docId)
                    is Screen.Viewer -> ViewerScreen(nav, screen.docId, screen.index)
                    is Screen.Crop -> CropScreen(nav, screen.docId, screen.pageId, screen.fresh)
                    is Screen.Filters -> FilterScreen(nav, screen.docId, screen.pageId)
                    is Screen.Annotate -> AnnotateScreen(nav, screen.docId, screen.pageId)
                    is Screen.TextResult -> TextScreen(nav, screen.docId, screen.pageId)
                    is Screen.Camera -> CameraScreen(nav, screen.docId, screen.mode, screen.folderId)
                    is Screen.Qr -> QrScreen(nav)
                    is Screen.Settings -> SettingsScreen(nav)
                }
            }
        }
        BusyOverlay()
    }
}
