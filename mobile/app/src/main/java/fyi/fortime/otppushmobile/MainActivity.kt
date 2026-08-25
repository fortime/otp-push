package fyi.fortime.otppushmobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import fyi.fortime.otppushmobile.data.OtpRequestDto
import fyi.fortime.otppushmobile.service.BleGattService
import fyi.fortime.otppushmobile.ui.screen.MainContainerScreen
import fyi.fortime.otppushmobile.ui.screen.bleOtpSubmissionScreenHistoryRecord
import fyi.fortime.otppushmobile.ui.screen.httpOtpSubmissionScreenHistoryRecord
import fyi.fortime.otppushmobile.ui.theme.OtpPushMobileTheme
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch

private const val LOG_TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    private lateinit var appContext: AppContext
    private var bleStarted = false

    private val requestNotifyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (!it) {
            Log.w(LOG_TAG, "Notification permissions denied")
        }
    }

    private val requestBlePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        blePermissionCallback(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = AppContext(applicationContext)

        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Automatically start BLE service if enabled in settings
        checkAndStartBleService()

        setContent {
            OtpPushMobileTheme {
                MainApp(intent)
            }
        }
    }

    private fun blePermissionCallback(permissions: Map<String, @JvmSuppressWildcards Boolean>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val advertiseGranted = permissions[Manifest.permission.BLUETOOTH_ADVERTISE] == true
            val connectGranted = permissions[Manifest.permission.BLUETOOTH_CONNECT] == true
            val scanGranted = permissions[Manifest.permission.BLUETOOTH_SCAN] == true
            val accessCoarseLocationGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val accessFineLocationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if (advertiseGranted && connectGranted && scanGranted && accessCoarseLocationGranted && accessFineLocationGranted) {
                startBleService()
            } else {
                Log.w(
                    LOG_TAG,
                    "Bluetooth permissions denied[advertise: $advertiseGranted, connect: $connectGranted, scan: $scanGranted, accessCoarseLocation: $accessCoarseLocationGranted, accessFineLocationGranted: $accessFineLocationGranted], cannot start BLE service"
                )
                appContext.saveBleEnabled(false)
            }
        } else {
            val accessCoarseLocationGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val accessFineLocationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            if (accessCoarseLocationGranted && accessFineLocationGranted) {
                startBleService()
            } else {
                Log.w(
                    LOG_TAG,
                    "Bluetooth permissions denied[accessCoarseLocation: $accessCoarseLocationGranted, accessFineLocationGranted: $accessFineLocationGranted], cannot start BLE service"
                )
                appContext.saveBleEnabled(false)
            }
        }
    }

    private fun checkAndStartBleService() {
        if (!appContext.persistentStore.isBleEnabled() || bleStarted) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBlePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        } else {
            requestBlePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        }
    }

    private fun startBleService() {
        Log.i(LOG_TAG, "Starting BLE GATT Server Service")
        val intent = Intent(this, BleGattService::class.java)
        startForegroundService(intent)
        sendBroadcast(IntentManager(applicationContext).genBleFetchDevicesBroadcastIntent())
        bleStarted = true
    }

    private fun stopBleService() {
        Log.i(LOG_TAG, "Stopping BLE GATT Server Service")
        val intent = Intent(this, BleGattService::class.java)
        stopService(intent)
        bleStarted = false
    }

    private suspend fun handleHttpRequest(requestId: String) {
        val token = appContext.persistentStore.getToken()
        val baseUrl = appContext.persistentStore.getServerUrl()
        if (token != null) {
            appContext.apiClient.safeApiCall(builder = {
                method = HttpMethod.Get
                url("$baseUrl/api/http/mobile/requests/$requestId")
                header("Authorization", "Bearer $token")
            }, serializer = { response -> response.body<OtpRequestDto>() })?.let { request ->
                appContext.history.push(
                    httpOtpSubmissionScreenHistoryRecord(
                        appContext, request
                    )
                )
            }
        }
    }

    private fun handleBleToggle() {
        if (appContext.persistentStore.isBleEnabled() && !bleStarted) {
            checkAndStartBleService()
        } else if (!appContext.persistentStore.isBleEnabled() && bleStarted) {
            stopBleService()
        }
    }

    private fun handleBleRequest(
        deviceAddress: String,
        deviceName: String,
        requestId: String,
        name: String,
        serviceIdentifier: String,
        pubKey: String?
    ) {
        Log.d(
            LOG_TAG,
            "receive a ble request: $deviceName[$deviceAddress], $requestId, $name, $serviceIdentifier, $pubKey"
        )
        appContext.history.push(
            bleOtpSubmissionScreenHistoryRecord(
                appContext, deviceAddress, deviceName, requestId, name, serviceIdentifier, pubKey
            )
        )
    }

    private fun compatRegisterReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag") registerReceiver(receiver, filter)
        }
    }

    @Composable
    fun MainApp(intent: Intent) {

        val scope = rememberCoroutineScope()
        LaunchedEffect(intent) {
            val intentManager = IntentManager(applicationContext)
            intentManager.genHttpRequestListener {
                scope.launch { handleHttpRequest(it) }
            }(intent)
            intentManager.genBleRequestListener { deviceAddress, deviceName, requestId, name, serviceIdentifier, pubKey ->
                handleBleRequest(
                    deviceAddress, deviceName, requestId, name, serviceIdentifier, pubKey
                )
            }(intent)
        }

        SetupIntentReceivers()

        MainContainerScreen(appContext = appContext)
    }

    @Composable
    fun SetupIntentReceivers() {
        val context = LocalContext.current

        // Register broadcast receiver for incoming BLE OTP Requests
        DisposableEffect(context) {
            Log.i(LOG_TAG, "Registering receivers")

            val intentManager = IntentManager(context)

            val bleToggleReceiver = intentManager.genBleToggleReceiver {
                handleBleToggle()
            }
            val bleScanningStateReceiver = intentManager.genBleScanningStateReceiver { state ->
                Log.d(LOG_TAG, "Update scanning state: $state")
                appContext.bleScanning = state
            }
            val bleDeviceClearedReceiver = intentManager.genBleDeviceClearedReceiver {
                appContext.bleDevices.clear()
            }
            val bleDeviceAddedReceiver = intentManager.genBleDeviceAddedReceiver { name, address ->
                appContext.bleDevices.add(Pair(name, address))
            }
            val bleDeviceRemovedReceiver = intentManager.genBleDeviceRemovedReceiver { address ->
                appContext.bleDevices.removeIf { d ->
                    d.second == address
                }
            }

            compatRegisterReceiver(bleToggleReceiver.first, bleToggleReceiver.second)
            compatRegisterReceiver(bleScanningStateReceiver.first, bleScanningStateReceiver.second)
            compatRegisterReceiver(bleDeviceClearedReceiver.first, bleDeviceClearedReceiver.second)
            compatRegisterReceiver(bleDeviceAddedReceiver.first, bleDeviceAddedReceiver.second)
            compatRegisterReceiver(bleDeviceRemovedReceiver.first, bleDeviceRemovedReceiver.second)

            onDispose {
                Log.i(LOG_TAG, "Unregistering receivers")
                context.unregisterReceiver(bleDeviceRemovedReceiver.first)
                context.unregisterReceiver(bleDeviceAddedReceiver.first)
                context.unregisterReceiver(bleDeviceClearedReceiver.first)
                context.unregisterReceiver(bleScanningStateReceiver.first)
                context.unregisterReceiver(bleToggleReceiver.first)
            }
        }
    }
}
