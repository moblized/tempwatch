package app.tempwatch.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

// Ember orange for the meat, teal for the grill air. Follows the phone's light/dark setting
// (so does the launcher icon, through its night resources).
private val Light = lightColorScheme(
    primary = Color(0xFFB83E0C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0A00),
    secondary = Color(0xFF77574C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF2C160D),
    tertiary = Color(0xFF00696E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF0F6),
    onTertiaryContainer = Color(0xFF002022),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF231915),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF231915),
    surfaceVariant = Color(0xFFF5DED6),
    onSurfaceVariant = Color(0xFF53433E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EC),
    surfaceContainer = Color(0xFFFCEAE4),
    surfaceContainerHigh = Color(0xFFF6E4DE),
    surfaceContainerHighest = Color(0xFFF0DFD9),
    outline = Color(0xFF85736D),
    outlineVariant = Color(0xFFD8C2BB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5A1A00),
    primaryContainer = Color(0xFF7E2C0C),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFF4FD8DF),
    onTertiary = Color(0xFF00373A),
    tertiaryContainer = Color(0xFF004F53),
    onTertiaryContainer = Color(0xFF9CF0F6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF1DFD9),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF1DFD9),
    surfaceVariant = Color(0xFF3A3330),
    onSurfaceVariant = Color(0xFFD8C2BB),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF151211),
    surfaceContainer = Color(0xFF1C1816),
    surfaceContainerHigh = Color(0xFF262220),
    surfaceContainerHighest = Color(0xFF312C2A),
    outline = Color(0xFFA08D86),
    outlineVariant = Color(0xFF53433E),
)

@Composable
fun TempWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Wall-clock time that ticks every second, for "seen 5 s ago" and elapsed times. */
@Composable
fun rememberNow(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(1_000L)
        value = System.currentTimeMillis()
    }
}
