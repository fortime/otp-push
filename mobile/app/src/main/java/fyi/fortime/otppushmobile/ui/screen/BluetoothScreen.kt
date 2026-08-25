package fyi.fortime.otppushmobile.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fyi.fortime.otppushmobile.AppContext
import fyi.fortime.otppushmobile.HistoryRecord
import fyi.fortime.otppushmobile.ui.component.MenuItem

@Composable
fun BluetoothScreen(
    appContext: AppContext,
) {
    val serverMode = appContext.persistentStore.isBleServerMode()
    val title = if (serverMode) {
        "Client Devices"
    } else {
        "Server Devices"
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MenuItem(
            icon = Icons.AutoMirrored.Filled.List,
            title = title,
            subtitle = "Connected devices",
            enabled = appContext.bleEnabled,
            onClick = {
                appContext.history.push(HistoryRecord(title, false, {
                    if (appContext.bleEnabled) {
                        if (appContext.persistentStore.isBleServerMode()) {
                            BluetoothClientDevicesScreen(appContext)
                        } else {
                            BluetoothServerDevicesScreen(appContext)
                        }
                    }
                }))
            },
        )
        MenuItem(
            icon = Icons.Default.Settings,
            title = "Settings",
            subtitle = "",
            onClick = {
                appContext.history.push(HistoryRecord("Bluetooth Settings", false, {
                    BluetoothSettingsScreen(appContext)
                }))
            },
        )
    }
}
