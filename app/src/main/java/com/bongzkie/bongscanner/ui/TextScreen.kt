@file:OptIn(ExperimentalMaterial3Api::class)

package com.bongzkie.bongscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.export.Exporter
import com.bongzkie.bongscanner.ml.Ocr
import kotlinx.coroutines.CancellationException

/** Text recognised from one page (or every page of the document), ready to edit, copy or share. */
@Composable
fun TextScreen(nav: Nav, docId: String, pageId: String?) {
    val doc = Library.doc(docId)
    if (doc == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val storageGate = rememberStorageGate()

    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumping this starts a fresh pass that ignores text remembered from earlier.
    var pass by remember { mutableIntStateOf(0) }

    LaunchedEffect(pass) {
        running = true
        error = null
        val force = pass > 0
        try {
            val ids = if (pageId != null) listOf(pageId) else (Library.doc(docId)?.pages?.map { it.id } ?: emptyList())
            val sb = StringBuilder()
            ids.forEachIndexed { i, id ->
                progress = i / ids.size.toFloat()
                val known = Library.page(docId, id)?.ocrText
                val pageText = if (!force && known != null) known else Ocr.recognizePage(docId, id).text
                if (ids.size > 1) sb.append("--- Page ${i + 1} ---\n")
                sb.append(pageText.trim()).append("\n\n")
            }
            text = sb.toString().trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = friendly(e)
        } finally {
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Text (OCR)")
                        Text(
                            if (pageId != null) "One page" else "Whole document",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(enabled = !running, onClick = { pass++ }) { Icon(Icons.Filled.Refresh, "Read again") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val ready = !running && text.isNotBlank()
                    ToolButton(Icons.Filled.ContentCopy, "Copy", enabled = ready) {
                        clipboard.setText(AnnotatedString(text))
                        toast(context, "Text copied")
                    }
                    ToolButton(Icons.Filled.Share, "Share", enabled = ready) { Exporter.shareText(context, text) }
                    ToolButton(Icons.Filled.SaveAlt, "Save .txt", enabled = ready) {
                        val content = text
                        storageGate {
                            launchBusy(context, "Saving…") {
                                val file = Exporter.buildTextFile(context, doc.title, content)
                                val where = Exporter.savePublic(context, listOf(file), "text/plain", pictures = false)
                                toast(context, "Saved to $where")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                running -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    if (pageId == null && doc.pages.size > 1) {
                        val p = progress
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    } else {
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Reading text…")
                }

                error != null -> Text(
                    "Could not read the text.\n${error.orEmpty()}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(32.dp),
                )

                else -> OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("No text was found on this page.") },
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }
    }
}
