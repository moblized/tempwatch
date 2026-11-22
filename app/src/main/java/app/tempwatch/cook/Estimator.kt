package app.tempwatch.cook

/** One recorded point of a cook. [t] is wall-clock time in ms; temperatures in °C. */
data class Sample(val t: Long, val internalC: Double?, val ambientC: Double?)

/**
 * Predicts when the meat reaches its target from how fast it has been rising lately.
 *
 * The TempSpike reports whole degrees C, so single readings are steppy; a least-squares line
 * through the last [WINDOW_MS] of readings smooths that out. Meat usually slows down as it
 * nears the target (and BBQ cuts "stall"), so the estimate tracks the recent rate, not the
 * whole cook's average.
 */
object Estimator {
    const val WINDOW_MS = 20 * 60_000L
    private const val MIN_SPAN_MS = 4 * 60_000L
    private const val MIN_POINTS = 6
    /** Slower than this (°C/min) counts as stalled. */
    private const val STALL_RATE = 0.03
    private const val MAX_MINUTES = 24 * 60.0

    enum class Status { WAITING, RISING, STALLED, FALLING, DONE }

    data class Estimate(
        val status: Status,
        /** °C per minute, from the fitted line. */
        val ratePerMin: Double? = null,
        val minutesLeft: Double? = null,
    )

    fun estimate(samples: List<Sample>, targetC: Double, now: Long, toleranceC: Double = 0.4): Estimate {
        val latest = samples.lastOrNull { it.internalC != null }?.internalC
            ?: return Estimate(Status.WAITING)
        if (latest >= targetC - toleranceC) return Estimate(Status.DONE, minutesLeft = 0.0)

        val pts = samples.filter { it.internalC != null && it.t >= now - WINDOW_MS }
        if (pts.size < MIN_POINTS || pts.last().t - pts.first().t < MIN_SPAN_MS) return Estimate(Status.WAITING)

        // Least squares in minutes since the first point (keeps the numbers small).
        val t0 = pts.first().t
        val xs = pts.map { (it.t - t0) / 60_000.0 }
        val ys = pts.map { it.internalC!! }
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in xs.indices) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        if (sxx == 0.0) return Estimate(Status.WAITING)
        val slope = sxy / sxx
        val fittedNow = my + slope * ((now - t0) / 60_000.0 - mx)

        return when {
            slope < -0.1 -> Estimate(Status.FALLING, slope)
            slope < STALL_RATE -> Estimate(Status.STALLED, slope)
            else -> {
                val minutes = ((targetC - fittedNow) / slope).coerceAtLeast(0.0)
                if (minutes > MAX_MINUTES) Estimate(Status.STALLED, slope)
                else Estimate(Status.RISING, slope, minutes)
            }
        }
    }

    /** How far from the starting temperature to the target the meat has come, 0..1. */
    fun progress(startC: Double?, currentC: Double?, targetC: Double): Float {
        if (startC == null || currentC == null) return 0f
        val span = targetC - startC
        if (span <= 0.0) return if (currentC >= targetC) 1f else 0f
        return ((currentC - startC) / span).toFloat().coerceIn(0f, 1f)
    }
}
