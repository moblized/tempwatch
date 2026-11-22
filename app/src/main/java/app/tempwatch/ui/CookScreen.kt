package app.tempwatch.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tempwatch.ble.BleScanner
import app.tempwatch.cook.ActiveCook
import app.tempwatch.cook.Estimator
import app.tempwatch.cook.LiveCard
import app.tempwatch.cook.TempHub
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import app.tempwatch.data.Prefs
import java.text.DateFormat
import java.util.Date

@Composable
fun CookScreen(canScan: Boolean, onRequestPermissions: () -> Unit, onPickTarget: (changing: Boolean) -> Unit) {
    val devices by TempHub.devices.collectAsStateWithLifecycle()
    val cookState by TempHub.cook.collectAsStateWithLifecycle()
    val unit by Prefs.unit.collectAsStateWithLifecycle()
    val scan by BleScanner.status.collectAsStateWithLifecycle()
    val now by rememberNow()
    val context = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }

    val cook = cookState
    val primary = TempHub.primary(devices, cook)
    val device = primary?.first
    val reading = primary?.second
    val ageMs = device?.let { now - it.lastSeen }
    val fresh = ageMs != null && ageMs < 30_000L
    val meat = reading?.internalC ?: cook?.record?.samples?.lastOrNull { it.internalC != null }?.internalC

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TempWatch", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            ConnectionText(device?.name, ageMs, fresh)
        }

        if (cook?.alarmRinging == true) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${cook.record.title} is ready",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        "Hit ${Temps.format(cook.record.targetC, unit)}. Pull it off the heat and let it rest.",
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Button(
                        onClick = { TempHub.stopAlarm() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("Stop alarm") }
                }
            }
        }

        when {
            !canScan -> SectionCard {
                Text("Allow Bluetooth", style = MaterialTheme.typography.titleMedium)
                Text("TempWatch reads the temperatures your TempSpike booster broadcasts. Android needs your OK to listen for nearby devices.")
                Button(onClick = onRequestPermissions) { Text("Allow") }
            }
            scan == BleScanner.Status.BLUETOOTH_OFF -> SectionCard {
                Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                Text("Turn it on to hear your thermometer.")
                Button(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }) { Text("Bluetooth settings") }
            }
            scan == BleScanner.Status.NO_BLUETOOTH -> SectionCard {
                Text("This phone has no Bluetooth LE.", style = MaterialTheme.typography.titleMedium)
            }
            device == null && cook == null -> SectionCard {
                Text("Looking for your TempSpike…", style = MaterialTheme.typography.titleMedium)
                Text("• Batteries in the booster (2 × AAA)\n• Probe pulled out of the booster to wake it\n• Close the TempPro app — the booster may stop broadcasting while that app is connected to it\n• Stay within a few feet for the first connection")
            }
        }

        if (device != null || cook != null) {
            TempDial(
                meatC = meat,
                targetC = cook?.record?.targetC,
                progress = cook?.let { Estimator.progress(it.record.startInternalC, meat, it.record.targetC) },
                unit = unit,
                stale = !fresh,
                staleText = ageMs?.let { "Last reading ${Temps.duration(it)} ago" } ?: "Waiting for the probe…",
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    "Grill", Temps.format(reading?.ambientC, unit), Modifier.weight(1f),
                    valueColor = MaterialTheme.colorScheme.tertiary,
                )
                StatTile("Battery", reading?.batteryPercent?.let { "$it%" } ?: "--", Modifier.weight(1f))
                StatTile("Signal", device?.let { signalWord(it.rssi) } ?: "--", Modifier.weight(1f))
            }
        }

        if (cook == null) {
            Button(
                onClick = { onPickTarget(false) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Start a cook", style = MaterialTheme.typography.titleMedium) }
            Text(
                "Pick the meat and doneness (or your own target). You'll get an alarm when it's done, " +
                    "even with the phone locked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CookCard(cook, meat, unit, now)
            SectionCard {
                TempGraph(
                    samples = cook.record.samples,
                    targetC = cook.record.targetC,
                    unit = unit,
                    endTime = now,
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onPickTarget(true) }, modifier = Modifier.weight(1f)) { Text("Change target") }
                Button(onClick = { confirmEnd = true }, modifier = Modifier.weight(1f)) { Text("End cook") }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End this cook?") },
            text = { Text("It will be saved to History.") },
            confirmButton = {
                TextButton(onClick = { confirmEnd = false; TempHub.endCook() }) { Text("End cook") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep cooking") } },
        )
    }
}

@Composable
private fun ConnectionText(name: String?, ageMs: Long?, fresh: Boolean) {
    val (text, color) = when {
        ageMs == null -> "Searching" to MaterialTheme.colorScheme.onSurfaceVariant
        fresh -> (name?.substringBefore(" (") ?: "Connected") to MaterialTheme.colorScheme.tertiary
        else -> "No signal" to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
}

private fun signalWord(rssi: Int): String = when {
    rssi >= -65 -> "Strong"
    rssi >= -80 -> "Good"
    rssi >= -92 -> "Weak"
    else -> "Poor"
}

/** Big meat temperature inside a 270° ring that fills toward the target. */
@Composable
private fun TempDial(
    meatC: Double?,
    targetC: Double?,
    progress: Float?,
    unit: TempUnit,
    stale: Boolean,
    staleText: String,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Box(modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress != null && progress > 0f) {
                drawArc(fill, 135f, 270f * progress, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Meat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                meatC?.let { "${Temps.whole(it, unit)}°" } ?: "--",
                fontSize = 72.sp,
                fontWeight = FontWeight.Medium,
                color = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            Text(
                if (targetC != null) "Target ${Temps.format(targetC, unit)}" else unit.symbol,
                style = MaterialTheme.typography.titleMedium,
            )
            if (stale) {
                Text(
                    staleText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun CookCard(cook: ActiveCook, meatC: Double?, unit: TempUnit, now: Long) {
    val rec = cook.record
    val est = Estimator.estimate(rec.samples, rec.targetC, now, TempHub.TOLERANCE_C)
    SectionCard {
        Text(rec.title, style = MaterialTheme.typography.titleLarge)
        val reached = rec.reachedAt
        when {
            reached != null -> Text(
                "Hit ${Temps.format(rec.targetC, unit)} at ${clock(reached)}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> {
                Text(LiveCard.estimateText(est), style = MaterialTheme.typography.headlineSmall)
                val minutes = est.minutesLeft
                if (est.status == Estimator.Status.RISING && minutes != null) {
                    Text("Ready around ${clock(now + (minutes * 60_000).toLong())}", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (cook.signalLost) {
            Text(
                "No reading for ${Temps.duration(now - cook.lastReadingAt)}. Bring the phone closer to the booster.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        val parts = buildList {
            add("Cooking ${Temps.duration(rec.durationMs(now))}")
            est.ratePerMin?.let { add(Temps.rate(it, unit)) }
            if (meatC != null && rec.reachedAt == null && meatC < rec.targetC) {
                add("${Temps.whole(rec.targetC, unit) - Temps.whole(meatC, unit)}° to go")
            }
        }
        Text(parts.joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
