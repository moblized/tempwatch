package app.tempwatch

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.tempwatch.ble.BleScanner
import app.tempwatch.ble.GattProbe
import app.tempwatch.ble.SpikeLink
import app.tempwatch.cook.CookService
import app.tempwatch.cook.TempHub
import app.tempwatch.ui.AppRoot
import app.tempwatch.ui.TempWatchTheme

class MainActivity : ComponentActivity() {
    private var canScan by mutableStateOf(false)

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            canScan = BleScanner.hasPermission(this)
            if (canScan) BleScanner.restart()
            if (GattProbe.hasPermission(this)) SpikeLink.retryAll()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark (OLED): light status/nav-bar icons on black, whatever the phone's setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        canScan = BleScanner.hasPermission(this)
        setContent {
            TempWatchTheme {
                AppRoot(canScan = canScan, onRequestPermissions = ::requestPermissions)
            }
        }
        if (savedInstanceState == null && (!canScan || !GattProbe.hasPermission(this) || missingNotificationPermission())) {
            requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        canScan = BleScanner.hasPermission(this)
        BleScanner.acquire(this, BleScanner.HOLDER_UI)
        // A cook that survived the app being closed: bring its service and lock-screen card back.
        if (TempHub.cook.value != null) CookService.start(this)
    }

    override fun onStop() {
        BleScanner.release(BleScanner.HOLDER_UI)
        super.onStop()
    }

    private fun requestPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT) // the TP961 is read over a connection
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        askPermissions.launch(wanted.toTypedArray())
    }

    private fun missingNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}
