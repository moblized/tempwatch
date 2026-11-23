package app.tempwatch.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import app.tempwatch.cook.HeatScale
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's heat ring drawn into a bitmap for home-screen widgets (widgets can't run Compose).
 * Same look: same-size ticks coloured by the temperature each one stands for, lit up to the
 * current reading, a white dot at the reading and a white tick at the target, with the
 * temperature in the middle in its heat colour.
 */
object RingBitmap {
    private const val TICKS = 61
    private const val START_DEG = 120.0
    private const val SWEEP_DEG = 300.0
    private const val UNLIT_ALPHA = 46 // of 255

    fun draw(
        sizePx: Int,
        range: ClosedFloatingPointRange<Double>,
        currentC: Double?,
        targetC: Double?,
        big: String,
        bigColor: Int,
        small: String?,
        smallColor: Int,
        dim: Boolean,
    ): Bitmap {
        val size = sizePx.coerceIn(96, 520)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f + size * 0.03f
        val rOut = size * 0.46f
        val rIn = size * 0.40f
        val lit = currentC?.let { HeatScale.position(it, range) }

        fun dx(p: Float) = cos(Math.toRadians(START_DEG + p * SWEEP_DEG)).toFloat()
        fun dy(p: Float) = sin(Math.toRadians(START_DEG + p * SWEEP_DEG)).toFloat()

        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = size * 0.014f
        }
        for (i in 0 until TICKS) {
            val p = i / (TICKS - 1f)
            val temp = range.start + p * (range.endInclusive - range.start)
            tick.color = HeatScale.argb(temp)
            tick.alpha = when {
                lit == null || p > lit -> UNLIT_ALPHA
                dim -> 120
                else -> 255
            }
            canvas.drawLine(cx + dx(p) * rIn, cy + dy(p) * rIn, cx + dx(p) * rOut, cy + dy(p) * rOut, tick)
        }

        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = size * 0.018f
        }
        if (targetC != null) {
            val p = HeatScale.position(targetC, range)
            canvas.drawLine(cx + dx(p) * rIn, cy + dy(p) * rIn, cx + dx(p) * rOut, cy + dy(p) * rOut, white)
        }
        if (lit != null) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); alpha = if (dim) 130 else 255 }
            val rMid = (rIn + rOut) / 2f
            canvas.drawCircle(cx + dx(lit) * rMid, cy + dy(lit) * rMid, size * 0.022f, dot)
        }

        val bigPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bigColor
            textAlign = Paint.Align.CENTER
            textSize = size * (if (big.length > 4) 0.20f else 0.25f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val bigY = if (small != null) cy + bigPaint.textSize * 0.22f else cy + bigPaint.textSize * 0.35f
        canvas.drawText(big, cx, bigY, bigPaint)
        if (small != null) {
            val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = smallColor
                textAlign = Paint.Align.CENTER
                textSize = size * 0.085f
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.12f
            }
            canvas.drawText(small, cx, bigY + smallPaint.textSize * 1.7f, smallPaint)
        }
        return bmp
    }
}
