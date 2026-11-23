package app.tempwatch.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tempwatch.ble.BleScanner
import app.tempwatch.ble.ProbeReading
import app.tempwatch.ble.SpikeLink
import app.tempwatch.ble.TempSpikeDecoder
import app.tempwatch.cook.ActiveCook
import app.tempwatch.cook.DonenessLabel
import app.tempwatch.cook.Estimator
import app.tempwatch.cook.HeatScale
import app.tempwatch.cook.TempHub
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import app.tempwatch.data.Prefs
import java.text.DateFormat
import java.util.Date

/** OLED palette shared by the cook screen. */
internal object Oled {
    val card = Color(0xFF0E0E0F)
    val cardBorder = Color(0xFF232325)
    val pill = Color(0xFF1A1A1C)
    val label = Color(0xFF9A9A9E)
    val dimLabel = Color(0xFF8A8A8A)
    val silver = Color(0xFFD5D7DB)
    val grill = Color(0xFF4FD8DF)
    val red = Color(0xFFA9342A)
    val onRed = Color(0xFFF3E9D7)
    val warn = Color(0xFFFF8A7A)
}

@Composable
fun CookScreen(canScan: Boolean, onRequestPermissions: () -> Unit, onPickTarget: (changing: Boolean) -> Unit) {
    val devices by TempHub.devices.collectAsStateWithLifecycle()
    val cookState by TempHub.cook.collectAsStateWithLifecycle()
    val unit by Prefs.unit.collectAsStateWithLifecycle()
    val scan by BleScanner.status.collectAsStateWithLifecycle()
    val links by SpikeLink.states.collectAsStateWithLifecycle()
    val now by rememberNow()
    val context = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }

    val cook = cookState
    val primary = TempHub.primary(devices, cook)
    val device = primary?.first
    val reading = primary?.second
    val ageMs = device?.let { now - it.lastReadingAt }
    // A booster we can hear that hasn't sent temperatures yet (probe still docked, or asleep).
    val waiting = if (device == null) {
        devices.values.filter { it.probes.isEmpty() && now - it.lastSeen < 30_000L }.maxByOrNull { it.lastSeen }
    } else null
    val fresh = ageMs != null && ageMs < 30_000L
    val meat = reading?.internalC ?: cook?.record?.samples?.lastOrNull { it.internalC != null }?.internalC
    val meatColor by animateColorAsState(
        meat?.let { heatColor(it) } ?: Oled.label,
        animationSpec = tween(durationMillis = 900),
        label = "meat colour",
    )

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(
                "TempWatch",
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 26.sp,
                modifier = Modifier.weight(1f),
            )
            ConnectionTag(TempSpikeDecoder.modelName(device?.name ?: waiting?.name), ageMs, fresh, found = waiting != null)
        }

        if (cook?.alarmRinging == true) {
            Column(
                Modifier.fillMaxWidth().background(Oled.red, RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${cook.record.title} is ready", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Oled.onRed)
                Text("Hit ${Temps.format(cook.record.targetC, unit)}. Pull it off the heat and let it rest.", color = Oled.onRed)
                Button(
                    onClick = { TempHub.stopAlarm() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Oled.onRed, contentColor = Oled.red),
                ) { Text("Stop alarm") }
            }
        }

        when {
            !canScan -> SectionCard {
                Text("Allow Bluetooth", style = MaterialTheme.typography.titleMedium)
                Text("TempWatch reads the temperatures your TempSpike booster sends. Android needs your OK to look for nearby devices.")
                RedButton("Allow", onRequestPermissions)
            }
            scan == BleScanner.Status.BLUETOOTH_OFF -> SectionCard {
                Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium)
                Text("Turn it on to hear your thermometer.")
                RedButton("Bluetooth settings", onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                })
            }
            scan == BleScanner.Status.NO_BLUETOOTH -> SectionCard {
                Text("This phone has no Bluetooth LE.", style = MaterialTheme.typography.titleMedium)
            }
            waiting != null && cook == null -> SectionCard {
                val model = TempSpikeDecoder.modelName(waiting.name) ?: "TempSpike"
                when (links[waiting.address]) {
                    SpikeLink.State.CONNECTING, SpikeLink.State.RECONNECTING -> {
                        Text("Connecting to your $model…", style = MaterialTheme.typography.titleMedium)
                        Text("Readings start a few seconds after it connects. If the TempPro app is open, close it: the booster takes one connection at a time.")
                    }
                    SpikeLink.State.CONNECTED -> {
                        Text("Connected to your $model", style = MaterialTheme.typography.titleMedium)
                        Text("Waiting for the probe. Take it out of the booster; readings start within a few seconds.")
                    }
                    SpikeLink.State.NO_PERMISSION -> {
                        Text("Found your $model", style = MaterialTheme.typography.titleMedium)
                        Text("It sends temperatures over a connection, so TempWatch needs the Nearby devices permission to connect.")
                        RedButton("Allow", onRequestPermissions)
                    }
                    SpikeLink.State.UNSUPPORTED -> {
                        Text("Found your $model", style = MaterialTheme.typography.titleMedium)
                        Text("Its connection didn't look as expected. Send a report from Settings → Bluetooth details.")
                    }
                    null -> {
                        Text("Found your $model", style = MaterialTheme.typography.titleMedium)
                        Text("The booster is on, but the probe isn't sending temperatures yet. Take the probe out of the booster; readings start within a few seconds.")
                    }
                }
            }
            // Still searching: the SEARCHING tag at the top says so; no tips card.
        }

        if (device != null || cook != null) {
            ProbeCard(cook, reading, meat, meatColor, unit, now, fresh, ageMs)
        }

        if (cook == null) {
            RedButton("Start a cook", onClick = { onPickTarget(false) }, modifier = Modifier.fillMaxWidth().height(56.dp))
        } else {
            SectionCard {
                TempGraph(
                    samples = cook.record.samples,
                    targetC = cook.record.targetC,
                    unit = unit,
                    endTime = now,
                    meatColor = meatColor,
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { onPickTarget(true) },
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFAEB2B8)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Oled.silver),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("Change target") }
                RedButton("End cook", onClick = { confirmEnd = true }, modifier = Modifier.weight(1f).height(50.dp))
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

