package com.madmandriver.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.madmandriver.data.HarshEventEntity
import com.madmandriver.service.TracePoint
import com.madmandriver.ui.theme.SeverityExtreme
import com.madmandriver.ui.theme.SeverityModerate
import com.madmandriver.ui.theme.SeverityStrong
import com.madmandriver.ui.theme.severityColor
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min

private val ZoneOk = Color(0xFF2E7D32)
private const val GAUGE_MAX_MPS2 = 6f

private fun zoneColor(value: Float, trigger: Float): Color = when {
    value >= 4.5f -> SeverityExtreme
    value >= 3f -> SeverityStrong
    value >= trigger -> SeverityModerate
    else -> ZoneOk
}

/**
 * Semicircular dial: comfort zones as a faint arc (ok / moderate / strong /
 * extreme), the live value as a solid arc in the zone's color, big readout
 * in the middle. Scale is fixed 0–6 m/s².
 */
@Composable
fun AccelGauge(valueMps2: Float, triggerMps2: Float, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 18.dp.toPx()
            val diameter = min(size.width - strokeWidth, (size.height - strokeWidth) * 2f)
            val topLeft = Offset((size.width - diameter) / 2f, size.height - diameter / 2f - strokeWidth / 2f)
            val arcSize = Size(diameter, diameter)

            fun angleOf(v: Float) = v.coerceIn(0f, GAUGE_MAX_MPS2) / GAUGE_MAX_MPS2 * 180f

            val zones = listOf(
                0f to ZoneOk,
                triggerMps2 to SeverityModerate,
                3f to SeverityStrong,
                4.5f to SeverityExtreme,
            )
            zones.forEachIndexed { i, (start, color) ->
                val end = zones.getOrNull(i + 1)?.first ?: GAUGE_MAX_MPS2
                drawArc(
                    color = color.copy(alpha = 0.25f),
                    startAngle = 180f + angleOf(start),
                    sweepAngle = angleOf(end) - angleOf(start),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
            }
            if (valueMps2 > 0.05f) {
                drawArc(
                    color = zoneColor(valueMps2, triggerMps2),
                    startAngle = 180f,
                    sweepAngle = angleOf(valueMps2),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
            Text(
                String.format(Locale.US, "%.1f", valueMps2),
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = zoneColor(valueMps2, triggerMps2),
            )
            Text("m/s² horizontal", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Time series of the smoothed horizontal acceleration with a dashed trigger
 * line and severity-colored dots at detected events. [windowMs] limits the
 * view to the trailing window (live mode); null fits the whole ride.
 */
@Composable
fun AccelChart(
    points: List<TracePoint>,
    events: List<HarshEventEntity>,
    triggerMps2: Float,
    windowMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)

    Canvas(modifier) {
        if (points.size < 2) {
            drawText(textMeasurer, "waiting for data…", Offset(8.dp.toPx(), 8.dp.toPx()), labelStyle)
            return@Canvas
        }
        val tEnd = points.last().tMs
        val tStart = windowMs?.let { tEnd - it } ?: points.first().tMs
        val span = (tEnd - tStart).coerceAtLeast(1L)
        val dataMax = points.maxOf { it.accelMps2 }
        val eventMax = events.maxOfOrNull { it.peakMps2 } ?: 0f
        val yMax = ceil(maxOf(GAUGE_MAX_MPS2, dataMax + 0.5f, eventMax + 0.5f))

        fun x(t: Long) = (t - tStart).toFloat() / span * size.width
        fun y(v: Float) = size.height - v / yMax * size.height

        var grid = 2f
        while (grid < yMax) {
            drawLine(gridColor, Offset(0f, y(grid)), Offset(size.width, y(grid)), strokeWidth = 1f)
            drawText(
                textMeasurer, "${grid.toInt()}",
                Offset(4.dp.toPx(), (y(grid) - 14.dp.toPx()).coerceAtLeast(0f)),
                labelStyle,
            )
            grid += 2f
        }
        drawLine(
            zoneColor(triggerMps2, triggerMps2),
            Offset(0f, y(triggerMps2)),
            Offset(size.width, y(triggerMps2)),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )

        val stride = points.size / 1500 + 1
        val path = Path()
        var started = false
        for (i in points.indices step stride) {
            val p = points[i]
            if (p.tMs < tStart) continue
            val px = x(p.tMs)
            val py = y(p.accelMps2)
            if (!started) {
                path.moveTo(px, py)
                started = true
            } else {
                path.lineTo(px, py)
            }
        }
        drawPath(
            path, lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        for (e in events) {
            if (e.occurredAtUtcMs < tStart || e.occurredAtUtcMs > tEnd) continue
            drawCircle(
                severityColor(e.severity),
                radius = 5.dp.toPx(),
                center = Offset(x(e.occurredAtUtcMs), y(e.peakMps2)),
            )
        }
    }
}
