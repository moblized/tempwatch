package app.tempwatch.cook

import android.bluetooth.le.ScanResult
import android.content.Context
import app.tempwatch.ble.BleScanner
import app.tempwatch.ble.ProbeReading
import app.tempwatch.ble.TempSpikeDecoder
import app.tempwatch.data.Prefs
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A booster in range and the latest reading from each of its probes. */
data class DeviceState(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Last broadcast of any kind (the TP961 also sends address-only frames between readings). */
    val lastSeen: Long,
    /** Last broadcast that carried temperatures; 0 if none yet (e.g. probe still in the booster). */
    val lastReadingAt: Long,
    val probes: Map<Int, ProbeReading>,
)

/** One nearby Bluetooth device's latest broadcast, for the Bluetooth details screen. */
data class RawAdvert(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val count: Int,
    val serviceUuids: List<String>,
    /** "3A00 → 00 30 08 38 00" per manufacturer-data entry. */
    val manufacturer: List<String>,
    val rawHex: String,
    val tempSpike: Boolean,
    val decoded: String?,
    /** The most recent broadcast that carried temperatures (the latest one may not). */
    val lastReadingFrame: String? = null,
    /** Each distinct kind of packet heard from this device → how many times. */
    val variants: Map<String, Int> = emptyMap(),
)

/** The cook in progress plus the alert bookkeeping that goes with it. */
data class ActiveCook(
    val record: CookRecord,
    /** The meat has been below the target since the target was set (so crossing it should ring). */
    val wasBelow: Boolean,
    val lastReadingAt: Long,
    val headsUpSent: Boolean = false,
    val alarmRinging: Boolean = false,
    val signalLost: Boolean = false,
)

/**
 * The app's live state: boosters in range, the cook in progress, and the cook's alerts.
 * Fed by [BleScanner]; read by the screens and by [CookService]'s lock-screen card.
 */
object TempHub {
    /** Readings are whole °C, so "reached" allows a little rounding (57°C counts for 135°F). */
    const val TOLERANCE_C = 0.4
    private const val SAMPLE_EVERY_MS = 10_000L
    private const val SIGNAL_LOST_MS = 90_000L
    private const val PERSIST_EVERY_MS = 60_000L
    private const val MAX_RAW = 80

    private lateinit var app: Context
    private val io = Executors.newSingleThreadExecutor()

    private val _devices = MutableStateFlow<Map<String, DeviceState>>(emptyMap())
    val devices: StateFlow<Map<String, DeviceState>> = _devices

    private val _raw = MutableStateFlow<Map<String, RawAdvert>>(emptyMap())
    val raw: StateFlow<Map<String, RawAdvert>> = _raw

    private val _cook = MutableStateFlow<ActiveCook?>(null)
    val cook: StateFlow<ActiveCook?> = _cook

    /** Bumped whenever cook history changes, so the history screen reloads. */
    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion

    /** The Bluetooth details screen sets this to see every nearby device, not just TempSpikes. */
    @Volatile var captureAllDevices = false

    private val names = HashMap<String, String>()
    private var lastPersist = 0L
    private var lastScanKick = 0L

    fun init(context: Context) {
        app = context.applicationContext
        names.putAll(Prefs.devices.value.filterValues { it.isNotBlank() })
        BleScanner.listener = ::onScanResult
        CookStore.loadActive(app)?.let { rec ->
            val latest = rec.samples.lastOrNull { it.internalC != null }?.internalC
            _cook.value = ActiveCook(
                record = rec,
                wasBelow = rec.reachedAt == null && (latest == null || latest < rec.targetC - TOLERANCE_C),
                lastReadingAt = System.currentTimeMillis(),
            )
        }
    }

    // ---- Bluetooth -------------------------------------------------------------------------

    private fun onScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val now = System.currentTimeMillis()
        val address = result.device.address ?: return
        record.deviceName?.takeIf { it.isNotBlank() }?.let { names[address] = it }
        val name = names[address]
        val uuids = record.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        val mfr = LinkedHashMap<Int, ByteArray>()
        record.manufacturerSpecificData?.let { sa -> for (i in 0 until sa.size()) mfr[sa.keyAt(i)] = sa.valueAt(i) }

        val known = address in Prefs.devices.value
        val spike = known || TempSpikeDecoder.looksLikeTempSpike(name, uuids)
        val readings = if (spike) TempSpikeDecoder.decode(name, mfr) else emptyList()

        if (spike || captureAllDevices || name?.uppercase()?.startsWith("TP") == true) {
            recordRaw(address, name, result.rssi, now, uuids, mfr, record.bytes, spike, readings, packetKind(result))
        }
        if (!spike) return

