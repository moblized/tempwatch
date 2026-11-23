package app.tempwatch

import app.tempwatch.cook.DonenessLabel
import app.tempwatch.cook.HeatScale
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatScaleTest {
    private fun c(f: Double) = Temps.toCelsius(f, TempUnit.F)
    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    @Test
    fun coldIsBlueHotIsWarm() {
        val cold = HeatScale.argb(c(40.0))
        val hot = HeatScale.argb(c(160.0))
        assertTrue(blue(cold) > red(cold))
        assertTrue(red(hot) > blue(hot))
        assertEquals(0xFF3D7BFF.toInt(), HeatScale.argb(c(0.0))) // below the scale: first colour
        assertEquals(0xFFFFF1B8.toInt(), HeatScale.argb(c(250.0))) // above: last colour
    }

    @Test
    fun blendsSmoothlyBetweenStops() {
        // Halfway between the 128°F and 138°F stops is halfway between their colours.
        val mid = HeatScale.argb(c(133.0))
        assertEquals(HeatScale.blend(0xFFFF4D5E.toInt(), 0xFFFF5F3A.toInt(), 0.5), mid)
        // Neighbouring degrees differ only a little: no jumps.
        for (f in 40..200) {
            val a = HeatScale.argb(c(f.toDouble()))
            val b = HeatScale.argb(c(f + 1.0))
            for (shift in listOf(0, 8, 16)) {
                assertTrue("jump at $f°F", kotlin.math.abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) <= 12)
            }
        }
    }

    @Test
    fun ringRunsFromStartToJustPastTarget() {
        val r = HeatScale.ringRange(startC = c(41.0), targetC = c(135.0))
        assertEquals(40.0, Temps.toUnit(r.start, TempUnit.F), 0.01)
        assertEquals(149.25, Temps.toUnit(r.endInclusive, TempUnit.F), 0.01) // 135 + 15% of 95
        assertEquals(0.87f, HeatScale.position(c(135.0), r), 0.01f)
        // No cook: the probe's whole range.
        val idle = HeatScale.ringRange(null, null)
        assertEquals(0.0, idle.start, 0.0)
        assertEquals(100.0, idle.endInclusive, 0.0)
        // Starting warm still leaves room below the target.
        val warm = HeatScale.ringRange(startC = c(130.0), targetC = c(135.0))
        assertTrue(HeatScale.position(c(135.0), warm) > 0.5f)
    }

    @Test
    fun donenessNames() {
        assertEquals("RARE", DonenessLabel.forCook("Beef · Medium rare", c(128.0)))
        assertEquals("MED RARE", DonenessLabel.forCook("Beef · Medium rare", c(135.0)))
        assertEquals("WELL DONE", DonenessLabel.forCook("Lamb · Well done", c(161.0)))
        assertNull(DonenessLabel.forCook("Chicken · Breast", c(150.0)))
    }
}
