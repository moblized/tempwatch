package app.tempwatch

import app.tempwatch.ble.TempSpikeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real advertisements captured by the thermopro-ble project, with the values it expects. */
class DecoderTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun tp960rSingleProbe() {
        // manufacturer_data={14848: b"\x000\x088\x00"}
        val r = TempSpikeDecoder.decode("TP960R (0000)", mapOf(14848 to bytes(0x00, 0x30, 0x08, 0x38, 0x00)))
        assertEquals(1, r.size)
        assertEquals(0, r[0].probe)
        assertEquals(28.0, r[0].internalC!!, 0.001)
        assertEquals(26.0, r[0].ambientC!!, 0.001)
        assertEquals(2096, r[0].batteryMillivolts)
        assertEquals(9, r[0].batteryPercent)
        assertEquals("TP96", r[0].format)
    }

    @Test
    fun tp962rTwoProbes() {
        // {17152: b"\x00\x17\nC\x00", 14081: b"\x00;\x0b7\x00"}
        val r = TempSpikeDecoder.decode(
            "TP962R (0000)",
            mapOf(17152 to bytes(0x00, 0x17, 0x0A, 0x43, 0x00), 14081 to bytes(0x00, 0x3B, 0x0B, 0x37, 0x00)),
        ).associateBy { it.probe }
        assertEquals(37.0, r[0]!!.internalC!!, 0.001)
        assertEquals(37.0, r[0]!!.ambientC!!, 0.001)
        assertEquals(82, r[0]!!.batteryPercent)
        assertEquals(25.0, r[1]!!.internalC!!, 0.001)
        assertEquals(25.0, r[1]!!.ambientC!!, 0.001)
        assertEquals(100, r[1]!!.batteryPercent)
    }

    @Test
    fun tp972sProFormat() {
        // {29184: b"\x00j\n3\xd3\xb8B\x00@\xaeBf\x06\xaeBlTswD\xf8"} → tip 3.6, centre 0.6, end 0.6, grill 15
        val payload = bytes(
            0x00, 0x6A, 0x0A, 0x33, 0xD3, 0xB8, 0x42, 0x00, 0x40, 0xAE, 0x42,
            0x66, 0x06, 0xAE, 0x42, 0x6C, 0x54, 0x73, 0x77, 0x44, 0xF8,
        )
        val r = TempSpikeDecoder.decode("TP972S", mapOf(29184 to payload)).single()
        assertEquals("TP97", r.format)
        assertEquals(0.6, r.internalC!!, 0.05) // coldest of tip/centre/end
        assertEquals(15.6, r.ambientC!!, 0.05)
        assertEquals(90, r.batteryPercent)
    }

    @Test
    fun thirteenByteVariantIgnoresTrailingAddress() {
        // TP972S simple probe: {13056: b"\x00\x48\x0a\x33\x00\xff\xee\xdd\xcc\xbb\xaa"}
        val payload = bytes(0x00, 0x48, 0x0A, 0x33, 0x00, 0xFF, 0xEE, 0xDD, 0xCC, 0xBB, 0xAA)
        val r = TempSpikeDecoder.decode("TP972S", mapOf(13056 to payload)).single()
        assertEquals("TP96", r.format)
        assertEquals(21.0, r.internalC!!, 0.001) // 0x0033 - 30
        assertEquals(21.0, r.ambientC!!, 0.001)
    }

    @Test
    fun outOfRangeValuesBecomeNull() {
        val r = TempSpikeDecoder.decodeFrame("TP961", bytes(0, 0xFF, 0xFF, 0x30, 0x08, 0xFF, 0xFF))!!
        assertNull(r.internalC)
        assertNull(r.ambientC)
    }

    @Test
    fun otherLengthsAreIgnored() {
        assertNull(TempSpikeDecoder.decodeFrame("TP960R", bytes(1, 2, 3, 4, 5)))
    }

    @Test
    fun recognisesTempSpikeNamesAndUuid() {
        assertTrue(TempSpikeDecoder.looksLikeTempSpike("TP960R (0000)", emptyList()))
        assertTrue(TempSpikeDecoder.looksLikeTempSpike("TP961", emptyList()))
        assertTrue(TempSpikeDecoder.looksLikeTempSpike(null, listOf("72FBB631-6F6B-D1BA-DB55-2EE6FDD942BD")))
        assertFalse(TempSpikeDecoder.looksLikeTempSpike("TP357S (C890)", emptyList()))
        assertFalse(TempSpikeDecoder.looksLikeTempSpike(null, emptyList()))
    }
}
