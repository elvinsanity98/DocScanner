@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.bongzkie.bongscanner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Page
import com.bongzkie.bongscanner.data.Prefs
import com.bongzkie.bongscanner.export.Importer

@Composable
fun DocumentScreen(nav: Nav, docId: String) {
    val doc = Library.doc(docId)
    if (doc == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val context = LocalContext.current

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var deleteDoc by remember { mutableStateOf(false) }
    var deletePages by remember { mutableStateOf(false) }
    var exportPages by remember { mutableStateOf<List<Page>?>(null) }

    fun stopSelecting() {
        selecting = false
        selected = emptySet()
    }

    BackHandler(enabled = selecting) { stopSelecting() }

    val smartScan = rememberSmartScan(
        onResult = { uris ->
            launchBusy(context, "Saving scans…") { progress ->
                Importer.importImages(context, uris, docId, progress = progress)
            }
        },
        onUnavailable = {
            toast(context, "Smart Scan is not available here, using the built-in camera")
            nav.push(Screen.Camera(docId, CameraMode.BATCH))
        },
    )
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            launchBusy(context, "Importing…") { progress ->
                Importer.importImages(context, uris, docId, progress = progress)
            }
        }
    }

    val gridState = rememberLazyGridState()
    val dragState = rememberGridDragDropState(gridState) { from, to -> Library.movePage(docId, from, to) }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { stopSelecting() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    },
                    actions = {
                        IconButton(onClick = { selected = doc.pages.map { it.id }.toSet() }) {
                            Icon(Icons.Filled.SelectAll, "Select all")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column(Modifier.clickable { rename = true }) {
                            Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"} · tap to rename",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        IconButton(onClick = { exportPages = doc.pages }) { Icon(Icons.Filled.IosShare, "Export") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Select pages") }, onClick = {
                                menu = false
                                selecting = true
                            })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                menu = false
                                rename = true
                            })
                            DropdownMenuItem(text = { Text("Move to folder") }, onClick = {
                                menu = false
                                move = true
                            })
                            DropdownMenuItem(
                                text = { Text(if (doc.starred) "Remove star" else "Add star") },
                                onClick = {
                                    menu = false
                                    Library.setStarred(docId, !doc.starred)
                                },
                            )
                            DropdownMenuItem(text = { Text("Delete document") }, onClick = {
                                menu = false
                                deleteDoc = true
                            })
                        }
                    },
                )
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    if (selecting) {
                        val any = selected.isNotEmpty()
                        ToolButton(Icons.Filled.IosShare, "Export", enabled = any) {
                            exportPages = doc.pages.filter { it.id in selected }
                        }
                        ToolButton(
                            Icons.Filled.ContentCut, "Split off",
                            enabled = any && selected.size < doc.pages.size,
                        ) {
                            val created = Library.extractPages(docId, selected)
                            stopSelecting()
                            if (created != null) toast(context, "Moved to a new document")
                        }
                        ToolButton(Icons.Filled.Delete, "Delete", enabled = any) { deletePages = true }
                    } else {
                        ToolButton(Icons.Filled.DocumentScanner, "Add scan") {
                            if (Prefs.smartScan) smartScan() else nav.push(Screen.Camera(docId, CameraMode.BATCH))
                        }
                        ToolButton(Icons.Filled.CameraAlt, "Camera") { nav.push(Screen.Camera(docId, CameraMode.BATCH)) }
                        ToolButton(Icons.Filled.PhotoLibrary, "Gallery") {
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        ToolButton(Icons.Filled.TextFields, "To text") { nav.push(Screen.TextResult(docId, null)) }
                        ToolButton(Icons.Filled.IosShare, "Export") { exportPages = doc.pages }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                if (selecting) "Tap pages to select them" else "Tap a page to edit · hold and drag to reorder",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().then(if (selecting) Modifier else Modifier.dragContainer(dragState)),
            ) {
                itemsIndexed(doc.pages, key = { _, page -> page.id }) { index, page ->
                    DraggableItem(dragState, index) { dragging ->
                        val isSelected = page.id in selected
                        val elevation by animateDpAsState(if (dragging) 10.dp else 1.dp, label = "pageElevation")
                        val shape = RoundedCornerShape(12.dp)
                        Card(
                            shape = shape,
                            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                            modifier = Modifier
                                .border(
                                    2.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape,
                                )
                                .clip(shape)
                                .clickable {
                                    if (selecting) {
                                        selected = if (isSelected) selected - page.id else selected + page.id
                                    } else {
                                        nav.push(Screen.Viewer(docId, index))
                                    }
                                },
                        ) {
                            Box {
                                PageThumb(docId, page, 420, Modifier.fillMaxWidth().aspectRatio(0.72f))
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(6.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Text("${index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                                if (selecting) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else Color.White.copy(alpha = 0.85f)
                                            )
                                            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Filled.Check, "Selected",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ dialogs

    if (rename) {
        TextPromptDialog(
            title = "Rename document", label = "Title", initial = doc.title, confirm = "Rename",
            onDismiss = { rename = false },
            onConfirm = {
                Library.rename(docId, it)
                rename = false
            },
        )
    }
    if (move) {
        FolderPickerDialog(
            current = doc.folderId,
            onDismiss = { move = false },
            onPick = {
                Library.moveToFolder(setOf(docId), it)
                move = false
            },
        )
    }
    if (deleteDoc) {
        ConfirmDialog(
            title = "Delete document?",
            text = "All ${doc.pages.size} page(s) will be removed from this device. This cannot be undone.",
            confirm = "Delete",
            onDismiss = { deleteDoc = false },
            onConfirm = {
                deleteDoc = false
                Library.deleteDocs(setOf(docId))
            },
        )
    }
    if (deletePages) {
        ConfirmDialog(
            title = "Delete ${selected.size} page(s)?",
            text = "This cannot be undone.",
            confirm = "Delete",
            onDismiss = { deletePages = false },
            onConfirm = {
                deletePages = false
                val ids = selected
                stopSelecting()
                Library.deletePages(docId, ids)
            },
        )
    }
    exportPages?.let { pages ->
        ExportDialog(doc, pages, onDismiss = {
            exportPages = null
            stopSelecting()
        })
    }
}
