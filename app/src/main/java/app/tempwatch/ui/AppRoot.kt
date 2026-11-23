package app.tempwatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import app.tempwatch.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tempwatch.cook.TempHub
import app.tempwatch.data.Prefs

/** Full-screen pages that sit on top of the tabs. */
private sealed interface Page {
    data class Picker(val changing: Boolean) : Page
    data class CookDetail(val id: Long) : Page
    data object Bluetooth : Page
}

@Composable
fun AppRoot(
    canScan: Boolean,
    onRequestPermissions: () -> Unit,
    openPicker: Boolean = false,
    onPickerOpened: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<Page?>(null) }
    // From a widget or the quick-settings tile while nothing is cooking: straight to the presets.
    LaunchedEffect(openPicker) {
        if (openPicker) {
            tab = 0
            if (TempHub.cook.value == null) page = Page.Picker(changing = false)
            onPickerOpened()
        }
    }
    BackHandler(enabled = page != null) { page = null }

    // Keep the screen awake while the app is open during a cook (Settings → While cooking).
    val cook by TempHub.cook.collectAsStateWithLifecycle()
    val keepOn by Prefs.keepScreenOn.collectAsStateWithLifecycle()
    val view = LocalView.current
    val awake = cook != null && keepOn
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        bottomBar = {
            if (page == null) {
                Column {
                    HorizontalDivider(color = Color(0xFF1C1C1E))
                    NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFFF3F3F3),
                            selectedTextColor = Color(0xFFF3F3F3),
                            indicatorColor = Color(0xFF2C2C2E),
                            unselectedIconColor = Color(0xFF8A8A8E),
                            unselectedTextColor = Color(0xFF8A8A8E),
                        )
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 }, colors = colors,
                            icon = { Icon(painterResource(R.drawable.ic_tab_cook), contentDescription = null) },
                            label = { Text("COOK", fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp, fontSize = 11.sp) },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 }, colors = colors,
                            icon = { Icon(painterResource(R.drawable.ic_tab_history), contentDescription = null) },
                            label = { Text("HISTORY", fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp, fontSize = 11.sp) },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 }, colors = colors,
                            icon = { Icon(painterResource(R.drawable.ic_tab_settings), contentDescription = null) },
                            label = { Text("SETTINGS", fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp, fontSize = 11.sp) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val p = page) {
                null -> when (tab) {
                    0 -> CookScreen(
                        canScan = canScan,
                        onRequestPermissions = onRequestPermissions,
                        onPickTarget = { changing -> page = Page.Picker(changing) },
                    )
                    1 -> HistoryScreen(onOpen = { page = Page.CookDetail(it) })
                    else -> SettingsScreen(
                        canScan = canScan,
                        onRequestPermissions = onRequestPermissions,
                        onBluetoothDetails = { page = Page.Bluetooth },
                    )
                }
                is Page.Picker -> PresetPicker(
                    changing = p.changing,
                    onBack = { page = null },
                    onPick = { title, targetC ->
                        if (p.changing) TempHub.changeTarget(title, targetC) else TempHub.startCook(title, targetC)
                        page = null
                        tab = 0
                    },
                )
                is Page.CookDetail -> CookDetailScreen(id = p.id, onBack = { page = null })
                Page.Bluetooth -> BluetoothDetailsScreen(onBack = { page = null })
            }
        }
    }
}
