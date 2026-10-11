@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.bongzkie.bongscanner.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.Doc
import com.bongzkie.bongscanner.data.Folder
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Prefs
import com.bongzkie.bongscanner.data.SortMode
import com.bongzkie.bongscanner.export.Importer

private const val STARRED = "*starred"

private enum class DocAction { RENAME, EXPORT, MOVE, STAR, DELETE }

@Composable
fun HomeScreen(nav: Nav) {
    val context = LocalContext.current

    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var folderFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    var renameDoc by remember { mutableStateOf<Doc?>(null) }
    var exportDoc by remember { mutableStateOf<Doc?>(null) }
    var moveIds by remember { mutableStateOf<Set<String>?>(null) }
    var deleteIds by remember { mutableStateOf<Set<String>?>(null) }
    var mergeAsk by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<Folder?>(null) }
    var deleteFolder by remember { mutableStateOf<Folder?>(null) }

    val docs = Library.docs
    val folders = Library.folders
    val sort = Prefs.sortMode

    // A folder that was deleted elsewhere must not stay selected as the filter.
    LaunchedEffect(folders, folderFilter) {
        val f = folderFilter
        if (f != null && f != STARRED && folders.none { it.id == f }) folderFilter = null
    }

    val visible = remember(docs, query, folderFilter, sort) {
        val comparator: Comparator<Doc> = when (sort) {
            SortMode.MODIFIED -> compareByDescending { it.modified }
            SortMode.CREATED -> compareByDescending { it.created }
            SortMode.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
            SortMode.PAGES -> compareByDescending { it.pages.size }
        }
        docs.filter { d ->
            val inFolder = when (folderFilter) {
                null -> true
                STARRED -> d.starred
                else -> d.folderId == folderFilter
            }
            inFolder && Library.matches(d, query)
        }.sortedWith(comparator)
    }

    val targetFolder = folderFilter?.takeIf { it != STARRED }

    fun openCamera(mode: CameraMode) = nav.push(Screen.Camera(null, mode, targetFolder))

    val smartScan = rememberSmartScan(
        onResult = { uris ->
            launchBusy(context, "Saving scans…") { progress ->
                val id = Importer.importImages(context, uris, null, progress = progress)
                if (id == null) {
                    toast(context, "Could not read the scanned pages")
                } else {
                    if (targetFolder != null) Library.moveToFolder(setOf(id), targetFolder)
                    nav.openDocument(id)
                }
            }
        },
        onUnavailable = {
            toast(context, "Smart Scan is not available here, using the built-in camera")
            openCamera(CameraMode.SINGLE)
        },
    )

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            launchBusy(context, "Importing…") { progress ->
                val id = Importer.importImages(context, uris, null, progress = progress)
                val doc = id?.let { Library.doc(it) }
                if (doc == null) {
                    toast(context, "Could not read the selected images")
                } else {
                    if (targetFolder != null) Library.moveToFolder(setOf(doc.id), targetFolder)
                    // A single picture goes straight to the crop screen, like a fresh capture.
                    val crop = if (doc.pages.size == 1) Screen.Crop(doc.id, doc.pages[0].id, fresh = true) else null
                    nav.openDocument(doc.id, then = crop)
                }
            }
        }
    }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            launchBusy(context, "Importing PDF…") { progress ->
                val id = Importer.importPdf(context, uri, progress)
                if (targetFolder != null) Library.moveToFolder(setOf(id), targetFolder)
                nav.openDocument(id)
            }
        }
    }

    BackHandler(enabled = selected.isNotEmpty() || searching) {
        if (selected.isNotEmpty()) {
            selected = emptySet()
        } else {
            searching = false
            query = ""
        }
    }

    Scaffold(
        topBar = {
            when {
                selected.isNotEmpty() -> TopAppBar(
                    title = { Text("${selected.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, "Cancel selection") }
                    },
                    actions = {
                        IconButton(onClick = { selected = visible.map { it.id }.toSet() }) {
                            Icon(Icons.Filled.SelectAll, "Select all")
                        }
                        IconButton(onClick = { moveIds = selected }) { Icon(Icons.Filled.Folder, "Move to folder") }
                        IconButton(onClick = { deleteIds = selected }) { Icon(Icons.Filled.Delete, "Delete") }
                        var more by remember { mutableStateOf(false) }
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(
                                text = { Text("Merge into one document") },
                                enabled = selected.size >= 2,
                                onClick = {
                                    more = false
                                    mergeAsk = true
                                },
                            )
                        }
                    },
                )

                searching -> TopAppBar(
                    title = {
                        val focus = remember { FocusRequester() }
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search titles and scanned text") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            searching = false
                            query = ""
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                        }
                    },
                )

                else -> TopAppBar(
                    title = {
                        Column {
                            Text("BongScanner", fontWeight = FontWeight.Bold)
                            Text(
                                "by Bongzkie",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        IconButton(onClick = { Prefs.gridView = !Prefs.gridView }) {
                            Icon(
                                if (Prefs.gridView) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                                "Change layout",
                            )
                        }
                        var more by remember { mutableStateOf(false) }
                        IconButton(onClick = { more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            SortMode.values().forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text("Sort: ${mode.label}") },
                                    trailingIcon = {
                                        if (mode == sort) Icon(Icons.Filled.CheckCircle, null)
                                    },
                                    onClick = {
                                        more = false
                                        Prefs.sortMode = mode
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = {
                                    more = false
                                    nav.push(Screen.Settings)
                                },
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { if (Prefs.smartScan) smartScan() else openCamera(CameraMode.SINGLE) },
                    icon = { Icon(Icons.Filled.DocumentScanner, null) },
                    text = { Text("Scan") },
                )
            }
        },
    ) { padding ->
        val grid = Prefs.gridView
        LazyVerticalGrid(
            columns = if (grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (!searching && selected.isEmpty()) {
                item(key = "#tools", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ToolTile(Icons.Filled.DocumentScanner, "Smart Scan") { smartScan() }
                        ToolTile(Icons.Filled.CameraAlt, "Camera") { openCamera(CameraMode.SINGLE) }
                        ToolTile(Icons.Filled.Badge, "ID Card") { openCamera(CameraMode.ID_CARD) }
                        ToolTile(Icons.Filled.PhotoLibrary, "Gallery") {
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        ToolTile(Icons.Filled.PictureAsPdf, "Import PDF") { pickPdf.launch(arrayOf("application/pdf")) }
                        ToolTile(Icons.Filled.QrCodeScanner, "QR Code") { nav.push(Screen.Qr) }
                    }
                }
            }
            item(key = "#folders", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = folderFilter == null,
                        onClick = { folderFilter = null },
                        label = { Text("All (${docs.size})") },
                    )
                    FilterChip(
                        selected = folderFilter == STARRED,
                        onClick = { folderFilter = STARRED },
                        label = { Text("Starred") },
                        leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) },
                    )
                    folders.forEach { folder ->
                        val active = folderFilter == folder.id
                        var menu by remember(folder.id) { mutableStateOf(false) }
                        Box {
                            FilterChip(
                                selected = active,
                                onClick = { folderFilter = folder.id },
                                label = { Text(folder.name, maxLines = 1) },
                                leadingIcon = { Icon(Icons.Filled.Folder, null, Modifier.size(18.dp)) },
                                trailingIcon = if (active) {
                                    {
                                        Icon(
                                            Icons.Filled.MoreVert, "Folder options",
                                            Modifier.size(18.dp).clickable { menu = true },
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename folder") }, onClick = {
                                    menu = false
                                    renameFolder = folder
                                })
                                DropdownMenuItem(text = { Text("Delete folder") }, onClick = {
                                    menu = false
                                    deleteFolder = folder
                                })
                            }
                        }
                    }
                    FilterChip(
                        selected = false,
                        onClick = { newFolder = true },
                        label = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
                    )
                }
            }

            if (visible.isEmpty()) {
                item(key = "#empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        if (docs.isEmpty()) {
                            "No documents yet.\nTap Scan to capture your first page."
                        } else {
                            "Nothing matches here."
                        }
                    )
                }
            }

            items(visible, key = { it.id }) { doc ->
                val isSelected = doc.id in selected
                DocItem(
                    doc = doc,
                    grid = grid,
                    selected = isSelected,
                    selecting = selected.isNotEmpty(),
                    onClick = {
                        if (selected.isNotEmpty()) {
                            selected = if (isSelected) selected - doc.id else selected + doc.id
                        } else {
                            nav.push(Screen.Document(doc.id))
                        }
                    },
                    onLongClick = { selected = if (isSelected) selected - doc.id else selected + doc.id },
                    onAction = { action ->
                        when (action) {
                            DocAction.RENAME -> renameDoc = doc
                            DocAction.EXPORT -> exportDoc = doc
                            DocAction.MOVE -> moveIds = setOf(doc.id)
                            DocAction.STAR -> Library.setStarred(doc.id, !doc.starred)
                            DocAction.DELETE -> deleteIds = setOf(doc.id)
                        }
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------ dialogs

    renameDoc?.let { doc ->
        TextPromptDialog(
            title = "Rename document", label = "Title", initial = doc.title, confirm = "Rename",
            onDismiss = { renameDoc = null },
            onConfirm = {
                Library.rename(doc.id, it)
                renameDoc = null
            },
        )
    }
    exportDoc?.let { doc -> ExportDialog(doc, doc.pages, onDismiss = { exportDoc = null }) }
    moveIds?.let { ids ->
        val current = if (ids.size == 1) Library.doc(ids.first())?.folderId else null
        FolderPickerDialog(
            current = current,
            onDismiss = { moveIds = null },
            onPick = { folderId ->
                Library.moveToFolder(ids, folderId)
                moveIds = null
                selected = emptySet()
            },
        )
    }
    deleteIds?.let { ids ->
        ConfirmDialog(
            title = if (ids.size == 1) "Delete document?" else "Delete ${ids.size} documents?",
            text = "All pages will be removed from this device. This cannot be undone.",
            confirm = "Delete",
            onDismiss = { deleteIds = null },
            onConfirm = {
                Library.deleteDocs(ids)
                deleteIds = null
                selected = emptySet()
            },
        )
    }
    if (mergeAsk) {
        val ordered = visible.filter { it.id in selected }
        ConfirmDialog(
            title = "Merge ${ordered.size} documents?",
            text = "All pages are added to \"${ordered.firstOrNull()?.title.orEmpty()}\" in the order shown.",
            confirm = "Merge",
            onDismiss = { mergeAsk = false },
            onConfirm = {
                mergeAsk = false
                selected = emptySet()
                Library.mergeDocs(ordered.map { it.id })?.let { nav.openDocument(it) }
            },
        )
    }
    if (newFolder) {
        TextPromptDialog(
            title = "New folder", label = "Folder name", confirm = "Create",
            onDismiss = { newFolder = false },
            onConfirm = { name ->
                newFolder = false
                Library.addFolder(name)?.let { folderFilter = it.id }
            },
        )
    }
    renameFolder?.let { folder ->
        TextPromptDialog(
            title = "Rename folder", label = "Folder name", initial = folder.name, confirm = "Rename",
            onDismiss = { renameFolder = null },
            onConfirm = {
                Library.renameFolder(folder.id, it)
                renameFolder = null
            },
        )
    }
    deleteFolder?.let { folder ->
        ConfirmDialog(
            title = "Delete folder \"${folder.name}\"?",
            text = "The documents inside are kept and move back to All.",
            confirm = "Delete folder",
            onDismiss = { deleteFolder = null },
            onConfirm = {
                Library.deleteFolder(folder.id)
                deleteFolder = null
                folderFilter = null
            },
        )
    }
}

@Composable
private fun ToolTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .width(72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Filled.DocumentScanner,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DocItem(
    doc: Doc,
    grid: Boolean,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAction: (DocAction) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val outline = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val subtitle = "${formatDate(doc.modified)} · ${doc.pages.size} page${if (doc.pages.size == 1) "" else "s"}"
    val first = doc.pages.firstOrNull()

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, outline, shape)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (grid) {
            Box {
                if (first != null) {
                    PageThumb(
                        doc.id, first, 360,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.82f),
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().aspectRatio(0.82f).background(MaterialTheme.colorScheme.surfaceVariant))
                }
                if (doc.starred) {
                    Icon(
                        Icons.Filled.Star, "Starred", tint = ScanAmber,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(20.dp),
                    )
                }
                if (selecting) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                    Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DocMenu(doc, onAction)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                Box {
                    if (first != null) {
                        PageThumb(
                            doc.id, first, 200,
                            modifier = Modifier.size(width = 60.dp, height = 78.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                        )
                    } else {
                        Box(
                            Modifier.size(width = 60.dp, height = 78.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                    if (selecting) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(2.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (doc.starred) {
                            Icon(Icons.Filled.Star, "Starred", tint = ScanAmber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DocMenu(doc, onAction)
            }
        }
    }
}

@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f))
            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle, "Selected",
                tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun DocMenu(doc: Doc, onAction: (DocAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Document options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: DocAction) {
                open = false
                onAction(action)
            }
            DropdownMenuItem(text = { Text("Export / share") }, onClick = { pick(DocAction.EXPORT) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { pick(DocAction.RENAME) })
            DropdownMenuItem(text = { Text("Move to folder") }, onClick = { pick(DocAction.MOVE) })
            DropdownMenuItem(
                text = { Text(if (doc.starred) "Remove star" else "Add star") },
                onClick = { pick(DocAction.STAR) },
            )
            DropdownMenuItem(text = { Text("Delete") }, onClick = { pick(DocAction.DELETE) })
        }
    }
}
