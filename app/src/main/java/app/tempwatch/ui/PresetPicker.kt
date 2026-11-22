package app.tempwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tempwatch.cook.Doneness
import app.tempwatch.cook.MeatPreset
import app.tempwatch.cook.Presets
import app.tempwatch.cook.TempHub
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import app.tempwatch.data.Prefs

/** Pick meat + doneness, or type in your own target. */
@Composable
fun PresetPicker(changing: Boolean, onBack: () -> Unit, onPick: (title: String, targetC: Double) -> Unit) {
    val unit by Prefs.unit.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageHeader(if (changing) "Change target" else "What are you cooking?", onBack)

        for (preset in Presets.all) {
            val expanded = open == preset.id
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { open = if (expanded) null else preset.id }
                        .padding(16.dp),
                ) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    preset.usdaNote?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (expanded) {
                    for (d in preset.options) {
                        HorizontalDivider()
                        DonenessRow(preset, d, unit) {
                            onPick(Presets.title(preset, d), Temps.toCelsius(d.targetF.toDouble(), TempUnit.F))
                        }
                    }
                }
            }
        }

        CustomTarget(unit, onPick)
    }
}

@Composable
private fun DonenessRow(preset: MeatPreset, d: Doneness, unit: TempUnit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.label, style = MaterialTheme.typography.bodyLarge)
            val note = when {
                preset.isBelowUsda(d) -> "Below the USDA minimum"
                else -> d.note
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (preset.isBelowUsda(d)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            Temps.format(Temps.toCelsius(d.targetF.toDouble(), TempUnit.F), unit),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Your own number, in steps of 1 or 5. The probe's meat sensor reads up to 212°F / 100°C. */
@Composable
private fun CustomTarget(unit: TempUnit, onPick: (String, Double) -> Unit) {
    val current = TempHub.cook.value?.record?.targetC
    val range = if (unit == TempUnit.F) 100..212 else 38..100
    var value by remember(unit) {
        mutableIntStateOf((current?.let { Temps.whole(it, unit) } ?: if (unit == TempUnit.F) 165 else 74).coerceIn(range))
    }
    SectionCard {
        Text("Custom target", style = MaterialTheme.typography.titleMedium)
        Text(
            "$value${unit.symbol}",
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (step in listOf(-5, -1, 1, 5)) {
                FilledTonalButton(
                    onClick = { value = (value + step).coerceIn(range) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.weight(1f),
                ) { Text(if (step < 0) "−${-step}" else "+$step") }
            }
        }
        Button(
            onClick = { onPick("Custom · $value${unit.symbol}", Temps.toCelsius(value.toDouble(), unit)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use $value${unit.symbol}") }
    }
}
