package tr.h2open.driver.ui.stop

import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import tr.h2open.driver.R
import java.io.ByteArrayOutputStream

/** The customer's strokes, kept so they can be drawn on screen and exported as a PNG. */
@Stable
class SignatureState {
    internal val strokes = mutableStateListOf<List<Offset>>()
    internal val current = mutableStateListOf<Offset>()
    internal var size = IntSize.Zero

    val isEmpty get() = strokes.isEmpty() && current.isEmpty()

    fun clear() {
        strokes.clear()
        current.clear()
    }

    /** Black strokes on white, base64 PNG, or null when nothing was drawn. */
    fun toBase64Png(): String? {
        if (isEmpty || size.width == 0 || size.height == 0) return null
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint().apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }
        (strokes + listOf(current.toList())).filter { it.isNotEmpty() }.forEach { points ->
            val path = android.graphics.Path()
            path.moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { path.lineTo(it.x, it.y) }
            if (points.size == 1) path.lineTo(points[0].x + 0.1f, points[0].y)
            canvas.drawPath(path, paint)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

@Composable
fun rememberSignatureState() = remember { SignatureState() }

@Composable
fun SignaturePad(state: SignatureState, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    Box(
        modifier
            .fillMaxWidth()
            .height(180.dp)
            .border(1.dp, outline, RoundedCornerShape(8.dp))
            .onSizeChanged { state.size = it }
            .pointerInput(state) {
                detectDragGestures(
                    onDragStart = { state.current.add(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        state.current.add(change.position)
                    },
                    onDragEnd = {
                        state.strokes.add(state.current.toList())
                        state.current.clear()
                    },
                    onDragCancel = { state.current.clear() },
                )
            },
    ) {
        if (state.isEmpty) {
            Text(
                stringResource(R.string.stop_signature_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = outline,
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        Canvas(Modifier.matchParentSize()) {
            (state.strokes + listOf(state.current.toList())).filter { it.isNotEmpty() }.forEach { points ->
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(path, ink, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
