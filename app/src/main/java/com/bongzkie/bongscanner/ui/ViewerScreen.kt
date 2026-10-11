@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.bongzkie.bongscanner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Page
import com.bongzkie.bongscanner.export.Exporter
import com.bongzkie.bongscanner.imaging.rememberBitmap

/** Full-screen, swipeable view of the pages with the per-page editing tools. */
@Composable
fun ViewerScreen(nav: Nav, docId: String, startIndex: Int) {
    val doc = Library.doc(docId)
    if (doc == null || doc.pages.isEmpty()) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val context = LocalContext.current
    val storageGate = rememberStorageGate()
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, doc.pages.lastIndex)) { doc.pages.size }
    val index = pagerState.currentPage.coerceIn(0, doc.pages.lastIndex)
    val page = doc.pages[index]

    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exportPage by remember { mutableStateOf<Page?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Page ${index + 1} of ${doc.pages.size}") },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { exportPage = page }) { Icon(Icons.Filled.IosShare, "Export page") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Save image to gallery") }, onClick = {
                            menu = false
                            storageGate {
                                launchBusy(context, "Saving…") {
                                    val files = Exporter.buildJpegs(context, doc, listOf(page))
                                    val where = Exporter.savePublic(context, files, "image/jpeg", pictures = true)
                                    toast(context, "Saved to $where")
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Rotate left") }, onClick = {
                            menu = false
                            launchBusy(context, "Rotating…") {
                                Library.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 270) % 360) }
                            }
                        })
                        DropdownMenuItem(text = { Text("Delete page") }, onClick = {
                            menu = false
                            confirmDelete = true
                        })
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolButton(Icons.Filled.Crop, "Crop") { nav.push(Screen.Crop(docId, page.id)) }
                    ToolButton(Icons.Filled.RotateRight, "Rotate") {
                        launchBusy(context, "Rotating…") {
                            Library.editPage(docId, page.id) { it.copy(rotation = (it.rotation + 90) % 360) }
                        }
                    }
                    ToolButton(Icons.Filled.AutoFixHigh, "Filter") { nav.push(Screen.Filters(docId, page.id)) }
                    ToolButton(Icons.Filled.Draw, "Sign / Draw") { nav.push(Screen.Annotate(docId, page.id)) }
                    ToolButton(Icons.Filled.TextFields, "To text") { nav.push(Screen.TextResult(docId, page.id)) }
                    ToolButton(Icons.Filled.Delete, "Delete") { confirmDelete = true }
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            key = { i -> doc.pages.getOrNull(i)?.id ?: "page$i" },
            modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) { i ->
            val p = doc.pages.getOrNull(i)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (p != null) {
                    val bitmap = rememberBitmap(Library.pageFile(docId, p.id), p.stamp, 2000)
                    if (bitmap != null) {
                        ZoomableImage(bitmap, Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this page?",
            text = "This cannot be undone.",
            confirm = "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                Library.deletePages(docId, setOf(page.id))
            },
        )
    }
    exportPage?.let { p -> ExportDialog(doc, listOf(p), onDismiss = { exportPage = null }) }
}

/** Pinch to zoom, drag to pan while zoomed, double-tap to toggle. Leaves plain swipes to the pager. */
@Composable
fun ZoomableImage(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Image(
        bitmap = bitmap,
        contentDescription = "Page",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = clamp(offset + event.calculatePan(), newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
