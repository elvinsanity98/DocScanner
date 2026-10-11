package com.bongzkie.bongscanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bongzkie.bongscanner.export.Exporter
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import kotlin.math.min

/** Live QR code / barcode reader. */
@Composable
fun QrScreen(nav: Nav) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    var result by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose {}
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val scanner = BarcodeScanning.getClient()
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || result != null) {
                        proxy.close()
                    } else {
                        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes ->
                                val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }
                                if (value != null && result == null) result = value
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                }
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                toast(context, "The camera could not be started")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
            executor.shutdown()
            scanner.close()
        }
    }

    LaunchedEffect(torch, camera) { runCatching { camera?.cameraControl?.enableTorch(torch) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                val side = min(size.width, size.height) * 0.68f
                val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
                drawRoundRect(
                    color = ScanAmber,
                    topLeft = topLeft,
                    size = Size(side, side),
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
            Text(
                "Point the camera at a QR code or barcode",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(32.dp),
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            ) {
                Text("BongScanner needs the camera to read codes.", color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Text(
                "QR / Barcode",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = { torch = !torch }) {
                Icon(
                    if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                    "Flashlight",
                    tint = if (torch) ScanAmber else Color.White,
                )
            }
        }
    }

    result?.let { value ->
        val isLink = value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(if (isLink) "Link found" else "Code found") },
            text = {
                SelectionContainer {
                    Text(value, modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(value))
                        toast(context, "Copied")
                    }) { Text("Copy") }
                    TextButton(onClick = { Exporter.shareText(context, value) }) { Text("Share") }
                    if (isLink) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value)))
                            } catch (e: Exception) {
                                toast(context, "No app can open this link")
                            }
                        }) { Text("Open") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { result = null }) { Text("Scan again") } },
        )
    }
}