/** The one-probe card: title + status tag, battery line, heat ring, and Meat / Target / Grill pills. */
@Composable
private fun ProbeCard(
    cook: ActiveCook?,
    reading: ProbeReading?,
    meat: Double?,
    meatColor: Color,
    unit: TempUnit,
    now: Long,
    fresh: Boolean,
    ageMs: Long?,
) {
    val rec = cook?.record
    val targetC = rec?.targetC
    val range = remember(rec?.startedAt, targetC) { HeatScale.ringRange(rec?.startInternalC ?: meat, targetC) }
    val est = rec?.let { Estimator.estimate(it.samples, it.targetC, now, TempHub.TOLERANCE_C) }

    // Status tag next to the title.
    val (tagText, tagColor) = when {
        cook == null && !fresh -> "NO SIGNAL" to Oled.warn
        cook == null -> "READY" to Oled.grill
        cook.signalLost -> "NO SIGNAL" to Oled.warn
        rec?.reachedAt != null -> "DONE" to meatColor
        else -> (meat?.let { DonenessLabel.forCook(rec?.title.orEmpty(), it) } ?: "COOKING") to meatColor
    }
    val subtitle = buildList {
        reading?.batteryPercent?.let { add("$it%") }
        when {
            !fresh && ageMs != null -> add("Last reading ${Temps.duration(ageMs)} ago")
            rec != null -> add("Cooking ${Temps.duration(rec.durationMs(now))}")
            else -> add("No cook running")
        }
    }.joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .background(Oled.card, RoundedCornerShape(24.dp))
            .border(1.dp, Oled.cardBorder, RoundedCornerShape(24.dp))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(rec?.title ?: "Probe", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
            Row(
                Modifier.border(1.dp, tagColor, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Canvas(Modifier.size(6.dp)) { drawCircle(tagColor) }
                MonoText(tagText, color = tagColor, size = 10.sp)
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            reading?.batteryPercent?.let { BatteryIcon(it, Oled.label) }
            MonoText(subtitle.uppercase(), color = Oled.label, size = 11.sp)
        }

        ProbeRing(
            range = range,
            currentC = meat,
            targetC = targetC,
            unit = unit,
            dim = !fresh,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
        ) {
            RingCenter(cook, est, meat, meatColor, unit, now)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
            StatPill("Meat", Temps.format(meat, unit), meatColor, Modifier.weight(1f))
            StatPill("Target", targetC?.let { Temps.format(it, unit) } ?: "—", if (targetC != null) Color.White else Oled.dimLabel, Modifier.weight(1f))
            StatPill("Grill", Temps.format(reading?.ambientC, unit), Oled.grill, Modifier.weight(1f))
        }
    }
}

/** What sits inside the ring: time left while cooking, otherwise the meat temperature. */
@Composable
private fun RingCenter(cook: ActiveCook?, est: Estimator.Estimate?, meat: Double?, meatColor: Color, unit: TempUnit, now: Long) {
    val rec = cook?.record
    val minutes = est?.minutesLeft
    val showTime = rec != null && rec.reachedAt == null && est?.status == Estimator.Status.RISING && minutes != null

    val label: String
    val labelColor: Color
    val big: String
    val bigColor: Color
    val under: String
    var foot: String? = null
    when {
        rec == null -> {
            label = "PROBE"; labelColor = Oled.dimLabel
            big = meat?.let { "${Temps.whole(it, unit)}°" } ?: "--"; bigColor = meatColor
            under = "LIVE"
        }
        rec.reachedAt != null -> {
            label = "DONE"; labelColor = meatColor
            big = meat?.let { "${Temps.whole(it, unit)}°" } ?: "--"; bigColor = meatColor
            under = "PULL IT & REST"
        }
        showTime -> {
            label = "COOKING"; labelColor = Oled.grill
            big = Temps.timeLeft(minutes!!); bigColor = Color.White
            under = "REMAINING"
            foot = "Ready ~${clock(now + (minutes * 60_000).toLong())}"
        }
        est?.status == Estimator.Status.STALLED -> {
            label = "STALL"; labelColor = Color(0xFFFFB020)
            big = meat?.let { "${Temps.whole(it, unit)}°" } ?: "--"; bigColor = meatColor
            under = "HOLDING STEADY"
        }
        est?.status == Estimator.Status.FALLING -> {
            label = "COOLING"; labelColor = Oled.warn
            big = meat?.let { "${Temps.whole(it, unit)}°" } ?: "--"; bigColor = meatColor
            under = "TEMP DROPPING"
        }
        else -> {
            label = "COOKING"; labelColor = Oled.grill
            big = meat?.let { "${Temps.whole(it, unit)}°" } ?: "--"; bigColor = meatColor
            under = "ESTIMATING TIME"
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MonoText(label, color = labelColor, size = 11.sp, spacing = 3.sp)
        Text(
            big,
            fontSize = if (big.length > 5) 48.sp else 60.sp,
            fontWeight = FontWeight.ExtraLight,
            color = bigColor,
            letterSpacing = (-2).sp,
            textAlign = TextAlign.Center,
        )
        MonoText(under, color = Color(0xFFBDBDBD), size = 11.sp, spacing = 3.sp)
        foot?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, fontSize = 13.sp, fontWeight = FontWeight.Light, color = Oled.label)
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, color = Color(0xFFBDBDBD))
        Text(
            value,
            fontSize = 20.sp,
            color = color,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().background(Oled.pill, RoundedCornerShape(50)).padding(vertical = 10.dp),
        )
    }
}

