package app.tempwatch.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import app.tempwatch.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Listens for TempSpike broadcasts. No pairing or connection: the booster puts its readings
 * in every advertisement, so we just scan.
 *
 * Whoever needs readings "holds" the scanner (the open app, the running cook). Two modes:
 *  - **open app**: an unfiltered scan, so a new booster shows up and the Bluetooth details
 *    screen can list everything nearby;
 *  - **cook in the background**: Android stops delivering unfiltered scans while the screen is
 *    off, so the scan is filtered to the TempSpike service UUID and the addresses and names of
 *    boosters seen before.
 *
 * The scan is restarted every 20 minutes (while the screen is on) because Android can quietly
 * downgrade scans that run longer than 30.
 */
@SuppressLint("MissingPermission") // every scan call is behind hasPermission()
object BleScanner {
    enum class Status { IDLE, SCANNING, NO_PERMISSION, BLUETOOTH_OFF, NO_BLUETOOTH, FAILED }

    const val HOLDER_UI = "ui"

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status

    /** Called on the main thread for every advertisement received. */
    var listener: ((ScanResult) -> Unit)? = null

    var lastErrorCode: Int = 0
        private set

    private const val RESTART_MS = 20 * 60_000L
    private const val RETRY_MS = 5_000L
    /** Android blocks apps that start more than 5 scans in 30 s; stay well clear. */
    private const val MIN_START_GAP_MS = 7_000L

    private val main = Handler(Looper.getMainLooper())
    private val holders = LinkedHashSet<String>()
    private var app: Context? = null
    private var active: BluetoothLeScanner? = null
    private var activeFiltered: Boolean? = null
    private var lastStart = 0L

    private val retry = Runnable { refresh(force = false) }

    /**
     * Some phones stop delivering results to a scan restarted with the screen off, so the routine
     * restart waits for the screen to be on. (If readings do go quiet with the screen off,
     * TempHub's watchdog restarts the scan anyway — nothing to lose by then.)
     */
    private val periodicRestart: Runnable = Runnable {
        val interactive = app?.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (interactive) refresh(force = true) else main.postDelayed(periodicRestart, 60_000L)
    }

    fun hasPermission(context: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    fun acquire(context: Context, holder: String) = onMain {
        app = context.applicationContext
        holders += holder
        refresh(force = false)
    }

    fun release(holder: String) = onMain {
        holders -= holder
        refresh(force = false)
    }

    /** Start over, e.g. after permission was granted or readings went quiet. */
    fun restart() = onMain { refresh(force = true) }

    private fun refresh(force: Boolean) {
        val context = app ?: return
        main.removeCallbacks(retry)
        if (holders.isEmpty()) {
            stop()
            _status.value = Status.IDLE
            return
        }
        val filtered = HOLDER_UI !in holders
        if (!force && active != null && activeFiltered == filtered) return
        stop()
        start(context, filtered)
    }

    private fun start(context: Context, filtered: Boolean) {
        if (!hasPermission(context)) {
            _status.value = Status.NO_PERMISSION
            return retryLater()
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            _status.value = Status.NO_BLUETOOTH
            return
        }
        val scanner = if (adapter.isEnabled) adapter.bluetoothLeScanner else null
        if (scanner == null) {
            _status.value = Status.BLUETOOTH_OFF
            return retryLater()
        }
        val wait = lastStart + MIN_START_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0) {
            main.postDelayed(retry, wait)
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(if (filtered) backgroundFilters() else emptyList(), settings, callback)
        } catch (e: Exception) {
            _status.value = Status.FAILED
            return retryLater()
        }
        lastStart = SystemClock.elapsedRealtime()
        active = scanner
        activeFiltered = filtered
        _status.value = Status.SCANNING
        main.postDelayed(periodicRestart, RESTART_MS)
    }

    private fun stop() {
        main.removeCallbacks(periodicRestart)
        active?.let { runCatching { it.stopScan(callback) } }
        active = null
        activeFiltered = null
    }

    private fun retryLater() {
        main.removeCallbacks(retry)
        main.postDelayed(retry, RETRY_MS)
    }

    /** Matches any TempSpike: by service UUID, or by the address/name of one seen before. */
    private fun backgroundFilters(): List<ScanFilter> {
        val filters = mutableListOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(TempSpikeDecoder.SERVICE_UUID)).build(),
        )
        for ((address, name) in Prefs.devices.value) {
            if (BluetoothAdapter.checkBluetoothAddress(address)) {
                runCatching { filters += ScanFilter.Builder().setDeviceAddress(address).build() }
            }
            if (name.isNotBlank()) filters += ScanFilter.Builder().setDeviceName(name).build()
        }
        return filters
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            listener?.invoke(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { listener?.invoke(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            lastErrorCode = errorCode
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
            active = null
            activeFiltered = null
            _status.value = Status.FAILED
            retryLater()
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
