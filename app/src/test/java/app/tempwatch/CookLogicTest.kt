package app.tempwatch

import app.tempwatch.cook.CookFile
import app.tempwatch.cook.CookRecord
import app.tempwatch.cook.Estimator
import app.tempwatch.cook.Presets
import app.tempwatch.cook.Sample
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CookLogicTest {
    private val min = 60_000L

    /** A probe rising [perMin] °C a minute in whole-degree steps, like the real thing reports. */
    private fun rising(from: Double, perMin: Double, minutes: Int, start: Long = 0L): List<Sample> =
        (0..minutes * 6).map { i ->
            val t = start + i * 10_000L
            Sample(t, Math.floor(from + perMin * (i / 6.0)), 120.0)
        }

    @Test
    fun estimateFromSteadyRise() {
        val samples = rising(from = 20.0, perMin = 1.0, minutes = 15)
        val now = samples.last().t
        val e = Estimator.estimate(samples, targetC = 57.0, now = now)
        assertEquals(Estimator.Status.RISING, e.status)
        assertEquals(1.0, e.ratePerMin!!, 0.1)
        // At ~34.5°C (fitted) and 1°C/min, 57°C is ~22 minutes away.
        assertEquals(22.0, e.minutesLeft!!, 2.0)
    }

    @Test
    fun waitsForEnoughData() {
        val samples = rising(from = 20.0, perMin = 1.0, minutes = 2)
        assertEquals(Estimator.Status.WAITING, Estimator.estimate(samples, 57.0, samples.last().t).status)
        assertEquals(Estimator.Status.WAITING, Estimator.estimate(emptyList(), 57.0, 0L).status)
    }

    @Test
    fun flatTemperatureIsAStall() {
        val samples = (0..120).map { Sample(it * 10_000L, 71.0, 110.0) }
        val e = Estimator.estimate(samples, targetC = 95.0, now = samples.last().t)
        assertEquals(Estimator.Status.STALLED, e.status)
        assertNull(e.minutesLeft)
    }

    @Test
    fun atTargetIsDone() {
        val samples = rising(from = 50.0, perMin = 1.0, minutes = 10)
        assertEquals(Estimator.Status.DONE, Estimator.estimate(samples, 57.0, samples.last().t).status)
    }

    @Test
    fun progressFraction() {
        assertEquals(0.5f, Estimator.progress(20.0, 40.0, 60.0), 0.001f)
        assertEquals(1f, Estimator.progress(20.0, 70.0, 60.0), 0.001f)
        assertEquals(0f, Estimator.progress(null, 40.0, 60.0), 0.001f)
    }

    @Test
    fun cookFileRoundTrip() {
        val rec = CookRecord(
            title = "Beef · Medium rare",
            targetC = Temps.toCelsius(135.0, TempUnit.F),
            startedAt = 1_732_311_000_000L,
            endedAt = 1_732_314_600_000L,
            reachedAt = 1_732_314_000_000L,
            device = "AA:BB:CC:DD:EE:FF",
            probe = 0,
            samples = listOf(
                Sample(1_732_311_000_000L, 21.0, 24.0),
                Sample(1_732_311_010_000L, null, 25.5),
                Sample(1_732_311_020_000L, 22.0, null),
            ),
        )
        val back = CookFile.decode(CookFile.encode(rec))
        assertNotNull(back)
        assertEquals(rec.copy(savedPeakInternalC = 22.0, savedPeakAmbientC = 25.5, savedSampleCount = 3), back)

        val header = CookFile.decode(CookFile.encode(rec), withSamples = false)!!
        assertTrue(header.samples.isEmpty())
        assertEquals(22.0, header.peakInternalC!!, 0.0)
        assertEquals(3, header.savedSampleCount)
    }

    @Test
    fun cookFileRejectsJunk() {
        assertNull(CookFile.decode("hello"))
        assertNull(CookFile.decode(""))
    }

    @Test
    fun temperatureFormatting() {
        assertEquals("135°F", Temps.format(Temps.toCelsius(135.0, TempUnit.F), TempUnit.F))
        assertEquals("57°C", Temps.format(57.2, TempUnit.C))
        assertEquals("--", Temps.format(null, TempUnit.F))
        assertEquals("+1.8°F/min", Temps.rate(1.0, TempUnit.F))
        assertEquals("1h 25m", Temps.duration(85 * min))
        assertEquals("<1m", Temps.duration(30_000L))
    }

    @Test
    fun presetsAreSensible() {
        val beef = Presets.all.first { it.id == "beef" }
        assertTrue(beef.isBelowUsda(beef.options.first { it.label == "Medium rare" }))
        assertTrue(!beef.isBelowUsda(beef.options.first { it.label == "Medium" }))
        // The probe's meat sensor tops out at 212°F; no preset may ask for more.
        assertTrue(Presets.all.flatMap { it.options }.all { it.targetF in 120..212 })
    }
}
