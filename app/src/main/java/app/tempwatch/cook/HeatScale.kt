package app.tempwatch.cook

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Colour by temperature, blended smoothly: cold meat is cool blue, then violet, magenta, red
 * around rare, orange through medium, amber when well done and nearly white-hot at boiling.
 * The ring and the temperature numbers use it, so they warm up as the meat does.
 */
object HeatScale {
    /** (°F, ARGB) stops; colours between stops are blended. */
    private val STOPS: List<Pair<Double, Int>> = listOf(
        32.0 to 0xFF3D7BFF.toInt(), // fridge-cold: blue
        70.0 to 0xFF6F74FF.toInt(), // room temperature: periwinkle
        100.0 to 0xFFA35CF0.toInt(), // warming: violet
        118.0 to 0xFFE0479E.toInt(), // magenta
        128.0 to 0xFFFF4D5E.toInt(), // rare: red
        138.0 to 0xFFFF5F3A.toInt(), // medium rare: red-orange
        150.0 to 0xFFFF8A2B.toInt(), // medium: orange
        165.0 to 0xFFFFB020.toInt(), // well done / poultry: amber
        190.0 to 0xFFFFD54A.toInt(), // BBQ territory: yellow
        212.0 to 0xFFFFF1B8.toInt(), // boiling: white-hot
    )

    /** The colour for a temperature in °C, as ARGB. */
    fun argb(celsius: Double): Int {
        val f = celsius * 9.0 / 5.0 + 32.0
        if (f <= STOPS.first().first) return STOPS.first().second
        if (f >= STOPS.last().first) return STOPS.last().second
        val i = STOPS.indexOfLast { it.first <= f }
        val (f0, c0) = STOPS[i]
        val (f1, c1) = STOPS[i + 1]
        return blend(c0, c1, (f - f0) / (f1 - f0))
    }

    fun blend(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * k).roundToInt() and 0xFF
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * What temperatures the ring spans, in °C. During a cook it runs from where the meat
     * started (rounded down to 10°F) to a little past the target, so the target sits near the
     * end of the ring. With no cook it spans the probe's whole range, 32–212°F.
     */
    fun ringRange(startC: Double?, targetC: Double?): ClosedFloatingPointRange<Double> {
        if (targetC == null) return 0.0..100.0
        val startF = (startC ?: 20.0) * 9.0 / 5.0 + 32.0
        val targetF = targetC * 9.0 / 5.0 + 32.0
        var loF = floor(startF.coerceAtMost(targetF - 20.0) / 10.0) * 10.0
        loF = loF.coerceAtLeast(32.0)
        val hiF = targetF + ((targetF - loF) * 0.15).coerceAtLeast(10.0)
        return ((loF - 32.0) * 5.0 / 9.0)..((hiF - 32.0) * 5.0 / 9.0)
    }

    /** Where [celsius] falls on the ring, 0..1. */
    fun position(celsius: Double, range: ClosedFloatingPointRange<Double>): Float =
        ((celsius - range.start) / (range.endInclusive - range.start)).toFloat().coerceIn(0f, 1f)
}

/** Doneness names for meats that have them (beef, lamb): "RARE", "MED RARE"… null otherwise. */
object DonenessLabel {
    fun forCook(title: String, celsius: Double): String? {
        val t = title.lowercase()
        if (!(t.startsWith("beef") || t.startsWith("lamb"))) return null
        val f = celsius * 9.0 / 5.0 + 32.0
        return when {
            f < 120 -> "RAW"
            f < 130 -> "RARE"
            f < 140 -> "MED RARE"
            f < 150 -> "MEDIUM"
            f < 160 -> "MED WELL"
            else -> "WELL DONE"
        }
    }
}
