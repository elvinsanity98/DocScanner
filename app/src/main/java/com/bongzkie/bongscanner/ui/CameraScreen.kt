package com.bongzkie.bongscanner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.data.Prefs
import com.bongzkie.bongscanner.imaging.EdgeDetector
import com.bongzkie.bongscanner.imaging.ImageOps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

private const val FLASH_OFF = 0
private const val FLASH_AUTO = 1
private const val FLASH_ON = 2
private const val FLASH_TORCH = 3

private val fourByThree: ResolutionSelector = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()

/**
 * Built-in camera. Single mode goes to the crop screen after each shot, batch mode keeps shooting,
 * ID card mode takes the front and the back and then finishes by itself.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScreen(nav: Nav, startDocId: String?, startMode: CameraMode, folderId: String?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    var mode by remember { mutableStateOf(startMode) }
    var docId by remember { mutableStateOf(startDocId) }
    var captured by remember { mutableIntStateOf(0) }
    var pending by remember { mutableIntStateOf(0) }
    var lastThumb by remember { mutableStateOf<ImageBitmap?>(null) }
    var flash by remember { mutableIntStateOf(FLASH_OFF) }
    var grid by remember { mutableStateOf(true) }
    var shooting by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val queue = remember { Mutex() }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(fourByThree)
            .build()
    }

    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose {}
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().setResolutionSelector(fourByThree).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                p.unbindAll()
                camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                toast(context, "The camera could not be started")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbindAll()
        }
    }

    // Keeps saved photos upright even though the screen itself stays in portrait.
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(flash, camera) {
        imageCapture.flashMode = when (flash) {
            FLASH_AUTO -> ImageCapture.FLASH_MODE_AUTO
            FLASH_ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        runCatching { camera?.cameraControl?.enableTorch(flash == FLASH_TORCH) }
    }

    // Tap to focus, pinch to zoom.
    DisposableEffect(camera) {
        val cam = camera
        if (cam != null) {
            val pinch = ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        val ratio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                        cam.cameraControl.setZoomRatio(ratio * detector.scaleFactor)
                        return true
                    }
                },
            )
            previewView.setOnTouchListener { _, event ->
                pinch.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP && !pinch.isInProgress) {
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    runCatching { cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build()) }
                }
                true
            }
        }
        onDispose { previewView.setOnTouchListener(null) }
    }

    fun finish() {
        val id = docId
        if (nav.current !is Screen.Camera) return
        if (id != null && Library.doc(id) != null) nav.openDocument(id, dropCurrent = true) else nav.pop()
    }

    fun process(file: File) {
        val shotMode = mode
        val single = shotMode == CameraMode.SINGLE
        pending++
        appScope.launch {
            if (single) {
                Busy.progress = -1f
                Busy.message = "Processing…"
            }
            var locked = false
            try {
                // One shot at a time, so batch pages are stored in the order they were taken.
                queue.lock()
                locked = true
                val bitmap = withContext(Dispatchers.Default) { ImageOps.decodeUri(context, Uri.fromFile(file)) }
                    ?: throw IOException("Could not read the photo")
                try {
                    val corners = if (single || Prefs.autoCrop) {
                        withContext(Dispatchers.Default) { EdgeDetector.detect(bitmap) }
                    } else {
                        null
                    }
                    val id = docId?.takeIf { Library.doc(it) != null }
                        ?: Library.createDoc(folderId = folderId, idCard = shotMode == CameraMode.ID_CARD).id
                    docId = id
                    val page = Library.addPage(id, bitmap, corners, Prefs.defaultFilter)
                    val k = 160f / max(bitmap.width, bitmap.height)
                    lastThumb = Bitmap.createScaledBitmap(
                        bitmap, max(1, (bitmap.width * k).toInt()), max(1, (bitmap.height * k).toInt()), true,
                    ).asImageBitmap()
                    captured++
                    if (nav.current is Screen.Camera) {
                        if (single) {
                            nav.openDocument(id, then = Screen.Crop(id, page.id, fresh = true), dropCurrent = true)
                        } else if (shotMode == CameraMode.ID_CARD && captured >= 2) {
                            nav.openDocument(id, dropCurrent = true)
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(context, friendly(e))
                Library.pruneEmpty()
            } finally {
                if (locked) queue.unlock()
                file.delete()
                pending--
                if (single) Busy.message = null
            }
        }
    }

    fun shoot() {
        if (shooting || camera == null) return
        shooting = true
        val file = File(context.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    shooting = false
                    process(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    shooting = false
                    file.delete()
                    toast(context, "Capture failed. Please try again.")
                }
            },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            Text(
                when (mode) {
                    CameraMode.ID_CARD -> if (captured == 0) "ID card · front side" else "ID card · back side"
                    CameraMode.BATCH -> "Batch scan"
                    CameraMode.SINGLE -> "Single scan"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = { flash = (flash + 1) % 4 }) {
                Icon(
                    when (flash) {
                        FLASH_AUTO -> Icons.Filled.FlashAuto
                        FLASH_ON -> Icons.Filled.FlashOn
                        FLASH_TORCH -> Icons.Filled.FlashlightOn
                        else -> Icons.Filled.FlashOff
                    },
                    "Flash",
                    tint = if (flash == FLASH_OFF) Color.White else ScanAmber,
                )
            }
            IconButton(onClick = { grid = !grid }) {
                Icon(if (grid) Icons.Filled.GridOn else Icons.Filled.GridOff, "Grid", tint = Color.White)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (granted) {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    if (grid) {
                        Canvas(Modifier.fillMaxSize()) {
                            val line = Color.White.copy(alpha = 0.35f)
                            for (i in 1..2) {
                                val x = size.width * i / 3f
                                val y = size.height * i / 3f
                                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                                drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                            }
                        }
                    }
                    if (shooting) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Text(
                        "BongScanner needs the camera to scan documents.",
                        color = Color.White, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
        }

        if (mode != CameraMode.ID_CARD) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                ModeChip("Single", mode == CameraMode.SINGLE, enabled = captured == 0) { mode = CameraMode.SINGLE }
                Spacer(Modifier.size(8.dp))
                ModeChip("Batch", mode == CameraMode.BATCH, enabled = true) { mode = CameraMode.BATCH }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // last shot + counter
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                val thumb = lastThumb
                if (thumb != null) {
                    Image(
                        bitmap = thumb, contentDescription = "Last capture", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White, RoundedCornerShape(8.dp)),
                    )
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$captured", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (pending > 0) CircularProgressIndicator(Modifier.size(28.dp), color = ScanAmber, strokeWidth = 3.dp)
            }

            // shutter
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (shooting || !granted) Color.Gray else Color.White)
                    .clickable(enabled = granted && !shooting) { shoot() },
            )

            // done
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                if (mode != CameraMode.SINGLE && captured > 0) {
                    val ready = pending == 0
                    Box(
                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                            .background(if (ready) MaterialTheme.colorScheme.primary else Color.DarkGray)
                            .clickable(enabled = ready) { finish() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, "Done", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = when {
            selected -> Color.Black
            enabled -> Color.White
            else -> Color.Gray
        },
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) ScanAmber else Color.Transparent)
            .border(1.dp, if (selected) ScanAmber else Color.Gray, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
