package app.tempwatch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tempwatch.ble.BleScanner
import app.tempwatch.cook.LiveUpdates
import app.tempwatch.cook.RawAdvert
import app.tempwatch.cook.TempHub
import app.tempwatch.cook.TempUnit
import app.tempwatch.data.Prefs

@Composable
fun SettingsScreen(canScan: Boolean, onRequestPermissions: () -> Unit, onBluetoothDetails: () -> Unit) {
    val context = LocalContext.current
    val unit by Prefs.unit.collectAsStateWithLifecycle()
    val headsUp by Prefs.headsUp.collectAsStateWithLifecycle()
    val devices by Prefs.devices.collectAsStateWithLifecycle()
    // Re-check notification settings whenever we come back from the system settings screen.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val live = remember(resumes) { LiveUpdates.status(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SectionCard {
            Text("Temperature unit", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = unit == TempUnit.F, onClick = { Prefs.setUnit(TempUnit.F) }, label = { Text("°F") })
                FilterChip(selected = unit == TempUnit.C, onClick = { Prefs.setUnit(TempUnit.C) }, label = { Text("°C") })
            }
            Text(
                "The thermometer measures in whole °C, so °F readings move in steps of about 2°.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Text("Alerts", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Almost-done heads-up")
                    Text(
                        "A notification ${if (unit == TempUnit.F) "5°F" else "3°C"} before the target",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = headsUp, onCheckedChange = { Prefs.setHeadsUp(it) })
            }
            Text(
                "When the target is reached, your phone's alarm sound plays on the alarm volume until you stop it " +
                    "(or for 10 minutes). Signal-lost warnings come after 90 seconds without a reading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Text("Lock-screen live card", style = MaterialTheme.typography.titleMedium)
            Text(
                when (live) {
                    LiveUpdates.Status.ON -> "On. While cooking, the meat temperature shows in the status bar and a live card sits on the lock screen."
                    LiveUpdates.Status.NOTIFICATIONS_OFF -> "Notifications are off for TempWatch, so there's no live card or alerts (the alarm sound still plays)."
                    LiveUpdates.Status.LIVE_UPDATES_OFF -> "Live updates are off for TempWatch. The card still shows as a normal notification."
                    LiveUpdates.Status.NOT_SUPPORTED -> "Shows as an ongoing notification with progress (status-bar chip needs Android 16)."
                },
            )
            if (live == LiveUpdates.Status.NOTIFICATIONS_OFF || live == LiveUpdates.Status.LIVE_UPDATES_OFF) {
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(LiveUpdates.settingsIntent(context, live)) }
                }) { Text("Turn on") }
            }
        }

        SectionCard {
            Text("Thermometers", style = MaterialTheme.typography.titleMedium)
            if (!canScan) {
                Text("Bluetooth permission is off.")
                Button(onClick = onRequestPermissions) { Text("Allow Bluetooth") }
            }
            if (devices.isEmpty()) {
                Text("None yet. Your TempSpike is added automatically the first time it's heard.")
            }
            for ((address, name) in devices) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name.ifBlank { "TempSpike" })
                        Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { Prefs.forgetDevice(address) }) { Text("Forget") }
                }
            }
            OutlinedButton(onClick = onBluetoothDetails) { Text("Bluetooth details") }
        }

        SectionCard {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text(
                "TempWatch ${appVersion(context)}. Reads the TempSpike's own Bluetooth broadcast, so no " +
                    "account or pairing. Not made by or affiliated with TempPro / ThermoPro.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Raw broadcasts from nearby devices: for checking what the thermometer actually sends. */
@Composable
fun BluetoothDetailsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val raw by TempHub.raw.collectAsStateWithLifecycle()
    val scan by BleScanner.status.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    DisposableEffect(showAll) {
        TempHub.captureAllDevices = showAll
        onDispose { TempHub.captureAllDevices = false }
    }
    val list = raw.values
        .filter { showAll || it.tempSpike || it.name?.uppercase()?.startsWith("TP") == true }
        .sortedWith(compareByDescending<RawAdvert> { it.tempSpike }.thenByDescending { it.rssi })

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader("Bluetooth details", onBack) }
        item {
            Text(
                "What nearby devices are broadcasting. If your thermometer shows here but no temperatures " +
                    "appear, tap Copy report and send it over so the decoding can be fixed. Scanner: ${scan.name.lowercase()}" +
                    if (BleScanner.lastErrorCode != 0) " (last error ${BleScanner.lastErrorCode})" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Show every nearby device", modifier = Modifier.weight(1f))
                Switch(checked = showAll, onCheckedChange = { showAll = it })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyReport(context, list) }) { Text("Copy report") }
                OutlinedButton(onClick = { TempHub.clearRaw() }) { Text("Clear") }
            }
        }
        if (list.isEmpty()) {
            item { Text("Nothing heard yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(list, key = { it.address }) { a ->
            Card(Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        describe(a),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
    }
}

private fun describe(a: RawAdvert): String = buildString {
    appendLine("${a.name ?: "(no name)"}  ${a.address}")
    appendLine("RSSI ${a.rssi} dBm · ${a.count} packets · ${((System.currentTimeMillis() - a.lastSeen) / 1000)} s ago")
    if (a.serviceUuids.isNotEmpty()) appendLine("Services: ${a.serviceUuids.joinToString()}")
    a.manufacturer.forEach { appendLine("Mfr $it") }
    a.decoded?.let { appendLine("Decoded: $it") }
    append("Raw: ${a.rawHex}")
}

private fun copyReport(context: Context, list: List<RawAdvert>) {
    val text = buildString {
        appendLine("TempWatch ${appVersion(context)} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine()
        list.forEach { appendLine(describe(it)); appendLine() }
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("TempWatch Bluetooth report", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
