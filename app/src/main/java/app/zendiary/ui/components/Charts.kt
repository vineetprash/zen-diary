package app.zendiary.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.zendiary.ui.theme.Mood
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min

/** Charts are drawn straight on Canvas: tiny, fast and in the app's own palette (no chart library). */
class ChartSeries(val values: List<Float?>, val color: Color, val label: String = "")

@Composable
fun LineChart(series: List<ChartSeries>, xLabels: List<String>, modifier: Modifier = Modifier, colorDots: Boolean = false) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val style = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        val left = 22.dp.toPx(); val bottom = 18.dp.toPx(); val top = 8.dp.toPx()
        val w = size.width - left - 8.dp.toPx(); val h = size.height - top - bottom
        fun yOf(v: Float) = top + (5f - v) / 4f * h
        for (l in 1..5) {
            drawLine(grid, Offset(left, yOf(l.toFloat())), Offset(left + w, yOf(l.toFloat())), 1.dp.toPx())
            val t = measurer.measure("$l", style)
            drawText(t, topLeft = Offset(2.dp.toPx(), yOf(l.toFloat()) - t.size.height / 2f))
        }
        val n = series.maxOfOrNull { it.values.size } ?: 0
        if (n == 0) return@Canvas
        fun xOf(i: Int) = left + if (n == 1) w / 2 else w * i / (n - 1)
        val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        series.forEach { s ->
            var path = Path(); var started = false
            s.values.forEachIndexed { i, v ->
                if (v == null) { if (started) { drawPath(path, s.color, style = stroke); path = Path(); started = false } }
                else if (!started) { path.moveTo(xOf(i), yOf(v)); started = true } else path.lineTo(xOf(i), yOf(v))
            }
            if (started) drawPath(path, s.color, style = stroke)
            s.values.forEachIndexed { i, v -> if (v != null) drawCircle(if (colorDots) Mood.colorFor(v) else s.color, 4.dp.toPx(), Offset(xOf(i), yOf(v))) }
        }
        if (xLabels.isNotEmpty()) {
            val step = max(1, xLabels.size / 6)
            xLabels.forEachIndexed { i, s ->
                if (i % step == 0) {
                    val t = measurer.measure(s, style)
                    drawText(t, topLeft = Offset((xOf(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), size.height - t.size.height))
                }
            }
        }
    }
}

@Composable
fun BarChart(values: List<Float?>, labels: List<String>, modifier: Modifier = Modifier, highlight: Int? = null) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        val top = 16.dp.toPx(); val bottom = 18.dp.toPx(); val h = size.height - top - bottom
        val slot = size.width / max(values.size, 1); val barW = slot * 0.58f
        drawLine(grid, Offset(0f, top + h), Offset(size.width, top + h), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            val cx = slot * (i + 0.5f)
            if (v != null) {
                val bh = v / 5f * h
                val a = if (highlight == null || highlight == i) 1f else 0.55f
                drawRoundRect(Mood.colorFor(v).copy(alpha = a), Offset(cx - barW / 2, top + h - bh), Size(barW, bh), CornerRadius(7.dp.toPx()))
                val t = measurer.measure("%.1f".format(v), style)
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, max(0f, top + h - bh - t.size.height - 1.dp.toPx())))
            }
            labels.getOrNull(i)?.let {
                val t = measurer.measure(it, style)
                drawText(t, topLeft = Offset(cx - t.size.width / 2f, size.height - t.size.height))
            }
        }
    }
}

@Composable
fun DonutChart(counts: IntArray, modifier: Modifier = Modifier.size(150.dp), center: @Composable () -> Unit) {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val total = counts.sum()
            val sw = 22.dp.toPx(); val d = min(size.width, size.height) - sw
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            if (total == 0) drawArc(empty, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
            else {
                var start = -90f
                val parts = counts.count { it > 0 }
                counts.forEachIndexed { i, c ->
                    if (c > 0) {
                        val sweep = 360f * c / total
                        drawArc(Mood.of(i + 1).color, start, if (parts > 1) max(sweep - 2f, 0.5f) else sweep, false, tl, Size(d, d), style = Stroke(sw))
                        start += sweep
                    }
                }
            }
        }
        center()
    }
}

/** Year in Pixels: 12 rows (months) x 31 columns (days), one tinted cell per logged day. */
@Composable
fun PixelGrid(year: Int, byDay: Map<LocalDate, Float>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val letters = "JFMAMJJASOND"
    Canvas(modifier.fillMaxWidth().height(150.dp)) {
        val left = 14.dp.toPx(); val gap = 1.5.dp.toPx()
        val cw = (size.width - left) / 31f; val ch = size.height / 12f
        for (m in 1..12) {
            val t = measurer.measure(letters[m - 1].toString(), style)
            drawText(t, topLeft = Offset(0f, (m - 1) * ch + (ch - t.size.height) / 2))
            for (d in 1..YearMonth.of(year, m).lengthOfMonth()) {
                val v = byDay[LocalDate.of(year, m, d)]
                drawRoundRect(v?.let { Mood.colorFor(it) } ?: empty,
                    Offset(left + (d - 1) * cw + gap / 2, (m - 1) * ch + gap / 2), Size(cw - gap, ch - gap), CornerRadius(2.dp.toPx()))
            }
        }
    }
}
