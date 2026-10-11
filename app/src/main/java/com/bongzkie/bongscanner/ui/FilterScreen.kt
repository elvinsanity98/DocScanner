@file:OptIn(ExperimentalMaterial3Api::class)

package com.bongzkie.bongscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.FilterType
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.imaging.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Pick a document filter and fine-tune brightness / contrast, with a live preview. */
@Composable
fun FilterScreen(nav: Nav, docId: String, pageId: String) {
    val doc = Library.doc(docId)
    val page = doc?.pages?.firstOrNull { it.id == pageId }
    if (doc == null || page == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val context = LocalContext.current

    var base by remember { mutableStateOf<Bitmap?>(null) }
    var thumbs by remember { mutableStateOf<Map<FilterType, ImageBitmap>>(emptyMap()) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var filter by remember { mutableStateOf(page.filter) }
    var brightness by remember { mutableFloatStateOf(page.brightness) }
    var contrast by remember { mutableFloatStateOf(page.contrast) }
    var applyAll by remember { mutableStateOf(false) }

    // Cropped + rotated page at preview size; filters are tried on copies of it.
    LaunchedEffect(pageId) {
        val prepared = withContext(Dispatchers.Default) {
            try {
                ImageOps.decodeFit(Library.origFile(docId, pageId), 1100)?.let { original ->
                    ImageOps.geometry(original, page.corners, page.rotation).also { original.recycle() }
                }
            } catch (e: Throwable) {
                null
            }
        }
        if (prepared == null) {
            toast(context, "The original image of this page is missing")
            nav.pop()
            return@LaunchedEffect
        }
        base = prepared
        thumbs = withContext(Dispatchers.Default) {
            val k = 200f / max(prepared.width, prepared.height)
            val small = Bitmap.createScaledBitmap(
                prepared, max(1, (prepared.width * k).roundToInt()), max(1, (prepared.height * k).roundToInt()), true,
            )
            FilterType.values().associateWith { type ->
                small.copy(Bitmap.Config.ARGB_8888, true).also { ImageOps.applyFilter(it, type) }.asImageBitmap()
            }
        }
    }

    LaunchedEffect(base, filter, brightness, contrast) {
        val source = base ?: return@LaunchedEffect
        delay(50) // lets quick slider movements settle
        preview = withContext(Dispatchers.Default) {
            source.copy(Bitmap.Config.ARGB_8888, true)
                .also { ImageOps.applyFilter(it, filter, brightness, contrast) }
                .asImageBitmap()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Filter") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = {
                    IconButton(
                        enabled = base != null,
                        onClick = {
                            val f = filter
                            val b = brightness
                            val c = contrast
                            val targets = if (applyAll) doc.pages.map { it.id } else listOf(pageId)
                            launchBusy(context, "Applying filter…") { progress ->
                                targets.forEachIndexed { i, id ->
                                    if (targets.size > 1) progress(i / targets.size.toFloat())
                                    Library.editPage(docId, id) { it.copy(filter = f, brightness = b, contrast = c) }
                                }
                                if (nav.current == Screen.Filters(docId, pageId)) nav.pop()
                            }
                        },
                    ) { Icon(Icons.Filled.Check, "Done") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    AdjustRow("Brightness", brightness) { brightness = it }
                    AdjustRow("Contrast", contrast) { contrast = it }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilterType.values().forEach { type ->
                            val chosen = type == filter
                            val shape = RoundedCornerShape(10.dp)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clip(shape).clickable { filter = type }.padding(4.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 64.dp, height = 82.dp)
                                        .clip(shape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(
                                            2.5.dp,
                                            if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape,
                                        ),
                                ) {
                                    thumbs[type]?.let {
                                        Image(
                                            bitmap = it, contentDescription = type.label,
                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    type.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (doc.pages.size > 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { applyAll = !applyAll },
                        ) {
                            Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                            Text("Apply to all ${doc.pages.size} pages", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            val shown = preview
            if (shown == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = shown, contentDescription = "Preview", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AdjustRow(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
        Slider(
            value = value,
            onValueChange = { onChange(if (it > -4f && it < 4f) 0f else it) },
            valueRange = -100f..100f,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onChange(0f) }, enabled = value != 0f) { Text("${value.roundToInt()}") }
    }
}
