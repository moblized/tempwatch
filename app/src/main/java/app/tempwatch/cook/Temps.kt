package app.tempwatch.cook

import kotlin.math.abs
import kotlin.math.roundToInt

enum class TempUnit(val symbol: String) { F("°F"), C("°C") }

/** Temperature conversion and display. Everything is stored in °C; shown in the chosen unit. */
object Temps {
    fun toUnit(celsius: Double, unit: TempUnit): Double =
        if (unit == TempUnit.F) celsius * 9.0 / 5.0 + 32.0 else celsius

    fun toCelsius(value: Double, unit: TempUnit): Double =
        if (unit == TempUnit.F) (value - 32.0) * 5.0 / 9.0 else value

    /** "135°F", or "--" when there's no reading. */
    fun format(celsius: Double?, unit: TempUnit, withUnit: Boolean = true): String {
        if (celsius == null) return "--"
        val n = toUnit(celsius, unit).roundToInt()
        return if (withUnit) "$n${unit.symbol}" else "$n°"
    }

    /** Whole degrees in the chosen unit, for big displays. */
    fun whole(celsius: Double, unit: TempUnit): Int = toUnit(celsius, unit).roundToInt()

    /** A rate of change, e.g. "+1.4°F/min". */
    fun rate(celsiusPerMinute: Double, unit: TempUnit): String {
        val v = if (unit == TempUnit.F) celsiusPerMinute * 9.0 / 5.0 else celsiusPerMinute
        val sign = if (v >= 0) "+" else "−"
        val shown = (abs(v) * 10).roundToInt() / 10.0
        return "$sign$shown${unit.symbol}/min"
    }

    /** "1h 25m", "12m", "<1m". */
    fun duration(ms: Long): String {
        val totalMin = (ms / 60_000L).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            ms < 60_000L -> "<1m"
            h == 0L -> "${m}m"
            m == 0L -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
