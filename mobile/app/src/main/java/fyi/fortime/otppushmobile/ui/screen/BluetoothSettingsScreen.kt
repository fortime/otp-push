package fyi.fortime.otppushmobile.ui.screen

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fyi.fortime.otppushmobile.AppContext
import fyi.fortime.otppushmobile.IntentManager
import java.util.UUID
import kotlin.time.DurationUnit
import kotlin.time.toDuration

private const val LOG_TAG = "BluetoothScreen"

@Composable
fun BluetoothSettingsScreen(
    appContext: AppContext
) {
    val context = LocalContext.current
    val intentManager = IntentManager(context)
    val persistentStore = appContext.persistentStore
    var bleClientConfig by remember { mutableStateOf(persistentStore.getBleClientConfig()) }
    var bleServerConfig by remember { mutableStateOf(persistentStore.getBleServerConfig()) }
    var serverMode by remember { mutableStateOf(persistentStore.isBleServerMode()) }

    var serviceUuid by remember {
        mutableStateOf(
            if (serverMode) {
                bleServerConfig.serviceUuid.toString()
            } else {
                bleClientConfig.serviceUuid.toString()
            }
        )
    }
    var requestCharUuid by remember {
        mutableStateOf(
            if (serverMode) {
                bleServerConfig.requestCharUuid.toString()
            } else {
                bleClientConfig.requestCharUuid.toString()
            }
        )
    }
    var responseCharUuid by remember {
        mutableStateOf(
            if (serverMode) {
                bleServerConfig.responseCharUuid.toString()
            } else {
                bleClientConfig.responseCharUuid.toString()
            }
        )
    }
    var readyDescriptorUuid by remember {
        mutableStateOf(
            if (serverMode) {
                ""
            } else {
                bleClientConfig.readyDescriptorUuid.toString()
            }
        )
    }
    var scanningTimeout by remember {
        mutableStateOf(
            if (serverMode) {
                ""
            } else {
                bleClientConfig.scanningTimeout.toInt(DurationUnit.SECONDS).toString()
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Enable BLE Service", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = appContext.bleEnabled,
                onCheckedChange = {
                    appContext.saveBleEnabled(it)
                    appContext.bleDevices.clear()
                    context.sendBroadcast(intentManager.genBleToggleBroadcastIntent())
                    Log.d(LOG_TAG, "intent sent")
                }
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Mode:", style = MaterialTheme.typography.bodyLarge)

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = serverMode,
                    enabled = !appContext.bleEnabled,
                    onClick = {
                        persistentStore.saveBleServerMode(true)
                        serverMode = true
                        serviceUuid = bleServerConfig.serviceUuid.toString()
                        requestCharUuid = bleServerConfig.requestCharUuid.toString()
                        responseCharUuid = bleServerConfig.responseCharUuid.toString()
                        readyDescriptorUuid = ""
                        scanningTimeout = ""
                    }
                )
                Text(
                    text = "Server",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = !serverMode,
                    enabled = !appContext.bleEnabled,
                    onClick = {
                        persistentStore.saveBleServerMode(false)
                        serverMode = false
                        serviceUuid = bleClientConfig.serviceUuid.toString()
                        requestCharUuid = bleClientConfig.requestCharUuid.toString()
                        responseCharUuid = bleClientConfig.responseCharUuid.toString()
                        readyDescriptorUuid = bleClientConfig.readyDescriptorUuid.toString()
                        scanningTimeout =
                            bleClientConfig.scanningTimeout.toInt(DurationUnit.SECONDS).toString()
                    }
                )
                Text(
                    text = "Client",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        OutlinedTextField(
            value = serviceUuid,
            enabled = !appContext.bleEnabled,
            onValueChange = {
                serviceUuid = it
                tryUuid(serviceUuid)?.let { uuid ->
                    if (serverMode) {
                        bleServerConfig.serviceUuid = uuid
                        persistentStore.saveBleServerConfig(bleServerConfig)
                    } else {
                        bleClientConfig.serviceUuid = uuid
                        persistentStore.saveBleClientConfig(bleClientConfig)
                    }
                }
            },
            label = { Text("Service UUID") }
        )
        OutlinedTextField(
            value = requestCharUuid,
            enabled = !appContext.bleEnabled,
            onValueChange = {
                requestCharUuid = it
                tryUuid(requestCharUuid)?.let { uuid ->
                    if (serverMode) {
                        bleServerConfig.requestCharUuid = uuid
                        persistentStore.saveBleServerConfig(bleServerConfig)
                    } else {
                        bleClientConfig.requestCharUuid = uuid
                        persistentStore.saveBleClientConfig(bleClientConfig)
                    }
                }
            },
            label = { Text("Request Char UUID") }
        )
        OutlinedTextField(
            value = responseCharUuid,
            enabled = !appContext.bleEnabled,
            onValueChange = {
                responseCharUuid = it
                tryUuid(responseCharUuid)?.let { uuid ->
                    if (serverMode) {
                        bleServerConfig.responseCharUuid = uuid
                        persistentStore.saveBleServerConfig(bleServerConfig)
                    } else {
                        bleClientConfig.responseCharUuid = uuid
                        persistentStore.saveBleClientConfig(bleClientConfig)
                    }
                }
            },
            label = { Text("Response Char UUID") }
        )
        if (!serverMode) {
            OutlinedTextField(
                value = readyDescriptorUuid,
                enabled = !appContext.bleEnabled,
                onValueChange = {
                    readyDescriptorUuid = it
                    tryUuid(readyDescriptorUuid)?.let { uuid ->
                        if (!serverMode) {
                            bleClientConfig.readyDescriptorUuid = uuid
                            persistentStore.saveBleClientConfig(bleClientConfig)
                        }
                    }
                },
                label = { Text("Ready Descriptor UUID") }
            )
            OutlinedTextField(
                value = scanningTimeout,
                enabled = !appContext.bleEnabled,
                onValueChange = {
                    scanningTimeout = it
                    scanningTimeout.toIntOrNull()?.let { duration ->
                        if (!serverMode) {
                            bleClientConfig.scanningTimeout =
                                duration.toDuration(DurationUnit.SECONDS)
                            persistentStore.saveBleClientConfig(bleClientConfig)
                        }
                    }
                },
                label = { Text("Scanning Timeout(s)") }
            )
        }
    }
}

private fun tryUuid(s: String): UUID? {
    return try {
        UUID.fromString(s)
    } catch (_: Exception) {
        null
    }
}

