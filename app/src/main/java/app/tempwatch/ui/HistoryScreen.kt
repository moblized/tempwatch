package app.tempwatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tempwatch.cook.CookRecord
import app.tempwatch.cook.CookStore
import app.tempwatch.cook.TempHub
import app.tempwatch.cook.TempUnit
import app.tempwatch.cook.Temps
import app.tempwatch.data.Prefs
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HistoryScreen(onOpen: (Long) -> Unit) {
    val context = LocalContext.current
    val version by TempHub.historyVersion.collectAsStateWithLifecycle()
    val unit by Prefs.unit.collectAsStateWithLifecycle()
    var cooks by remember { mutableStateOf<List<CookRecord>?>(null) }
    LaunchedEffect(version) {
        cooks = withContext(Dispatchers.IO) { CookStore.list(context) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("History", style = MaterialTheme.typography.headlineMedium) }
        val list = cooks
        when {
            list == null -> Unit
            list.isEmpty() -> item {
                Text(
                    "Finished cooks show up here, with their temperature graph.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(list, key = { it.id }) { rec -> CookRow(rec, unit) { onOpen(rec.id) } }
        }
    }
}

@Composable
private fun CookRow(rec: CookRecord, unit: TempUnit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(rec.title, style = MaterialTheme.typography.titleMedium)
            Text(dateTime(rec.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(
                    Temps.duration(rec.durationMs(rec.endedAt ?: rec.startedAt)),
                    "target ${Temps.format(rec.targetC, unit)}",
                    rec.peakInternalC?.let { "peak ${Temps.format(it, unit)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (rec.reachedAt != null) {
                Text(
                    "Hit target after ${Temps.duration(rec.reachedAt - rec.startedAt)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun CookDetailScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val unit by Prefs.unit.collectAsStateWithLifecycle()
    var rec by remember { mutableStateOf<CookRecord?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        rec = withContext(Dispatchers.IO) { CookStore.load(context, id) }
        loaded = true
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val r = rec
        PageHeader(r?.title ?: "Cook", onBack)
        if (r == null) {
            if (loaded) Text("This cook couldn't be opened.")
        } else {
            CookDetails(r, unit, onDelete = { confirmDelete = true })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this cook?") },
            text = { Text("Its graph and stats will be gone for good.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    TempHub.deleteCook(id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CookDetails(r: CookRecord, unit: TempUnit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(dateTime(r.startedAt), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Cook time", Temps.duration(r.durationMs(r.endedAt ?: r.startedAt)), Modifier.weight(1f))
            StatTile("Target", Temps.format(r.targetC, unit), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "Peak meat", Temps.format(r.peakInternalC, unit), Modifier.weight(1f),
                valueColor = r.peakInternalC?.let { heatColor(it) } ?: MaterialTheme.colorScheme.primary,
            )
            StatTile(
                "Peak grill", Temps.format(r.peakAmbientC, unit), Modifier.weight(1f),
                valueColor = MaterialTheme.colorScheme.tertiary,
            )
        }
        val reached = r.reachedAt
        Text(
            if (reached != null) "Hit target after ${Temps.duration(reached - r.startedAt)} (${clock(reached)})"
            else "Ended before reaching the target",
            style = MaterialTheme.typography.bodyLarge,
        )
        SectionCard {
            TempGraph(
                samples = r.samples,
                targetC = r.targetC,
                unit = unit,
                endTime = r.endedAt,
                meatColor = r.peakInternalC?.let { heatColor(it) } ?: MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().height(260.dp),
            )
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete this cook") }
    }
}

private fun dateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
