package app.tempwatch.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import androidx.core.content.IntentCompat
import app.tempwatch.data.AlarmSound
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import app.tempwatch.ble.GattProbe
import app.tempwatch.ble.SpikeLink
import app.tempwatch.cook.ChromaLink
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
    val keepScreenOn by Prefs.keepScreenOn.collectAsStateWithLifecycle()
    val devices by Prefs.devices.collectAsStateWithLifecycle()
    // Re-check notification settings whenever we come back from the system settings screen.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val live = remember(resumes) { LiveUpdates.status(context) }
    // …and whether Chroma Noise was installed or updated meanwhile.
    val chroma = remember(resumes) { ChromaLink.status(context) }
    val ringWithChroma by Prefs.ringWithChroma.collectAsStateWithLifecycle()
    val alarmSound by Prefs.alarmSound.collectAsStateWithLifecycle()
    var showSounds by remember { mutableStateOf(false) }
    val chromaRings = chroma == ChromaLink.Status.READY && ringWithChroma

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
            Text("While cooking", style = MaterialTheme.typography.titleMedium)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ring with Chroma Noise")
                    Text(
                        when (chroma) {
                            ChromaLink.Status.READY ->
                                if (ringWithChroma) {
                                    "The done alarm rings in Chroma Noise, full screen like your other alarms. Stop it in either app."
                                } else {
                                    "Off. Your phone's alarm sound plays instead."
                                }
                            ChromaLink.Status.NEEDS_UPDATE ->
                                "Update Chroma Noise to ring through it. Until then your phone's alarm sound plays."
                            ChromaLink.Status.NOT_INSTALLED ->
                                "Chroma Noise isn't installed, so your phone's alarm sound plays."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = ringWithChroma && chroma == ChromaLink.Status.READY,
                    enabled = chroma == ChromaLink.Status.READY,
                    onCheckedChange = { Prefs.setRingWithChroma(it) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Alarm sound")
                    Text(
                        if (alarmSound == AlarmSound.CHIMES && !chromaRings) {
                            "Soft chimes (needs Chroma Noise, so your phone's alarm plays for now)"
                        } else {
                            alarmSound.name
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { showSounds = true }) { Text("Change") }
            }
            if (chromaRings) {
                OutlinedButton(onClick = {
                    val sent = ChromaLink.ring(context, "Test alarm", "This is how a finished cook rings", alarmSound.uri)
                    Toast.makeText(
                        context,
                        if (sent) "Ringing in Chroma Noise…" else "Couldn't reach Chroma Noise",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Test alarm") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep screen on while cooking")
                    Text(
                        "Only while TempWatch is open. Locking the phone still works; the cook keeps running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepScreenOn, onCheckedChange = { Prefs.setKeepScreenOn(it) })
            }
            Text(
                "When the target is reached, the alarm rings until you stop it (or for 10 minutes). If Chroma Noise " +
                    "doesn't answer within 10 seconds, your phone's alarm sound plays instead. Signal-lost warnings " +
                    "come after 90 seconds without a reading.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showSounds) AlarmSoundDialog(current = alarmSound, onDismiss = { showSounds = false })

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
/** Soft chimes (Chroma Noise's), vibrate only, or any of the phone's sounds. */
@Composable
private fun AlarmSoundDialog(current: AlarmSound, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } ?: return@rememberLauncherForActivityResult
        val title = runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
        Prefs.setAlarmSound(AlarmSound(uri.toString(), title ?: "Phone sound"))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alarm sound") },
        text = {
            Column {
                SoundOption(AlarmSound.CHIMES.name, "Chroma Noise's built-in sound", current == AlarmSound.CHIMES) {
                    Prefs.setAlarmSound(AlarmSound.CHIMES)
                    onDismiss()
                }
                SoundOption("No sound", "Vibrate only", current.silent) {
                    Prefs.setAlarmSound(AlarmSound.VIBRATE_ONLY)
                    onDismiss()
                }
                val phoneSound = current.uri != null && !current.silent
                SoundOption(
                    "Phone sound…",
                    if (phoneSound) current.name else "Pick one of your phone's alarms or ringtones",
                    phoneSound,
                ) {
                    onDismiss()
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                        .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current.uri?.takeIf { phoneSound }?.let(Uri::parse))
                    try {
                        picker.launch(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "This phone has no sound picker", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Oled.card,
    )
}

@Composable
private fun SoundOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BluetoothDetailsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val raw by TempHub.raw.collectAsStateWithLifecycle()
    val scan by BleScanner.status.collectAsStateWithLifecycle()
    val gattLog by GattProbe.log.collectAsStateWithLifecycle()
    val gattRunning by GattProbe.running.collectAsStateWithLifecycle()
    var pendingTest by remember { mutableStateOf<String?>(null) }
    val askConnect = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val address = pendingTest
        pendingTest = null
        if (granted) SpikeLink.retryAll()
        if (granted && address != null) GattProbe.run(context, address)
    }
    val startTest: (String) -> Unit = { address ->
        if (GattProbe.hasPermission(context)) {
            GattProbe.run(context, address)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingTest = address
            askConnect.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
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
                Button(onClick = { copyReport(context, list, gattLog) }) { Text("Copy report") }
                OutlinedButton(onClick = { TempHub.clearRaw() }) { Text("Clear") }
            }
        }
        if (gattLog.isNotEmpty() || gattRunning) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (gattRunning) "Connection test running…" else "Connection test",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        SelectionContainer {
                            Text(
                                gattLog.joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        if (list.isEmpty()) {
            item { Text("Nothing heard yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(list, key = { it.address }) { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    SelectionContainer {
                        Text(
                            describe(a),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (a.tempSpike) {
                        OutlinedButton(
                            onClick = { startTest(a.address) },
                            enabled = !gattRunning,
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Connection test") }
                    }
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
    if (a.variants.isNotEmpty()) {
        appendLine("Packet types:")
        a.variants.forEach { (kind, n) -> appendLine("  ${n}× $kind") }
    }
    a.decoded?.let { appendLine("Decoded: $it") }
    if (a.decoded == null) a.lastReadingFrame?.let { appendLine("Last reading: $it") }
    append("Raw: ${a.rawHex}")
}

private fun copyReport(context: Context, list: List<RawAdvert>, gattLog: List<String>) {
    val text = buildString {
        appendLine("TempWatch ${appVersion(context)} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        appendLine()
        list.forEach { appendLine(describe(it)); appendLine() }
        if (gattLog.isNotEmpty()) {
            appendLine("Connection test:")
            gattLog.forEach { appendLine(it) }
        }
    }
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("TempWatch Bluetooth report", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
