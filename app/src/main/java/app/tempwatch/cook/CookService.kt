package app.tempwatch.cook

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.tempwatch.ble.BleScanner
import app.tempwatch.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps listening to the thermometer while a cook is on, with the app closed and the screen
 * off, and shows the live card. Runs only while there's a cook; stops itself when it ends.
 */
class CookService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var shown: LiveContent? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!goForeground()) {
            stopSelf()
            return
        }
        BleScanner.acquire(this, HOLDER)
        scope.launch {
            while (isActive) {
                refreshCard()
                delay(3_000L)
            }
        }
        scope.launch {
            while (isActive) {
                delay(15_000L)
                TempHub.tick()
            }
        }
        scope.launch { TempHub.cook.collect { if (it == null) shutDown() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun goForeground(): Boolean = try {
        val cook = TempHub.cook.value
        val n = if (cook != null) {
            LiveCard.build(this, currentContent(cook).also { shown = it })
        } else {
            LiveCard.placeholder(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Alerts.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Alerts.LIVE_ID, n)
        }
        true
    } catch (e: Exception) {
        // E.g. Bluetooth permission was taken away, or Android won't let us start from the background.
        false
    }

    private fun currentContent(cook: ActiveCook): LiveContent =
        LiveCard.content(cook, TempHub.readingFor(cook), Prefs.unit.value, System.currentTimeMillis())

    private fun refreshCard() {
        val cook = TempHub.cook.value ?: return
        val content = currentContent(cook)
        if (content == shown) return
        shown = content
        runCatching { Alerts.manager(this).notify(Alerts.LIVE_ID, LiveCard.build(this, content)) }
    }

    private fun shutDown() {
        if (stopping) return
        stopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        BleScanner.release(HOLDER)
        super.onDestroy()
    }

    companion object {
        private const val HOLDER = "cook"

        /** Starts (or keeps) the service for the current cook. False if Android refused. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, CookService::class.java))
            true
        } catch (e: Exception) {
            false
        }
    }
}
