package app.tempwatch.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tempwatch.cook.HeatScale
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private const val TICKS = 81
private const val START_DEG = 120f
private const val SWEEP_DEG = 300f
private const val UNLIT_ALPHA = 0.16f

/** Heat colour for a temperature, for text and pills. */
fun heatColor(celsius: Double): Color = Color(HeatScale.argb(celsius))

/**
 * The ring of ticks around the probe card's centre. Every tick is the same size and takes the
 * colour of the temperature it stands for, cool blue at the start through red and orange to
 * amber, so the lit part of the ring warms up as the meat does. Ticks light up to the current
 * reading (animated), a white dot marks it, and a white tick plus a tag marks the target.
 */
@Composable
fun ProbeRing(
    range: ClosedFloatingPointRange<Double>,
    currentC: Double?,
    targetC: Double?,
    unit: TempUnit,
    dim: Boolean,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit,
) {
    val litTarget = currentC?.let { HeatScale.position(it, range) } ?: 0f
    val lit by animateFloatAsState(litTarget, animationSpec = tween(durationMillis = 900), label = "ring fill")
    val measurer = rememberTextMeasurer()
    val tagStyle = TextStyle(color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val targetText = targetC?.let { Temps.format(it, unit) }

    Box(modifier.size(width = 300.dp, height = 264.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f + 20.dp.toPx())
            val rIn = 96.dp.toPx()
            val rOut = 107.dp.toPx()
            val stroke = 1.6.dp.toPx()
            val step = 1f / (TICKS - 1)

            fun dir(p: Float): Offset {
                val rad = Math.toRadians((START_DEG + p * SWEEP_DEG).toDouble())
                return Offset(cos(rad).toFloat(), sin(rad).toFloat())
            }

            for (i in 0 until TICKS) {
                val p = i * step
                val tempC = range.start + p * (range.endInclusive - range.start)
                val alpha = when {
                    currentC == null -> UNLIT_ALPHA
                    p <= lit -> if (dim) 0.45f else 1f
                    p - lit < step -> UNLIT_ALPHA + (1f - UNLIT_ALPHA) * (1f - (p - lit) / step) // soft leading edge
                    else -> UNLIT_ALPHA
                }
                val d = dir(p)
                drawLine(
                    color = heatColor(tempC).copy(alpha = alpha),
                    start = c + d * rIn,
                    end = c + d * rOut,
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            if (targetC != null && targetText != null) {
                val d = dir(HeatScale.position(targetC, range))
                drawLine(Color.White, c + d * rIn, c + d * rOut, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                // Tag just outside the ring, pushed out far enough that no corner touches the ticks.
                val layout = measurer.measure(targetText, tagStyle)
                val halfW = layout.size.width / 2f + 10.dp.toPx()
                val halfH = 12.dp.toPx()
                val r = rOut + 6.dp.toPx() + abs(d.x) * halfW + abs(d.y) * halfH
                val tc = c + d * r
                drawRoundRect(
                    color = Color.White,
                    topLeft = tc - Offset(halfW, halfH),
                    size = Size(halfW * 2f, halfH * 2f),
                    cornerRadius = CornerRadius(halfH, halfH),
                )
                drawText(layout, topLeft = tc - Offset(layout.size.width / 2f, layout.size.height / 2f))
            }

            if (currentC != null) {
                val d = dir(lit)
                drawCircle(Color.White.copy(alpha = if (dim) 0.5f else 1f), radius = 4.dp.toPx(), center = c + d * ((rIn + rOut) / 2f))
            }
        }
        Box(Modifier.align(Alignment.Center).offset(y = 20.dp)) { center() }
    }
}
