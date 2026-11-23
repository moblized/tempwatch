package app.tempwatch.ble

import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * One probe's reading, decoded from a TempSpike booster's Bluetooth broadcast.
 * Temperatures are in °C. A value is null when the probe sent something out of range.
 */
data class ProbeReading(
    /** 0 for the first (or only) probe. */
    val probe: Int,
    /** Meat temperature (the probe's internal sensor). */
    val internalC: Double?,
    /** Grill / oven air temperature (the probe's ceramic-end sensor). */
    val ambientC: Double?,
    val batteryPercent: Int?,
    val batteryMillivolts: Int?,
    /** Which broadcast layout this came from: "TP96" (TempSpike) or "TP97" (TempSpike Pro). */
    val format: String,
)

/**
 * Reads the temperatures a TempPro (formerly ThermoPro) TempSpike booster broadcasts.
 *
 * The booster never needs pairing: it puts the probe's readings straight into its Bluetooth
 * advertisement, in the manufacturer-data field. The layout is the one worked out by the
 * open-source thermopro-ble project (used by Home Assistant):
 *
 *  - Android splits manufacturer data into a 2-byte "company ID" plus the rest, but TempSpike
 *    boosters don't use a real company ID: those two bytes are just the start of the reading.
 *    So we glue them back on (little-endian) before decoding — see [frame].
 *  - Between readings (e.g. while the probe sits in the booster) the TP961 sends a 6-byte frame
 *    that is just its own Bluetooth address backwards. No reading in it, so it's ignored.
 *  - TempSpike (TP96x, which includes the TP961 TempSpike CORE), 7 bytes (13 on some, the
 *    extra 6 are ignored):
 *      [0] probe number  [1..2] meat °C + 30  [3..4] battery mV  [5..6] grill °C + 30
 *  - TempSpike Pro (TP97x), 23 bytes: [0] probe  [1..2] grill °F + 54  [3..4] battery mV
 *      [5..8] tip, [9..12] centre, [13..16] end — floats, °F + 54.
 */
object TempSpikeDecoder {
    /** Service UUID TempSpike boosters advertise. */
    const val SERVICE_UUID = "72fbb631-6f6b-d1ba-db55-2ee6fdd942bd"

    private val INTERNAL_RANGE = -40.0..150.0
    private val AMBIENT_RANGE = -40.0..450.0

    /**
     * Newer boosters advertise a short code instead of the model: the TP961 TempSpike CORE is
     * "I61" (confirmed in thermopro-ble PR #224 and on a real unit). Same 7-byte frame as the TP960.
     */
    private val SHORT_NAME = Regex("^I[67]\\d$")

    /** True for anything that could be a TempSpike booster, by its name or its service UUID. */
    fun looksLikeTempSpike(name: String?, serviceUuids: Collection<String>): Boolean {
        val n = name?.trim()?.uppercase().orEmpty()
        return n.startsWith("TP96") || n.startsWith("TP97") || n.contains("SPIKE") || SHORT_NAME.matches(n) ||
            serviceUuids.any { it.equals(SERVICE_UUID, ignoreCase = true) }
    }

    /** "I61" → "TP961", for display. */
    fun modelName(name: String?): String? {
        val n = name?.trim() ?: return null
        return if (SHORT_NAME.matches(n.uppercase())) "TP9${n.substring(1)}" else n.substringBefore(" (")
    }

    /** Puts Android's split-off "company ID" back in front of the payload. */
    fun frame(companyId: Int, payload: ByteArray): ByteArray =
        byteArrayOf((companyId and 0xFF).toByte(), ((companyId shr 8) and 0xFF).toByte()) + payload

    /** Every probe reading in one advertisement (manufacturer data keyed by company ID). */
    fun decode(name: String?, manufacturerData: Map<Int, ByteArray>): List<ProbeReading> =
        manufacturerData.entries
            .mapNotNull { (id, payload) -> decodeFrame(name, frame(id, payload)) }
            .distinctBy { it.probe }

    /** Boosters that only give readings over a connection (see [SpikeLink]): the TP961 "I61". */
    fun needsConnection(name: String?): Boolean = SHORT_NAME.matches(name?.trim()?.uppercase().orEmpty())

    /**
     * A notification from the TP961's FF01 characteristic (service 93bb7cab-…): 0x10, then the
     * same 7-byte frame as the broadcast one. Sent every 3 s per probe slot.
     */
    fun decodeNotification(data: ByteArray): ProbeReading? =
        if (data.size >= 8 && data[0] == 0x10.toByte()) {
            decodeSpike(data.copyOfRange(1, 8))?.copy(format = "TP961 link")
        } else {
            null
        }

    fun decodeFrame(name: String?, data: ByteArray): ProbeReading? {
        val n = name?.trim()?.uppercase().orEmpty()
        return when {
            data.size == 23 && !n.startsWith("TP96") -> decodePro(data)
            data.size == 7 || data.size == 13 -> decodeSpike(data)
            else -> null
        }
    }

    /** Both sensors at exactly −1 °C (raw 29): an empty probe slot (the TP961 booster reports two). */
    private const val EMPTY_SLOT = 29

    private fun decodeSpike(d: ByteArray): ProbeReading? {
        if (d.u16(1) == EMPTY_SLOT && d.u16(5) == EMPTY_SLOT) return null
        val millivolts = d.u16(3)
        return ProbeReading(
            probe = d.u8(0),
            internalC = plausible(d.u16(1) - 30.0, INTERNAL_RANGE),
            ambientC = plausible(d.u16(5) - 30.0, AMBIENT_RANGE),
            batteryPercent = batteryPercent(millivolts),
            batteryMillivolts = millivolts.takeIf { it > 0 },
            format = "TP96",
        )
    }

    private fun decodePro(d: ByteArray): ProbeReading {
        val millivolts = d.u16(3)
        val tip = proTemp(d.f32(5).toDouble())
        val centre = proTemp(d.f32(9).toDouble())
        val end = proTemp(d.f32(13).toDouble())
        // The meat is only as done as its coldest point.
        val internal = listOfNotNull(tip, centre, end).minOrNull()
        return ProbeReading(
            probe = d.u8(0),
            internalC = internal,
            ambientC = proTemp(d.u16(1).toDouble()),
            batteryPercent = batteryPercent(millivolts),
            batteryMillivolts = millivolts.takeIf { it > 0 },
            format = "TP97",
        )
    }

    private fun proTemp(raw: Double): Double? {
        if (raw.isNaN() || raw.isInfinite()) return null
        val c = (raw - 54.0 - 32.0) * 5.0 / 9.0
        return plausible((c * 10).roundToInt() / 10.0, AMBIENT_RANGE)
    }

    /**
     * Battery voltage to percent. The curve is thermopro-ble's fit to measured TempSpike
     * voltages: 52.317286·tanh(mV/273.624 − 8.76485) + 51.06925, clamped to 0–100.
     */
    fun batteryPercent(millivolts: Int): Int? {
        if (millivolts <= 0) return null
        val raw = 52.317286 * tanh(millivolts / 273.624277936 - 8.76485439394) + 51.06925
        return raw.coerceIn(0.0, 100.0).roundToInt()
    }

    private fun plausible(value: Double, range: ClosedFloatingPointRange<Double>): Double? =
        value.takeIf { it in range }

    private fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
    private fun ByteArray.f32(i: Int): Float =
        Float.fromBits(u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24))

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
}
