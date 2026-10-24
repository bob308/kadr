package ua.kadr.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Thin line icons drawn on a 24×24 grid, in the style of pro camera apps. */
object KIcons {
    private fun line(name: String, vararg paths: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()

    private fun solid(name: String, d: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(addPathNodes(d), fill = SolidColor(Color.White))
        }.build()

    val Back = line("back", "M15 5L8 12L15 19", width = 2.2f)
    val Script = line("script", "M6 3h8l4 4v14H6z", "M14 3v4h4", "M9 12h6", "M9 16h6")
    val Speed = line("speed", "M3.5 17a8.5 8.5 0 0 1 17 0", "M12 17L16 10.5", "M6.5 13.5l1 0.6", "M17.5 13.5l-1 0.6", "M12 8.5v1.2")
    val Flip = line("flip", "M3 8h3.5l2-2.5h7l2 2.5H21v11H3z", "M9.2 13a2.8 2.8 0 0 1 5.3-1.2", "M14.8 13a2.8 2.8 0 0 1-5.3 1.2", "M14.5 10.5v1.6h-1.6", "M9.5 15.5v-1.6h1.6")
    val Timer = line("timer", "M12 21a8 8 0 1 0 0-16a8 8 0 1 0 0 16z", "M12 9v4l2.5 1.8", "M10 2.5h4")
    val More = line("more", "M4 7h9", "M17 7h3", "M15 5v4", "M4 17h3", "M11 17h9", "M9 15v4")
    val Play = solid("play", "M8 5.5v13l10.5-6.5z")
    val Pause = line("pause", "M8.5 5.5v13", "M15.5 5.5v13", width = 3f)
    val Eye = line("eye", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M12 9.2a2.8 2.8 0 1 0 0 5.6a2.8 2.8 0 1 0 0-5.6z")
    val EyeOff = line("eyeoff", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z", "M4 4l16 16")
    val Gallery = line("gallery", "M3.5 5h17v14h-17z", "M3.5 15.5l5-5l4 4l3-3l5 5", "M15.5 8.5h0.01", width = 1.8f)
    val Grid = line("grid", "M4 4h16v16H4z", "M9.3 4v16", "M14.7 4v16", "M4 9.3h16", "M4 14.7h16")
    val Level = line("level", "M2.5 12h4", "M17.5 12h4", "M8.5 12h7", "M12 8.5v1.5", "M12 14v1.5")
    val Remote = line("remote", "M8 2.5h8v19H8z", "M12 6.5a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z", "M10.5 13h3", "M10.5 16.5h3")
    val Gear = line("gear", "M12 15a3 3 0 1 0 0-6a3 3 0 1 0 0 6z", "M12 2.5v3", "M12 18.5v3", "M2.5 12h3", "M18.5 12h3",
        "M5.3 5.3l2.1 2.1", "M16.6 16.6l2.1 2.1", "M5.3 18.7l2.1-2.1", "M16.6 7.4l2.1-2.1")
    val Mirror = line("mirror", "M12 3v18", "M9 7.5L4.5 12L9 16.5z", "M15 7.5l4.5 4.5L15 16.5z")
    val Plus = line("plus", "M12 5v14", "M5 12h14", width = 2f)
    val Reset = line("reset", "M4.5 12a7.5 7.5 0 1 0 2.2-5.3", "M4.5 4.5v4h4")
    val Mic = line("mic", "M9 5.5a3 3 0 0 1 6 0v6a3 3 0 0 1-6 0z", "M5.5 11.5a6.5 6.5 0 0 0 13 0", "M12 18v3")
    val Meter = line("meter", "M5 19v-4", "M9.5 19v-8", "M14 19v-12", "M18.5 19v-6", width = 2.2f)
    val Close = line("close", "M6 6l12 12", "M18 6L6 18", width = 2f)
    val Captions = line("captions", "M3 5h18v14H3z", "M6.5 13h5", "M13.5 13h4", "M6.5 16h3", "M11.5 16h6")
}

@Composable
fun KIcon(icon: ImageVector, tint: Color = Color.White, size: Dp = 26.dp, modifier: Modifier = Modifier) =
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))

/**
 * Ruler-style value picker: drag sideways to change, the big number shows the value,
 * the marker in the middle is where the value sits. Mirrors the dials in pro camera apps.
 */
@Composable
fun RulerSlider(
    value: Float, range: ClosedFloatingPointRange<Float>, step: Float, majorEvery: Int,
    format: (Float) -> String, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
    accent: Color = CapYellow,
) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    Box(modifier.fillMaxWidth().height(64.dp).pointerInput(range, step) {
        val pxPerStep = 9.dp.toPx()
        var acc = current
        detectHorizontalDragGestures(onDragStart = { acc = current }) { _, dx ->
            acc = (acc - dx / pxPerStep * step).coerceIn(range.start, range.endInclusive)
            change((acc / step).roundToInt() * step)
        }
    }) {
        Text(format(value), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter))
        Canvas(Modifier.fillMaxWidth().height(26.dp).align(Alignment.BottomCenter)) {
            val pxPerStep = 9.dp.toPx()
            val cx = size.width / 2
            val first = ((range.start) / step).roundToInt()
            val last = ((range.endInclusive) / step).roundToInt()
            for (k in first..last) {
                val x = cx + (k * step - value) / step * pxPerStep
                if (x < 0 || x > size.width) continue
                val major = k % majorEvery == 0
                val h = if (major) size.height * 0.75f else size.height * 0.4f
                val edgeFade = (1f - kotlin.math.abs(x - cx) / cx).coerceIn(0.15f, 1f)
                drawLine(Color.White.copy(alpha = 0.85f * edgeFade), Offset(x, size.height - h), Offset(x, size.height), if (major) 2.5f else 1.5f)
            }
            // Center marker
            val p = Path().apply {
                moveTo(cx, size.height * 0.15f); lineTo(cx - 7f, size.height); lineTo(cx + 7f, size.height); close()
            }
            drawLine(accent, Offset(cx, 0f), Offset(cx, size.height), 3f)
            drawPath(p, accent)
        }
    }
}
