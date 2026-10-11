package com.bongzkie.bongscanner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Page
import com.bongzkie.bongscanner.export.Exporter
import com.bongzkie.bongscanner.imaging.rememberBitmap
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------- navigation

enum class CameraMode { SINGLE, BATCH, ID_CARD }

sealed interface Screen {
    data object Home : Screen
    data class Document(val docId: String) : Screen
    data class Viewer(val docId: String, val index: Int) : Screen
    data class Crop(val docId: String, val pageId: String, val fresh: Boolean = false) : Screen
    data class Filters(val docId: String, val pageId: String) : Screen
    data class Annotate(val docId: String, val pageId: String) : Screen

    /** Recognised text of one page, or of the whole document when [pageId] is null. */
    data class TextResult(val docId: String, val pageId: String?) : Screen
    data class Camera(val docId: String?, val mode: CameraMode, val folderId: String? = null) : Screen
    data object Qr : Screen
    data object Settings : Screen
}

/** A plain back stack; the screen on top is the one shown. */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Lands on the document (reusing it when it is already open), optionally with [then] on top. */
    fun openDocument(docId: String, then: Screen? = null, dropCurrent: Boolean = false) {
        if (dropCurrent && stack.size > 1) stack.removeAt(stack.lastIndex)
        val at = stack.indexOfFirst { it is Screen.Document && it.docId == docId }
        if (at >= 0) {
            while (stack.lastIndex > at) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(Screen.Document(docId))
        }
        if (then != null) stack.add(then)
    }
}

// ---------------------------------------------------------------- background work

/** Outlives individual screens so an operation finishes even if the user navigates away. */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/** Drives the blocking progress dialog. */
object Busy {
    var message by mutableStateOf<String?>(null)
    var progress by mutableFloatStateOf(-1f)
}

fun toast(context: Context, text: String) {
    Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
}

fun friendly(e: Throwable): String = when (e) {
    is OutOfMemoryError -> "Not enough memory for this image"
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong"
}

/** Runs [block] behind the progress dialog; failures are shown as a toast instead of crashing. */
fun launchBusy(context: Context, message: String, block: suspend (progress: (Float) -> Unit) -> Unit): Job =
    appScope.launch {
        Busy.progress = -1f
        Busy.message = message
        try {
            block { Busy.progress = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("BongScanner", message, e)
            toast(context, friendly(e))
            Library.pruneEmpty()
        } finally {
            Busy.message = null
        }
    }

@Composable
fun BusyOverlay() {
    val message = Busy.message ?: return
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.padding(24.dp).widthIn(min = 220.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val p = Busy.progress
                if (p in 0f..1f) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                } else {
                    CircularProgressIndicator()
                }
                Spacer(Modifier.height(16.dp))
                Text(message, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

fun Context.findActivity(): Activity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    error("No activity")
}

fun formatDate(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time))

fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024f * 1024f))
    else -> "${bytes / 1024} KB"
}

/** The rendered image of a page, loaded lazily. */
@Composable
fun PageThumb(
    docId: String,
    page: Page,
    maxSide: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
) {
    val bitmap = rememberBitmap(Library.pageFile(docId, page.id), page.stamp, maxSide)
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Icon with a caption underneath, used in the bottom tool bars. */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = LocalContentColor.current,
    onClick: () -> Unit,
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .widthIn(min = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = value.text.isNotBlank(), onClick = { onConfirm(value.text.trim()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FolderPickerDialog(current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextPromptDialog(
            title = "New folder",
            label = "Folder name",
            confirm = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                Library.addFolder(name)?.let { onPick(it.id) }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to folder") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PickerRow(Icons.Filled.FolderOff, "No folder", current == null) { onPick(null) }
                Library.folders.forEach { folder ->
                    PickerRow(Icons.Filled.Folder, folder.name, current == folder.id) { onPick(folder.id) }
                }
                PickerRow(Icons.Filled.CreateNewFolder, "New folder…", false) { creating = true }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun PickerRow(icon: ImageVector?, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
        }
        Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

/** A list of options with a check mark on the current one. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    current: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option -> PickerRow(null, label(option), option == current) { onPick(option) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- shared launchers

/**
 * Google's Smart Scan screen: live edge detection, auto capture, crop and clean-up.
 * [onUnavailable] fires on devices without Google Play services so the caller can fall back.
 */
@Composable
fun rememberSmartScan(onResult: (List<Uri>) -> Unit, onUnavailable: () -> Unit): () -> Unit {
    val activity = LocalContext.current.findActivity()
    val unavailable by rememberUpdatedState(onUnavailable)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                ?.pages?.map { it.imageUri } ?: emptyList()
            if (uris.isNotEmpty()) onResult(uris)
        }
    }
    return remember(activity, launcher) {
        {
            try {
                val options = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
                    .addOnSuccessListener { sender ->
                        try {
                            launcher.launch(IntentSenderRequest.Builder(sender).build())
                        } catch (e: Exception) {
                            unavailable()
                        }
                    }
                    .addOnFailureListener { unavailable() }
            } catch (e: Exception) {
                unavailable()
            }
        }
    }
}

/**
 * Returns a function that runs an action once writing to shared storage is allowed
 * (asks for the storage permission on Android 9 and older; newer versions need none).
 */
@Composable
fun rememberStorageGate(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pending
        pending = null
        if (granted) action?.invoke() else toast(context, "Storage permission is needed to save files")
    }
    return { action ->
        if (Exporter.needsStoragePermission(context)) {
            pending = action
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }
}
