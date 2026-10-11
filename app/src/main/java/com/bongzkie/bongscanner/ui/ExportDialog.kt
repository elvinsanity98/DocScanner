@file:OptIn(ExperimentalMaterial3Api::class)

package com.bongzkie.bongscanner.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.Doc
import com.bongzkie.bongscanner.data.Page
import com.bongzkie.bongscanner.data.Prefs
import com.bongzkie.bongscanner.export.Exporter
import com.bongzkie.bongscanner.export.PageSize
import com.bongzkie.bongscanner.export.PdfRequest

private const val SHARE = 0
private const val SAVE = 1
private const val PRINT = 2

/** Export options for a document (or a selection of its pages): PDF or JPG, then share, save or print. */
@Composable
fun ExportDialog(doc: Doc, pages: List<Page>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val storageGate = rememberStorageGate()

    var asPdf by remember { mutableStateOf(true) }
    var pageSize by remember { mutableStateOf(Prefs.pageSize) }
    var quality by remember { mutableIntStateOf(Prefs.exportQuality) }
    var idLayout by remember { mutableStateOf(doc.idCard) }
    var searchable by remember { mutableStateOf(false) }
    var watermark by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun run(action: Int) {
        Prefs.pageSize = pageSize
        Prefs.exportQuality = quality
        // A printer cannot open a locked file, so printing always uses an unprotected copy.
        val request = PdfRequest(
            pageSize = pageSize,
            quality = quality,
            idCardLayout = idLayout,
            watermark = watermark,
            password = if (action == PRINT) "" else password,
            searchable = searchable && action != PRINT,
        )
        val pdf = asPdf
        val activity = context.findActivity()
        onDismiss()
        launchBusy(context, if (pdf) "Creating PDF…" else "Preparing images…") { progress ->
            if (pdf) {
                val file = Exporter.buildPdf(context, doc, pages, request, progress)
                when (action) {
                    SHARE -> Exporter.share(activity, listOf(file), "application/pdf")
                    SAVE -> {
                        val where = Exporter.savePublic(context, listOf(file), "application/pdf", pictures = false)
                        toast(context, "Saved to $where")
                    }
                    else -> Exporter.print(activity, file, doc.title)
                }
            } else {
                val files = Exporter.buildJpegs(context, doc, pages)
                if (action == SHARE) {
                    Exporter.share(activity, files, "image/jpeg")
                } else {
                    val where = Exporter.savePublic(context, files, "image/jpeg", pictures = true)
                    toast(context, "Saved ${files.size} image(s) to $where")
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Export", maxLines = 1)
                Text(
                    "${doc.title} · ${pages.size} page${if (pages.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = asPdf, onClick = { asPdf = true }, label = { Text("PDF") })
                    FilterChip(selected = !asPdf, onClick = { asPdf = false }, label = { Text("JPG images") })
                }
                if (asPdf) {
                    SectionLabel("Page size")
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PageSize.values().forEach { size ->
                            FilterChip(
                                selected = pageSize == size,
                                enabled = !idLayout,
                                onClick = { pageSize = size },
                                label = { Text(size.label) },
                            )
                        }
                    }
                    SectionLabel("File size")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Original", "Medium", "Small").forEachIndexed { i, name ->
                            FilterChip(selected = quality == i, onClick = { quality = i }, label = { Text(name) })
                        }
                    }
                    SwitchRow("Searchable text (OCR)", "Hidden text layer: search and copy inside the PDF", searchable) {
                        searchable = it
                    }
                    SwitchRow("ID card layout", "Two sides per A4 sheet", idLayout) { idLayout = it }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = watermark,
                        onValueChange = { watermark = it.take(40) },
                        label = { Text("Watermark (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { v -> password = v.filter { it.code in 33..126 }.take(32) },
                        label = { Text("Password lock (optional)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (asPdf) TextButton(onClick = { run(PRINT) }) { Text("Print") }
                TextButton(onClick = { storageGate { run(SAVE) } }) { Text("Save") }
                TextButton(onClick = { run(SHARE) }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
