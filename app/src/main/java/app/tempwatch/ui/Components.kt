package app.tempwatch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.tempwatch.R
import app.tempwatch.cook.Sample
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Title row for full-screen pages, with a back arrow. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "Back")
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Small label + value tile, e.g. "Grill" / "251°F". */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
        }
    }
}

@Composable
fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp)) {
            Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Temperature over time: meat (orange), grill (teal), and the target as a dashed line.
 * [endTime] stretches the time axis (e.g. to "now" during a live cook).
 */
@Composable
fun TempGraph(
    samples: List<Sample>,
    targetC: Double?,
    unit: TempUnit,
    modifier: Modifier = Modifier,
    endTime: Long? = null,
) {
    val meatColor = MaterialTheme.colorScheme.primary
    val grillColor = MaterialTheme.colorScheme.tertiary
    val targetColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val measurer = rememberTextMeasurer()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val points = samples.filter { it.internalC != null || it.ambientC != null }
            if (points.size < 2) {
                drawText(measurer, "The graph fills in as readings come in", Offset(0f, size.height / 2 - 20f), labelStyle)
                return@Canvas
            }
            val t0 = points.first().t
            val t1 = max(points.last().t, endTime ?: 0L)
            val span = max(t1 - t0, 60_000L).toFloat()

            val values = points.flatMap { listOfNotNull(it.internalC, it.ambientC) } + listOfNotNull(targetC)
            val step = if (unit == TempUnit.F) 25.0 else 10.0
            var lo = floor(Temps.toUnit(values.min(), unit) / step) * step
            var hi = ceil(Temps.toUnit(values.max(), unit) / step) * step
            if (hi - lo < step * 2) { lo -= step; hi += step }

            val left = measurer.measure("000°", labelStyle).size.width + 8f
            val bottom = 22f + measurer.measure("0", labelStyle).size.height
            val w = size.width - left
            val h = size.height - bottom
            fun x(t: Long) = left + (t - t0) / span * w
            fun y(v: Double) = ((hi - Temps.toUnit(v, unit)) / (hi - lo) * h).toFloat()

            // Grid + labels: about four lines on the chosen step.
            val lines = ((hi - lo) / step).toInt()
            val every = max(1, (lines + 3) / 4)
            var v = lo
            var i = 0
            while (v <= hi + 0.01) {
                if (i % every == 0) {
                    val yy = ((hi - v) / (hi - lo) * h).toFloat()
                    drawLine(gridColor, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
                    val label = "${v.toInt()}°"
                    val m = measurer.measure(label, labelStyle)
                    drawText(m, topLeft = Offset(0f, yy - m.size.height / 2f))
                }
                v += step
                i++
            }

            targetC?.let {
                val yy = y(it)
                drawLine(
                    targetColor, Offset(left, yy), Offset(size.width, yy), strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f)),
                )
            }

            fun line(get: (Sample) -> Double?, color: Color, width: Float) {
                val path = Path()
                var drawing = false
                for (s in points) {
                    val value = get(s)
                    if (value == null) { drawing = false; continue }
                    if (drawing) path.lineTo(x(s.t), y(value)) else path.moveTo(x(s.t), y(value))
                    drawing = true
                }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
            }
            line({ it.ambientC }, grillColor, 4f)
            line({ it.internalC }, meatColor, 7f)

            val startLabel = measurer.measure("0m", labelStyle)
            drawText(startLabel, topLeft = Offset(left, size.height - startLabel.size.height))
            val endLabel = measurer.measure(Temps.duration((t1 - t0)), labelStyle)
            drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, size.height - endLabel.size.height))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(meatColor, "Meat")
            LegendDot(grillColor, "Grill")
            if (targetC != null) LegendDot(targetColor, "Target ${Temps.format(targetC, unit)}")
        }
    }
}
