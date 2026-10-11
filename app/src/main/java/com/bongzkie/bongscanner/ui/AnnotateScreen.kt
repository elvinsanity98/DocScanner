@file:OptIn(ExperimentalMaterial3Api::class)

package com.bongzkie.bongscanner.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bongzkie.bongscanner.data.Library
import com.bongzkie.bongscanner.imaging.ImageOps
import com.bongzkie.bongscanner.imaging.rememberBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt

/** One pen stroke; points and width are relative to the page so they scale to any resolution. */
private class Ink(val points: List<Offset>, val color: Color, val width: Float)

private class Pen(val name: String, val color: Color, val widthFactor: Float)

private val pens = listOf(
    Pen("Black", Color(0xFF111111), 1f),
    Pen("Blue", Color(0xFF0D47A1), 1f),
    Pen("Red", Color(0xFFD32F2F), 1f),
    Pen("Green", Color(0xFF2E7D32), 1f),
    Pen("Highlighter", Color(0x73FFEB3B), 5f),
)

private fun fitInside(canvasW: Float, canvasH: Float, imageW: Int, imageH: Int, pad: Float): Rect {
    val s = min((canvasW - 2 * pad) / imageW, (canvasH - 2 * pad) / imageH)
    val w = imageW * s
    val h = imageH * s
    val left = (canvasW - w) / 2f
    val top = (canvasH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** Smooth curve through the points: straight to the first mid-point, then quadratic segments. */
private inline fun tracePath(
    points: List<Offset>,
    moveTo: (Float, Float) -> Unit,
    lineTo: (Float, Float) -> Unit,
    quadTo: (Float, Float, Float, Float) -> Unit,
) {
    if (points.isEmpty()) return
    moveTo(points[0].x, points[0].y)
    if (points.size == 1) {
        lineTo(points[0].x + 0.01f, points[0].y + 0.01f)
        return
    }
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        quadTo(prev.x, prev.y, (prev.x + cur.x) / 2f, (prev.y + cur.y) / 2f)
    }
    lineTo(points.last().x, points.last().y)
}

/** Sign or mark up a page with a finger. Saving bakes the ink into the page. */
@Composable
fun AnnotateScreen(nav: Nav, docId: String, pageId: String) {
    val page = Library.page(docId, pageId)
    if (page == null) {
        LaunchedEffect(Unit) { nav.pop() }
        return
    }
    val context = LocalContext.current
    val image = rememberBitmap(Library.pageFile(docId, pageId), page.stamp, 1400)

    val strokes = remember { mutableStateListOf<Ink>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var penIndex by remember { mutableIntStateOf(1) }
    var thickness by remember { mutableFloatStateOf(0.005f) }
    val pen = pens[penIndex]

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign / Draw") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.Filled.Close, "Cancel") } },
                actions = {
                    IconButton(enabled = strokes.isNotEmpty(), onClick = { strokes.removeAt(strokes.lastIndex) }) {
                        Icon(Icons.Filled.Undo, "Undo")
                    }
                    IconButton(enabled = strokes.isNotEmpty(), onClick = { strokes.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, "Clear all")
                    }
                    IconButton(
                        enabled = strokes.isNotEmpty(),
                        onClick = {
                            val ink = strokes.toList()
                            launchBusy(context, "Saving…") {
                                val merged = withContext(Dispatchers.Default) {
                                    val decoded = ImageOps.decodeFile(
                                        Library.pageFile(docId, pageId), ImageOps.MAX_SIDE, mutable = true,
                                    ) ?: throw IOException("Page image is missing")
                                    val target = if (decoded.isMutable) {
                                        decoded
                                    } else {
                                        decoded.copy(Bitmap.Config.ARGB_8888, true).also { decoded.recycle() }
                                    }
                                    val canvas = android.graphics.Canvas(target)
                                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                        style = Paint.Style.STROKE
                                        strokeCap = Paint.Cap.ROUND
                                        strokeJoin = Paint.Join.ROUND
                                    }
                                    val w = target.width.toFloat()
                                    val h = target.height.toFloat()
                                    for (s in ink) {
                                        paint.color = s.color.toArgb()
                                        paint.strokeWidth = s.width * w
                                        val path = android.graphics.Path()
                                        tracePath(
                                            s.points.map { Offset(it.x * w, it.y * h) },
                                            moveTo = { x, y -> path.moveTo(x, y) },
                                            lineTo = { x, y -> path.lineTo(x, y) },
                                            quadTo = { a, b, c, d -> path.quadTo(a, b, c, d) },
                                        )
                                        canvas.drawPath(path, paint)
                                    }
                                    target
                                }
                                try {
                                    Library.flattenPage(docId, pageId, merged)
                                } finally {
                                    merged.recycle()
                                }
                                if (nav.current == Screen.Annotate(docId, pageId)) nav.pop()
                            }
                        },
                    ) { Icon(Icons.Filled.Check, "Save") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        pens.forEachIndexed { i, p ->
                            val chosen = i == penIndex
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .border(
                                        3.dp,
                                        if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        CircleShape,
                                    )
                                    .padding(6.dp)
                                    .clip(CircleShape)
                                    .background(p.color.copy(alpha = 1f))
                                    .clickable { penIndex = i },
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pen.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
                        Slider(
                            value = thickness,
                            onValueChange = { thickness = it },
                            valueRange = 0.002f..0.014f,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                CircularProgressIndicator()
            } else {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(image) {
                            val pad = 8.dp.toPx()
                            fun normalise(p: Offset): Offset {
                                val r = fitInside(size.width.toFloat(), size.height.toFloat(), image.width, image.height, pad)
                                return Offset(
                                    ((p.x - r.left) / r.width).coerceIn(0f, 1f),
                                    ((p.y - r.top) / r.height).coerceIn(0f, 1f),
                                )
                            }
                            detectDragGestures(
                                onDragStart = { pos -> current = listOf(normalise(pos)) },
                                onDrag = { change, _ ->
                                    change.consume()
                                    current = current + normalise(change.position)
                                },
                                onDragEnd = {
                                    if (current.isNotEmpty()) {
                                        val p = pens[penIndex]
                                        strokes.add(Ink(current, p.color, thickness * p.widthFactor))
                                    }
                                    current = emptyList()
                                },
                                onDragCancel = { current = emptyList() },
                            )
                        },
                ) {
                    val r = fitInside(size.width, size.height, image.width, image.height, 8.dp.toPx())
                    drawImage(
                        image,
                        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
                        dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
                    )

                    fun draw(points: List<Offset>, color: Color, width: Float) {
                        val path = Path()
                        tracePath(
                            points.map { Offset(r.left + it.x * r.width, r.top + it.y * r.height) },
                            moveTo = { x, y -> path.moveTo(x, y) },
                            lineTo = { x, y -> path.lineTo(x, y) },
                            quadTo = { a, b, c, d -> path.quadraticBezierTo(a, b, c, d) },
                        )
                        drawPath(
                            path, color,
                            style = Stroke(width = width * r.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                    strokes.forEach { draw(it.points, it.color, it.width) }
                    if (current.isNotEmpty()) draw(current, pen.color, thickness * pen.widthFactor)
                }
            }
        }
    }
}