@Composable
internal fun MonoText(text: String, color: Color, size: TextUnit, spacing: TextUnit = 1.5.sp) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = size, letterSpacing = spacing, color = color, maxLines = 1)
}

@Composable
private fun BatteryIcon(percent: Int, color: Color) {
    Canvas(Modifier.width(18.dp).height(10.dp)) {
        val s = 1.2.dp.toPx()
        val bodyW = size.width - 3.dp.toPx()
        drawRoundRect(color, topLeft = Offset(s / 2, s / 2), size = Size(bodyW - s, size.height - s), cornerRadius = CornerRadius(2.dp.toPx()), style = Stroke(s))
        drawRoundRect(color, topLeft = Offset(bodyW + 0.5.dp.toPx(), size.height * 0.3f), size = Size(2.dp.toPx(), size.height * 0.4f), cornerRadius = CornerRadius(1.dp.toPx()))
        val inset = 2.5.dp.toPx()
        val fillW = (bodyW - inset * 2) * (percent.coerceIn(0, 100) / 100f)
        drawRoundRect(color, topLeft = Offset(inset, inset), size = Size(fillW, size.height - inset * 2), cornerRadius = CornerRadius(1.dp.toPx()))
    }
}

@Composable
internal fun RedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Oled.red, contentColor = Oled.onRed),
        modifier = modifier,
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun ConnectionTag(name: String?, ageMs: Long?, fresh: Boolean, found: Boolean) {
    val (text, color) = when {
        ageMs == null && found -> (name ?: "FOUND") to Oled.label
        ageMs == null -> "SEARCHING" to Oled.label
        fresh -> (name ?: "CONNECTED") to Oled.silver
        else -> "NO SIGNAL" to Oled.warn
    }
    Row(Modifier.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        MonoText(text.uppercase(), color = color, size = 11.sp)
    }
}

internal fun clock(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
