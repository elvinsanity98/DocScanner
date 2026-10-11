@file:OptIn(ExperimentalMaterial3Api::class)

package com.bongzkie.bongscanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.BuildInfo
import com.bongzkie.bongscanner.data.FilterType
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Prefs
import com.bongzkie.bongscanner.export.PageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val themeNames = listOf("Follow system", "Light", "Dark")
private val qualityNames = listOf("Original", "Medium", "Small")

@Composable
fun SettingsScreen(nav: Nav) {
    var dialog by remember { mutableIntStateOf(0) } // 1 theme, 2 filter, 3 page size, 4 quality
    var storage by remember { mutableStateOf<Long?>(null) }
    val docCount = Library.docs.size
    LaunchedEffect(docCount) { storage = withContext(Dispatchers.IO) { Library.storageBytes() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Header("Scanning")
            ToggleItem(
                "Smart Scan",
                "Use Google's scanner with live edge detection and auto capture for the Scan button. " +
                    "Turn off to always use the built-in camera.",
                Prefs.smartScan,
            ) { Prefs.smartScan = it }
            ToggleItem(
                "Auto crop",
                "Detect the page edges automatically in batch and ID card mode of the built-in camera.",
                Prefs.autoCrop,
            ) { Prefs.autoCrop = it }
            ValueItem("Default filter", "Applied to built-in camera scans: ${Prefs.defaultFilter.label}") { dialog = 2 }

            Header("Export")
            ValueItem("PDF page size", Prefs.pageSize.label) { dialog = 3 }
            ValueItem("PDF file size", qualityNames[Prefs.exportQuality.coerceIn(0, 2)]) { dialog = 4 }

            Header("Appearance")
            ValueItem("Theme", themeNames[Prefs.themeMode.coerceIn(0, 2)]) { dialog = 1 }

            Header("Storage")
            ValueItem(
                "Documents on this device",
                "$docCount document${if (docCount == 1) "" else "s"} · ${storage?.let { formatSize(it) } ?: "…"}",
            ) {}

            Header("About")
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(BrandTeal),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DocumentScanner, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text("BongScanner", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Version ${BuildInfo.VERSION}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Developed by Bongzkie",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Text(
                "Your scans stay on this device. Text recognition and filters run offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    when (dialog) {
        1 -> ChoiceDialog(
            "Theme", listOf(0, 1, 2), Prefs.themeMode, { themeNames[it] },
            onDismiss = { dialog = 0 },
        ) {
            Prefs.themeMode = it
            dialog = 0
        }

        2 -> ChoiceDialog(
            "Default filter", FilterType.values().toList(), Prefs.defaultFilter, { it.label },
            onDismiss = { dialog = 0 },
        ) {
            Prefs.defaultFilter = it
            dialog = 0
        }

        3 -> ChoiceDialog(
            "PDF page size", PageSize.values().toList(), Prefs.pageSize, { it.label },
            onDismiss = { dialog = 0 },
        ) {
            Prefs.pageSize = it
            dialog = 0
        }

        4 -> ChoiceDialog(
            "PDF file size", listOf(0, 1, 2), Prefs.exportQuality, { qualityNames[it] },
            onDismiss = { dialog = 0 },
        ) {
            Prefs.exportQuality = it
            dialog = 0
        }
    }
}

@Composable
private fun Header(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun ValueItem(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ToggleItem(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
