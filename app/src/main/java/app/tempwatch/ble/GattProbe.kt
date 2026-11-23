package app.tempwatch.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Diagnostics: connects to a booster, lists its Bluetooth services, reads what can be read and
 * listens to every notification for a while, then disconnects. The log goes into the Bluetooth
 * details report, so a booster that only gives readings over a connection can be supported.
 */
@SuppressLint("MissingPermission") // run() checks hasPermission() first
object GattProbe {
    private const val LISTEN_MS = 25_000L
    private const val GIVE_UP_MS = 45_000L
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var started = 0L
    private var retried = false
    private var app: Context? = null
    private var target: String? = null
    private val queue = ArrayDeque<() -> Boolean>()

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun run(context: Context, address: String) {
        if (_running.value) return
        if (!hasPermission(context)) {
            _log.value = listOf("Needs the Nearby devices (connect) permission.")
            return
        }
        app = context.applicationContext
        target = address
        retried = false
        _log.value = emptyList()
        _running.value = true
        started = System.currentTimeMillis()
        add("Connecting to $address…")
        main.postDelayed(giveUp, GIVE_UP_MS)
        connect()
    }

    private fun connect() {
        val context = app ?: return finish("No context")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return finish("No Bluetooth")
        val device = runCatching { adapter.getRemoteDevice(target) }.getOrNull() ?: return finish("Bad address")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val giveUp = Runnable { finish("Stopped after ${GIVE_UP_MS / 1000} s") }
    private val doneListening = Runnable { finish("Done listening") }

    private fun finish(reason: String) {
        if (!_running.value) return
        add(reason)
        main.removeCallbacks(giveUp)
        main.removeCallbacks(doneListening)
        queue.clear()
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
        _running.value = false
    }

    private fun add(line: String) {
        val t = (System.currentTimeMillis() - started) / 100 / 10.0
        _log.value = _log.value + "[${t}s] $line"
    }

    private fun onMain(block: () -> Unit) {
        main.post(block)
    }

    private fun props(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-no-resp")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString("/")

    private fun show(bytes: ByteArray?): String {
        if (bytes == null) return "(null)"
        val hex = TempSpikeDecoder.hex(bytes)
        val text = bytes.map { it.toInt() and 0xFF }
        val ascii = if (text.isNotEmpty() && text.all { it in 32..126 }) "  \"${String(bytes)}\"" else ""
        return "$hex$ascii"
    }

    private fun next() {
        val g = gatt ?: return
        while (queue.isNotEmpty()) {
            val op = queue.removeFirst()
            if (op()) return // wait for its callback
        }
        add("Listening for notifications for ${LISTEN_MS / 1000} s…")
        main.removeCallbacks(doneListening)
        main.postDelayed(doneListening, LISTEN_MS)
        if (g.services.isEmpty()) finish("No services")
    }

    private fun plan(g: BluetoothGatt) {
        for (service in g.services) {
            add("Service ${service.uuid}")
            for (c in service.characteristics) {
                add("  Char ${c.uuid} [${props(c.properties)}]")
                if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    queue.addLast { g.readCharacteristic(c) }
                }
                val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                val cccd = c.getDescriptor(CCCD)
                if ((notify || indicate) && cccd != null) {
                    queue.addLast {
                        g.setCharacteristicNotification(c, true)
                        val value = if (notify) {
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        } else {
                            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                        }
                        writeDescriptor(g, cccd, value)
                    }
                }
            }
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (g != gatt) return@onMain
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    add("Connected (status $status). Discovering services…")
                    main.postDelayed({ gatt?.discoverServices() }, 600L)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (status == 133 && !retried && g.services.isEmpty()) {
                        retried = true
                        add("Connection failed (133), retrying once…")
                        runCatching { g.close() }
                        gatt = null
                        main.postDelayed({ connect() }, 1_000L)
                    } else {
                        finish("Disconnected (status $status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain {
            if (g != gatt) return@onMain
            add("Services discovered (status $status)")
            plan(g)
            next()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            onMain {
                add("Read ${c.uuid.toString().take(8)} = ${show(value)}")
                next()
            }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = c.value
            onMain {
                add("Read ${c.uuid.toString().take(8)} = ${show(value)}")
                next()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = onMain {
            add("Subscribed to ${d.characteristic.uuid.toString().take(8)} (status $status)")
            next()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onMain { add("Notify ${c.uuid.toString().take(8)} = ${show(value)}") }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = c.value
            onMain { add("Notify ${c.uuid.toString().take(8)} = ${show(value)}") }
        }
    }
}