        // Remember it even before the first reading, so it's recognised (and found with the
        // screen off) from now on.
        Prefs.rememberDevice(address, name)
        val old = _devices.value[address]
        _devices.value = _devices.value + (address to DeviceState(
            address = address,
            name = name,
            rssi = result.rssi,
            lastSeen = now,
            lastReadingAt = if (readings.isNotEmpty()) now else old?.lastReadingAt ?: 0L,
            probes = (old?.probes ?: emptyMap()) + readings.associateBy { it.probe },
        ))
        readings.forEach { onCookReading(address, it, now) }
    }

    private fun recordRaw(
        address: String, name: String?, rssi: Int, now: Long, uuids: List<String>,
        mfr: Map<Int, ByteArray>, bytes: ByteArray?, spike: Boolean, readings: List<ProbeReading>, kind: String,
    ) {
        val prev = _raw.value[address]
        val signature = buildString {
            append(kind)
            append(" · ").append(bytes?.let { lastNonZero(it) } ?: 0).append(" bytes")
            if (uuids.isNotEmpty()) append(" · uuids ").append(uuids.joinToString { it.take(8) })
            mfr.forEach { (id, data) -> append(" · mfr %04X (%d bytes)".format(id, data.size)) }
        }
        val known = prev?.variants ?: emptyMap()
        var variants = known + (signature to ((known[signature] ?: 0) + 1))
        if (variants.size > 10) variants = variants.entries.toList().takeLast(10).associate { it.key to it.value }
        if (prev != null && now - prev.lastSeen < 1_000L && readings.isEmpty() && signature in known) {
            _raw.value = _raw.value + (address to prev.copy(variants = variants, count = prev.count + 1))
            return // same kind of packet as before: just count it
        }
        val decoded = readings.joinToString("; ") { r ->
            "probe ${r.probe + 1}: meat ${r.internalC ?: "--"}°C, grill ${r.ambientC ?: "--"}°C, " +
                "battery ${r.batteryPercent ?: "--"}% (${r.batteryMillivolts ?: "--"} mV) [${r.format}]"
        }.ifEmpty { null }
        val entry = RawAdvert(
            address = address,
            name = name,
            rssi = rssi,
            lastSeen = now,
            count = (prev?.count ?: 0) + 1,
            serviceUuids = uuids,
            manufacturer = mfr.map { (id, data) -> "%04X → %s".format(id, TempSpikeDecoder.hex(data)) },
            rawHex = bytes?.let { TempSpikeDecoder.hex(it.copyOf(lastNonZero(it))) }.orEmpty(),
            tempSpike = spike,
            decoded = decoded,
            variants = variants,
            lastReadingFrame = if (decoded != null) {
                mfr.map { (id, data) -> "%04X → %s".format(id, TempSpikeDecoder.hex(data)) }.joinToString("; ") + " = " + decoded
            } else {
                prev?.lastReadingFrame
            },
        )
        var next = _raw.value + (address to entry)
        if (next.size > MAX_RAW) {
            next = next.values.sortedByDescending { it.lastSeen }.take(MAX_RAW).associateBy { it.address }
        }
        _raw.value = next
    }

    private fun packetKind(r: ScanResult): String {
        if (r.isLegacy) return "legacy" + if (r.isConnectable) " connectable" else ""
        fun phy(p: Int) = when (p) { 1 -> "1M"; 2 -> "2M"; 3 -> "Coded"; else -> "-" }
        return "extended sid ${r.advertisingSid} ${phy(r.primaryPhy)}/${phy(r.secondaryPhy)}" +
            if (r.isConnectable) " connectable" else ""
    }

    private fun lastNonZero(b: ByteArray): Int {
        var n = b.size
        while (n > 0 && b[n - 1].toInt() == 0) n--
        return n
    }

    fun clearRaw() {
        _raw.value = emptyMap()
    }

    /** The probe on screen: the cook's probe if cooking, else the most recently heard one. */
    fun primary(devices: Map<String, DeviceState>, cook: ActiveCook?): Pair<DeviceState, ProbeReading>? {
        val device = cook?.record?.device?.let { devices[it] }
            ?: devices.values.filter { it.probes.isNotEmpty() }.maxByOrNull { it.lastReadingAt }
            ?: return null
        val probe = cook?.record?.device?.let { device.probes[cook.record.probe] }
            ?: device.probes.entries.minByOrNull { it.key }?.value
            ?: return null
        return device to probe
    }

    fun readingFor(cook: ActiveCook): ProbeReading? =
        cook.record.device?.let { _devices.value[it] }?.probes?.get(cook.record.probe)

    // ---- Cooking ---------------------------------------------------------------------------

    fun startCook(title: String, targetC: Double) {
        val now = System.currentTimeMillis()
        val current = primary(_devices.value, null)
        val reading = current?.second
        val record = CookRecord(
            title = title,
            targetC = targetC,
            startedAt = now,
            device = current?.first?.address,
            probe = reading?.probe ?: 0,
            samples = reading?.let { listOf(Sample(now, it.internalC, it.ambientC)) } ?: emptyList(),
        )
        _cook.value = ActiveCook(
            record = record,
            wasBelow = reading?.internalC == null || reading.internalC < targetC - TOLERANCE_C,
            lastReadingAt = now,
        )
        persist(force = true)
        CookService.start(app)
    }

    fun changeTarget(title: String, targetC: Double) {
        val c = _cook.value ?: return
        stopAlarm()
        val latest = latestInternal(c)
        _cook.value = c.copy(
            record = c.record.copy(title = title, targetC = targetC, reachedAt = null),
            wasBelow = latest == null || latest < targetC - TOLERANCE_C,
            headsUpSent = false,
            alarmRinging = false,
        )
        Alerts.cancelDone(app)
        Alerts.cancelHeadsUp(app)
        persist(force = true)
    }

    fun endCook() {
        AlarmRinger.stop()
        val c = _cook.value ?: return
        _cook.value = null
        val finished = c.record.copy(endedAt = System.currentTimeMillis())
        io.execute {
            CookStore.finish(app, finished)
            _historyVersion.value++
        }
        Alerts.cancelAll(app)
    }

    fun stopAlarm() {
        AlarmRinger.stop()
        Alerts.cancelDone(app)
        _cook.value?.let { if (it.alarmRinging) _cook.value = it.copy(alarmRinging = false) }
    }

    fun deleteCook(id: Long) {
        io.execute {
            CookStore.delete(app, id)
            _historyVersion.value++
        }
    }

    fun latestInternal(c: ActiveCook): Double? =
        readingFor(c)?.internalC ?: c.record.samples.lastOrNull { it.internalC != null }?.internalC

    private fun onCookReading(address: String, r: ProbeReading, now: Long) {
        val c = _cook.value ?: return
        if (c.record.device != null && (c.record.device != address || c.record.probe != r.probe)) return

        var next = c.copy(
            record = c.record.copy(device = address, probe = r.probe),
            lastReadingAt = now,
            signalLost = false,
        )
        if (c.signalLost) Alerts.cancelSignalLost(app)

        val last = c.record.samples.lastOrNull()
        if (last == null || now - last.t >= SAMPLE_EVERY_MS) {
            next = next.copy(record = next.record.copy(samples = c.record.samples + Sample(now, r.internalC, r.ambientC)))
        }

        val meat = r.internalC
        if (meat != null) {
            val unit = Prefs.unit.value
            val doneAt = c.record.targetC - TOLERANCE_C
            val headsUpAt = doneAt - (if (unit == TempUnit.F) 5.0 / 1.8 else 3.0)
            if (!next.wasBelow && meat < doneAt) next = next.copy(wasBelow = true)
            if (next.wasBelow && c.record.reachedAt == null) {
                if (meat >= doneAt) {
                    next = next.copy(record = next.record.copy(reachedAt = now), alarmRinging = true, headsUpSent = true)
                    AlarmRinger.start(app)
                    Alerts.showDone(app, next.record, meat, unit)
                    Alerts.cancelHeadsUp(app)
                } else if (meat >= headsUpAt && !c.headsUpSent && Prefs.headsUp.value) {
                    next = next.copy(headsUpSent = true)
                    Alerts.showHeadsUp(app, next.record, meat, unit)
                }
            }
        }
        _cook.value = next
        persist(force = next.record.reachedAt != c.record.reachedAt)
    }

    /** Every 15 s from [CookService]: signal-lost alert, scan watchdog, saving. */
    fun tick() {
        val c = _cook.value ?: return
        val now = System.currentTimeMillis()
        val quiet = now - c.lastReadingAt
        if (!c.signalLost && quiet > SIGNAL_LOST_MS) {
            _cook.value = c.copy(signalLost = true)
            Alerts.showSignalLost(app)
        }
        if (quiet > 45_000L && now - lastScanKick > 60_000L) {
            lastScanKick = now
            BleScanner.restart()
        }
        persist(force = false)
    }

    private fun persist(force: Boolean) {
        val c = _cook.value ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < PERSIST_EVERY_MS) return
        lastPersist = now
        val snapshot = c.record
        io.execute { runCatching { CookStore.saveActive(app, snapshot) } }
    }
}
