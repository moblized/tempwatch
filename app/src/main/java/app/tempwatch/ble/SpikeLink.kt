package app.tempwatch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.tempwatch.data.Prefs
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Stays connected to boosters that only give readings over a connection: the TP961 TempSpike
 * CORE ("I61"). Once connected and subscribed, the booster sends a notification on FF01 every
 * 3 s for each probe slot (see [TempSpikeDecoder.decodeNotification]). No login is needed.
 *
 * The link is up whenever something wants readings (the open app or a running cook; same as
 * [BleScanner]). If it drops, it reconnects with Android's auto-connect, which waits for the
 * booster to come back in range and works with the screen off.
 *
 * While TempWatch is connected, the official TempPro app may not be able to connect.
 */
@SuppressLint("MissingPermission") // every call is behind GattProbe.hasPermission()
object SpikeLink {
    val SERVICE: UUID = UUID.fromString("93bb7cab-eb14-c5a9-2354-a24f4df9330f")
    val READINGS: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val RSSI_EVERY_MS = 10_000L

    enum class State { CONNECTING, CONNECTED, RECONNECTING, NO_PERMISSION, UNSUPPORTED }

    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    /** Link state per booster address. */
    val states: StateFlow<Map<String, State>> = _states

    /** Called on the main thread with each decoded notification (and the raw bytes). */
    var onReading: ((address: String, reading: ProbeReading?, raw: ByteArray) -> Unit)? = null
    var onRssi: ((address: String, rssi: Int) -> Unit)? = null

    private class Link(val address: String) {
        var gatt: BluetoothGatt? = null
        var failures = 0
        var unsupported = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val links = HashMap<String, Link>()
    private var wanted = false
    private var app: Context? = null

    /** Turn links on (connect to every remembered TP961) or off (disconnect all). */
    fun setWanted(context: Context, on: Boolean) = onMain {
        app = context.applicationContext
        wanted = on
        if (on) {
            Prefs.devices.value.filterValues { TempSpikeDecoder.needsConnection(it) }.keys.forEach { ensure(it) }
        } else {
            links.values.toList().forEach { close(it) }
            links.clear()
            _states.value = emptyMap()
        }
    }

    /** Connect to this booster if we aren't already (called when its broadcast is heard). */
    fun ensure(address: String) = onMain {
        val context = app ?: return@onMain
        if (!wanted || links.containsKey(address)) return@onMain
        if (!GattProbe.hasPermission(context)) {
            setState(address, State.NO_PERMISSION)
            return@onMain
        }
        val link = Link(address)
        links[address] = link
        connect(link, autoConnect = false)
    }

    /** After the Nearby-devices permission is granted. */
    fun retryAll() = onMain {
        val context = app ?: return@onMain
        if (!wanted) return@onMain
        _states.value.filterValues { it == State.NO_PERMISSION }.keys.forEach { links.remove(it) }
        setWanted(context, true)
    }

    private fun connect(link: Link, autoConnect: Boolean) {
        val context = app ?: return
        if (!wanted || links[link.address] !== link) return
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = runCatching { adapter?.getRemoteDevice(link.address) }.getOrNull()
        setState(link.address, if (autoConnect) State.RECONNECTING else State.CONNECTING)
        link.gatt = device?.let { runCatching { it.connectGatt(context, autoConnect, callbackFor(link), BluetoothDevice.TRANSPORT_LE) }.getOrNull() }
        if (link.gatt == null) main.postDelayed({ connect(link, autoConnect = true) }, 10_000L) // Bluetooth off?
    }

    private fun close(link: Link) {
        link.gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        link.gatt = null
    }

    private fun dropped(link: Link) {
        close(link)
        if (!wanted || links[link.address] !== link || link.unsupported) return
        link.failures++
        val delay = (2_000L * link.failures).coerceAtMost(15_000L)
        setState(link.address, State.RECONNECTING)
        main.postDelayed({ connect(link, autoConnect = true) }, delay)
    }

    private fun setState(address: String, state: State) {
        _states.value = _states.value + (address to state)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun callbackFor(link: Link) = object : BluetoothGattCallback() {
        private val rssiPoll = object : Runnable {
            override fun run() {
                val g = link.gatt ?: return
                runCatching { g.readRemoteRssi() }
                main.postDelayed(this, RSSI_EVERY_MS)
            }
        }

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (g !== link.gatt) return@onMain
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> main.postDelayed({ link.gatt?.discoverServices() }, 300L)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    main.removeCallbacks(rssiPoll)
                    dropped(link)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain {
            if (g !== link.gatt) return@onMain
            val c = g.getService(SERVICE)?.getCharacteristic(READINGS)
            val cccd = c?.getDescriptor(CCCD)
            if (c == null || cccd == null) {
                // Not the kind of booster we know how to read over a connection.
                link.unsupported = true
                setState(link.address, State.UNSUPPORTED)
                close(link)
                return@onMain
            }
            g.setCharacteristicNotification(c, true)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!ok) runCatching { g.disconnect() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = onMain {
            if (g !== link.gatt) return@onMain
            if (status == BluetoothGatt.GATT_SUCCESS) {
                link.failures = 0
                setState(link.address, State.CONNECTED)
                main.removeCallbacks(rssiPoll)
                main.post(rssiPoll)
            } else {
                runCatching { g.disconnect() }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            deliver(c, value)

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            deliver(c, c.value ?: return)
        }

        private fun deliver(c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != READINGS) return
            val copy = value.copyOf()
            onMain { onReading?.invoke(link.address, TempSpikeDecoder.decodeNotification(copy), copy) }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) = onMain {
            if (status == BluetoothGatt.GATT_SUCCESS) onRssi?.invoke(link.address, rssi)
        }
    }
}
