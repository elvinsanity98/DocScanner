@file:OptIn(ExperimentalMaterial3Api::class)

package com.bongzkie.bongscanner.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.imaging.EdgeDetector
import com.bongzkie.bongscanner.imaging.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a point of the original picture ends up after [quarterTurns] clockwise turns (normalised). */
private fun toDisplay(x: Float, y: Float, quarterTurns: Int): Offset = when (quarterTurns) {
    1 -> Offset(1f - y, x)
    2 -> Offset(1f - x, 1f - y)
    3 -> Offset(y, 1f - x)
    else -> Offset(x, y)
}

private fun toOriginal(x: Float, y: Float, quarterTurns: Int): Offset = when (quarterTurns) {
    1 -> Offset(y, 1f - x)
    2 -> Offset(1f - x, 1f - y)
    3 -> Offset(1f - y, x)
    else -> Offset(x, y)
}

/** The rectangle the picture occupies when fitted into the canvas with a margin for the handles. */
private fun fitRect(canvasW: Float, canvasH: Float, imageW: Int, imageH: Int, pad: Float): Rect {
    val s = min((canvasW - 2 * pad) / imageW, (canvasH - 2 * pad) / imageH)
    val w = imageW * s
    val h = imageH * s
    val left = (canvasW - w) / 2f
    val top = (canvasH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * Manual crop: drag the four corners (or the edge mid-points) of the page outline.
 * Corners are stored relative to the untouched original, so cropping never loses quality.
 */
@Composable
fun CropScreen(nav: Nav, docId: String, pageId: String, fresh: Boolean) {
    val page = Library.page(docId, pageId)
    if (page == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<Bitmap?>(null) }
    var rotation by remember { mutableIntStateOf(((page.rotation % 360) + 360) % 360) }
    var corners by remember { mutableStateOf(page.corners ?: ImageOps.FULL_FRAME) }
    var active by remember { mutableIntStateOf(-1) }

    LaunchedEffect(pageId) {
        val bitmap = withContext(Dispatchers.IO) {
            try {
                ImageOps.decodeFit(Library.origFile(docId, pageId), 1600)
            } catch (e: Throwable) {
                null
            }
        }
        if (bitmap == null) {
            toast(context, "The original image of this page is missing")
            nav.pop()
            return@LaunchedEffect
        }
        source = bitmap
        // A page that just arrived uncropped gets a first guess of its edges.
        if (fresh && page.corners == null) {
            val found = withContext(Dispatchers.Default) { EdgeDetector.detect(bitmap) }
            if (found != null) corners = found
        }
    }

    val turns = rotation / 90
    val display = remember(source, rotation) {
        source?.let { if (rotation == 0) it else ImageOps.rotate(it, rotation) }?.asImageBitmap()
    }
    val valid = ImageOps.isValidQuad(corners)
    val accent = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crop") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = {
                    IconButton(
                        enabled = source != null,
                        onClick = {
                            if (!valid) {
                                toast(context, "Drag the corners into a proper four-sided shape")
                            } else {
                                val result = corners
                                val turn = rotation
                                launchBusy(context, "Applying…") {
                                    Library.editPage(docId, pageId) {
                                        it.copy(
                                            corners = result.takeUnless { c -> ImageOps.isFullFrame(c) },
                                            rotation = turn,
                                        )
                                    }
                                    if (nav.current == Screen.Crop(docId, pageId, fresh)) nav.pop()
                                }
                            }
                        },
                    ) { Icon(Icons.Filled.Check, "Done") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolButton(Icons.Filled.AutoFixHigh, "Auto detect", enabled = source != null) {
                        val bitmap = source ?: return@ToolButton
                        scope.launch {
                            val found = withContext(Dispatchers.Default) { EdgeDetector.detect(bitmap) }
                            if (found != null) corners = found else toast(context, "No clear page edges found")
                        }
                    }
                    ToolButton(Icons.Filled.CropFree, "Whole image") { corners = ImageOps.FULL_FRAME }
                    ToolButton(Icons.Filled.RotateLeft, "Left") { rotation = (rotation + 270) % 360 }
                    ToolButton(Icons.Filled.RotateRight, "Right") { rotation = (rotation + 90) % 360 }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFF101414)),
            contentAlignment = Alignment.Center,
        ) {
            val image = display
            if (image == null) {
                CircularProgressIndicator()
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(image, turns) {
                            val pad = 28.dp.toPx()
                            val reach = 44.dp.toPx()

                            fun rect() = fitRect(size.width.toFloat(), size.height.toFloat(), image.width, image.height, pad)

                            fun handle(i: Int, r: Rect): Offset {
                                val c = corners
                                fun corner(k: Int): Offset {
                                    val d = toDisplay(c[k * 2], c[k * 2 + 1], turns)
                                    return Offset(r.left + d.x * r.width, r.top + d.y * r.height)
                                }
                                if (i < 4) return corner(i)
                                val a = corner(i - 4)
                                val b = corner((i - 3) % 4)
                                return Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                            }

                            fun moveCorner(list: MutableList<Float>, k: Int, dx: Float, dy: Float) {
                                val d = toDisplay(list[k * 2], list[k * 2 + 1], turns)
                                val o = toOriginal((d.x + dx).coerceIn(0f, 1f), (d.y + dy).coerceIn(0f, 1f), turns)
                                list[k * 2] = o.x
                                list[k * 2 + 1] = o.y
                            }

                            detectDragGestures(
                                onDragStart = { pos ->
                                    val r = rect()
                                    var best = -1
                                    var bestDistance = reach
                                    for (i in 0 until 8) {
                                        val h = handle(i, r)
                                        val distance = hypot(h.x - pos.x, h.y - pos.y)
                                        if (distance < bestDistance) {
                                            bestDistance = distance
                                            best = i
                                        }
                                    }
                                    active = best
                                },
                                onDrag = { change, drag ->
                                    val i = active
                                    if (i >= 0) {
                                        change.consume()
                                        val r = rect()
                                        val dx = drag.x / r.width
                                        val dy = drag.y / r.height
                                        val next = corners.toMutableList()
                                        if (i < 4) {
                                            moveCorner(next, i, dx, dy)
                                        } else {
                                            moveCorner(next, i - 4, dx, dy)
                                            moveCorner(next, (i - 3) % 4, dx, dy)
                                        }
                                        corners = next
                                    }
                                },
                                onDragEnd = { active = -1 },
                                onDragCancel = { active = -1 },
                            )
                        },
                ) {
                    val pad = 28.dp.toPx()
                    val r = fitRect(size.width, size.height, image.width, image.height, pad)
                    val dst = IntOffset(r.left.roundToInt(), r.top.roundToInt())
                    val dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt())
                    drawImage(image, dstOffset = dst, dstSize = dstSize)

                    val c = corners
                    val pts = List(4) { k ->
                        val d = toDisplay(c[k * 2], c[k * 2 + 1], turns)
                        Offset(r.left + d.x * r.width, r.top + d.y * r.height)
                    }
                    val quad = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (k in 1..3) lineTo(pts[k].x, pts[k].y)
                        close()
                    }
                    // Dim everything outside the selected page.
                    val shade = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(0f, 0f, size.width, size.height))
                        addPath(quad)
                    }
                    drawPath(shade, Color.Black.copy(alpha = 0.55f))

                    val line = if (valid) accent else bad
                    drawPath(quad, line, style = Stroke(width = 2.5.dp.toPx()))
                    for (k in 0 until 4) {
                        val a = pts[k]
                        val b = pts[(k + 1) % 4]
                        val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                        drawCircle(Color.White, 7.dp.toPx(), mid)
                        drawCircle(line, 7.dp.toPx(), mid, style = Stroke(width = 2.dp.toPx()))
                    }
                    for (k in 0 until 4) {
                        drawCircle(Color.White, 11.dp.toPx(), pts[k])
                        drawCircle(line, 11.dp.toPx(), pts[k], style = Stroke(width = 3.dp.toPx()))
                    }

                    // Magnifier while a corner is being dragged, kept away from the finger.
                    val i = active
                    if (i in 0..3) {
                        val focus = pts[i]
                        val radius = 58.dp.toPx()
                        val margin = 12.dp.toPx()
                        val onLeft = focus.x > size.width / 2f
                        val center = Offset(
                            if (onLeft) margin + radius else size.width - margin - radius,
                            margin + radius,
                        )
                        val zoom = 2.2f
                        val lens = Path().apply { addOval(Rect(center, radius)) }
                        drawCircle(Color.Black, radius, center)
                        clipPath(lens) {
                            withTransform({
                                translate(center.x - zoom * focus.x, center.y - zoom * focus.y)
                                scale(zoom, zoom, pivot = Offset.Zero)
                            }) {
                                drawImage(image, dstOffset = dst, dstSize = dstSize)
                                drawPath(quad, line, style = Stroke(width = 1.5.dp.toPx()))
                            }
                        }
                        drawCircle(Color.White, radius, center, style = Stroke(width = 2.dp.toPx()))
                        val cross = 10.dp.toPx()
                        drawLine(line, Offset(center.x - cross, center.y), Offset(center.x + cross, center.y), 1.5.dp.toPx())
                        drawLine(line, Offset(center.x, center.y - cross), Offset(center.x, center.y + cross), 1.5.dp.toPx())
                    }
                }
            }
        }
    }
}
