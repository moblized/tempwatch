package app.tempwatch.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

// Always dark, tuned for OLED: true black backgrounds (those pixels stay off), near-black
// panels with hairline edges, silver text accents, ember red for the main buttons and teal for
// the grill. The meat temperature itself is coloured by HeatScale. The launcher icon is the
// dark version too, since the app is always dark.
private val Oled = darkColorScheme(
    primary = Color(0xFFA9342A),
    onPrimary = Color(0xFFF3E9D7),
    primaryContainer = Color(0xFF3A1A16),
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFFD5D7DB),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE6E6E8),
    tertiary = Color(0xFF4FD8DF),
    onTertiary = Color(0xFF00373A),
    tertiaryContainer = Color(0xFF0E2C2E),
    onTertiaryContainer = Color(0xFF9CF0F6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF3F3F3),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF3F3F3),
    surfaceVariant = Color(0xFF1A1A1C),
    onSurfaceVariant = Color(0xFF9A9A9E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0A0A0B),
    surfaceContainer = Color(0xFF0E0E0F),
    surfaceContainerHigh = Color(0xFF121213),
    surfaceContainerHighest = Color(0xFF161618),
    outline = Color(0xFF3A3A3C),
    outlineVariant = Color(0xFF232325),
    error = Color(0xFFFF8A7A),
    onError = Color(0xFF000000),
)

@Composable
fun TempWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Oled, content = content)
}

/** Wall-clock time that ticks every second, for "seen 5 s ago" and elapsed times. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1_000L)
        value = System.currentTimeMillis()
    }
}
