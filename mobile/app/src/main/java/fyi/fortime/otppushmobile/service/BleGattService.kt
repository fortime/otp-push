package fyi.fortime.otppushmobile.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import fyi.fortime.otppushmobile.AppContext
import fyi.fortime.otppushmobile.IntentManager
import fyi.fortime.otppushmobile.IntentManager.Companion.EXTRA_NAME
import fyi.fortime.otppushmobile.IntentManager.Companion.EXTRA_PUB_KEY
import fyi.fortime.otppushmobile.IntentManager.Companion.EXTRA_REQUEST_ID
import fyi.fortime.otppushmobile.IntentManager.Companion.EXTRA_SERVICE_IDENTIFIER
import fyi.fortime.otppushmobile.R
import fyi.fortime.otppushmobile.data.BleClientConfig
import fyi.fortime.otppushmobile.data.BleServerConfig
import fyi.fortime.otppushmobile.util.NotifyClient
import io.ktor.util.collections.ConcurrentSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.internal.notifyAll
import okhttp3.internal.wait
import org.json.JSONObject
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val LOG_TAG = "BleGattService"

class GattClient(val name: String) {
    val reqBuf = ArrayDeque<Byte>()
    var curRequestId: String? = null
    var resp: ByteArray? = null
}

class GattServer(val device: BluetoothDevice, val name: String, val gattConn: BluetoothGatt) {
    val reqBuf = ArrayDeque<Byte>()
    var curRequestId: String? = null
}

enum class ScanningEvent {
    FORCE_START, START, EXIT
}

class BleGattService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 9912
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val INIT_SCAN_INTERVAL = 5.seconds
    }

    private val clientDevices = ConcurrentHashMap<String, GattClient>()
    private val serverDevices = ConcurrentHashMap<String, GattServer>()
    private val lastConnecteds = ConcurrentHashMap<String, LocalDateTime>()
    private val newDevices = ConcurrentSet<String>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var appContext: AppContext
    private lateinit var intentManager: IntentManager
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var notifyClient: NotifyClient
    private lateinit var bleClientConfig: BleClientConfig
    private lateinit var bleServerConfig: BleServerConfig
    private lateinit var adapter: BluetoothAdapter
    private var started: Boolean = false
    private var serverMode: Boolean = true
    private var scanningLock = object {}
    private var scanning: Boolean = false
    private var scanningVersion: Int = 0
    private var scanningEventChannel: Channel<ScanningEvent>? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var bleResponseReceiver: BroadcastReceiver? = null
    private var bleScanningToggleReceiver: BroadcastReceiver? = null
    private var bleRemoveDeviceReceiver: BroadcastReceiver? = null
    private var bleFetchDevicesReceiver: BroadcastReceiver? = null
    private var bluetoothStateReceiver: BroadcastReceiver? = null

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ]
    )
    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_TAG, "Creating service")

        appContext = AppContext(applicationContext)
        intentManager = IntentManager(applicationContext)
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        notifyClient =
            NotifyClient(
                applicationContext,
                "otp_ble_requests",
                "OTP BLE Requests",
                "Channel for OTP BLE requests"
            )

        bleClientConfig = appContext.persistentStore.getBleClientConfig()
        bleServerConfig = appContext.persistentStore.getBleServerConfig()
        serverMode = appContext.persistentStore.isBleServerMode()

        setupReceivers()
        startForegroundService()

        adapter = bluetoothManager.adapter ?: return
        start()
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ]
    )
    @Synchronized
    fun start() {
        Log.i(LOG_TAG, "Starting service")
        if (!started) {
            started = true
            if (serverMode) {
                setupGattServer()
            } else {
                scope.launch {
                    runScanningMonitor()
                }
            }
        }
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ]
    )
    suspend fun runScanningMonitor() {
        val channel = Channel<ScanningEvent>(Channel.UNLIMITED)
        scanningEventChannel = channel
        scanningVersion += 1
        val curScanningVersion = scanningVersion
        // attempt to scan devices if there is no connected device
        var interval = INIT_SCAN_INTERVAL
        var event: ScanningEvent? = null
        while (started && curScanningVersion == scanningVersion) {
            Log.d(LOG_TAG, "New scanning event: $event")
            if (!scanning &&
                ((serverDevices.isEmpty() && event == null)
                        || event == ScanningEvent.FORCE_START
                        || event == ScanningEvent.START)
            ) {
                if (event == ScanningEvent.FORCE_START) {
                    newDevices.clear()
                    sendBroadcast(intentManager.genBleDeviceClearedBroadcastIntent())
                }
                startScanning()
                // Stop scanning in timeout
                delay(bleClientConfig.scanningTimeout)
                stopScanning()

                // Remove devices not in newDevices
                val unfoundDevices = mutableListOf<String>()
                for (address in serverDevices.keys()) {
                    if (!newDevices.contains(address)) {
                        unfoundDevices.add(address)
                    }
                }
                for (address in unfoundDevices) {
                    removeServer(address)
                }

                if (serverDevices.isEmpty()) {
                    interval *= 2
                    if (interval > 10.minutes) {
                        interval = 10.minutes
                    }
                    Log.i(LOG_TAG, "No server devices found, scan in $interval")
                } else {
                    interval = INIT_SCAN_INTERVAL
                }
            } else if (!scanning) {
                // check if any devices is disconnected
                val disconnectedDevices = mutableListOf<String>()
                for ((address, gattServer) in serverDevices) {
                    if (bluetoothManager.getConnectionState(
                            gattServer.device,
                            BluetoothProfile.GATT_SERVER
                        ) != BluetoothProfile.STATE_CONNECTED
                    ) {
                        disconnectedDevices.add(address)
                    }
                }
                for (address in disconnectedDevices) {
                    removeServer(address)
                }
            }
            event = withTimeoutOrNull(interval) {
                channel.receive()
            }
            if (event != null) {
                // drain all events and use the last one
                while (true) {
                    val result = channel.tryReceive()
                    when {
                        result.isSuccess -> {
                            event = result.getOrNull()
                        }

                        result.isClosed -> {
                            val cause = result.exceptionOrNull()
                            Log.e(LOG_TAG, "Channel closed with cause: $cause")
                            return
                        }

                        result.isFailure -> {
                            break
                        }
                    }
                }
            }
            if (event != null && event == ScanningEvent.EXIT) {
                break
            }
        }
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ]
    )
    override fun onDestroy() {
        Log.i(LOG_TAG, "Destroying service")
        stop()
        destroyReceivers()
        job.cancel()
        super.onDestroy()
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ]
    )
    @Synchronized
    fun stop() {
        Log.i(LOG_TAG, "Stoping service")
        if (started) {
            started = false
            if (serverMode) {
                stopAdvertising()
                clientDevices.clear()
                bluetoothGattServer?.close()
            } else {
                stopScanning()
                scope.launch {
                    scanningEventChannel?.send(ScanningEvent.EXIT)
                }
                serverDevices.forEach { (_, s) ->
                    s.gattConn.close()
                }
                serverDevices.clear()
                lastConnecteds.clear()
            }
            sendBroadcast(intentManager.genBleDeviceClearedBroadcastIntent())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ]
    )
    private fun setupReceivers() {
        val (tmpBleResponseReceiver, bleResponseReceiverFilter) = intentManager.genBleResponseReceiver { deviceAddress, requestId, otpCode, errMsg ->
            if (serverMode) {
                sendBleResponseToClient(deviceAddress, requestId, otpCode, errMsg)
            } else {
                sendBleResponseToServer(deviceAddress, requestId, otpCode, errMsg)
            }
        }

        val (tmpBleScanningToggleReceiver, bleScanningToggleReceiverFilter) = intentManager.genBleScanningToggleReceiver { toggle ->
            if (toggle) {
                scope.launch {
                    scanningEventChannel?.send(ScanningEvent.START)
                }
            } else {
                stopScanning()
            }
        }

        val (tmpBleRemoveDeviceReceiver, bleRemoveDeviceReceiverFilter) = intentManager.genBleRemoveDeviceReceiver { address ->
            if (serverMode) {
                removeClient(address)
            } else {
                removeServer(address)
            }
        }

        val (tmpBleFetchDevicesDeviceReceiver, bleFetchDevicesReceiverFilter) = intentManager.genBleFetchDevicesReceiver {
            if (serverMode) {
                for ((address, gattClient) in clientDevices) {
                    sendBroadcast(
                        intentManager.genBleDeviceAddedBroadcastIntent(
                            gattClient.name,
                            address
                        )
                    )
                }
            } else {
                for ((address, gattServer) in serverDevices) {
                    sendBroadcast(
                        intentManager.genBleDeviceAddedBroadcastIntent(
                            gattServer.name,
                            address
                        )
                    )
                }
            }
        }

        val (tmpBluetoothStateReceiver, bluetoothStateReceiverFilter) = intentManager.genBluetoothStateReceiver { intent ->
            when (intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR
            )) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(LOG_TAG, "Bluetooth turned on")
                    start()
                }

                BluetoothAdapter.STATE_OFF -> {
                    Log.d(LOG_TAG, "Bluetooth turned off")
                    stop()
                }
            }
        }

        compatRegisterReceiver(tmpBleResponseReceiver, bleResponseReceiverFilter)
        bleResponseReceiver = tmpBleResponseReceiver
        compatRegisterReceiver(tmpBleScanningToggleReceiver, bleScanningToggleReceiverFilter)
        bleScanningToggleReceiver = tmpBleScanningToggleReceiver
        compatRegisterReceiver(tmpBleRemoveDeviceReceiver, bleRemoveDeviceReceiverFilter)
        bleRemoveDeviceReceiver = tmpBleRemoveDeviceReceiver
        compatRegisterReceiver(tmpBleFetchDevicesDeviceReceiver, bleFetchDevicesReceiverFilter)
        bleFetchDevicesReceiver = tmpBleFetchDevicesDeviceReceiver
        compatRegisterReceiver(tmpBluetoothStateReceiver, bluetoothStateReceiverFilter)
        bluetoothStateReceiver = tmpBluetoothStateReceiver
    }

    private fun destroyReceivers() {
        if (bluetoothStateReceiver != null) {
            unregisterReceiver(bluetoothStateReceiver)
            bluetoothStateReceiver = null
        }
        if (bleFetchDevicesReceiver != null) {
            unregisterReceiver(bleFetchDevicesReceiver)
            bleFetchDevicesReceiver = null
        }
        if (bleRemoveDeviceReceiver != null) {
            unregisterReceiver(bleRemoveDeviceReceiver)
            bleRemoveDeviceReceiver = null
        }
        if (bleScanningToggleReceiver != null) {
            unregisterReceiver(bleScanningToggleReceiver)
            bleScanningToggleReceiver = null
        }
        if (bleResponseReceiver != null) {
            unregisterReceiver(bleResponseReceiver)
            bleResponseReceiver = null
        }
    }

    private fun compatRegisterReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    private fun startForegroundService() {
        val channelId = "otp_ble_service"
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "OTP BLE Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service for handling BLE requests"
        }
        notificationManager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OTP BLE Service")
            .setContentText("Waiting OTP BLE Requests")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        val gattServerCallback = object : BluetoothGattServerCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(
                device: BluetoothDevice,
                status: Int,
                newState: Int
            ) {
                Log.d(LOG_TAG, "Connection state change: status=$status, newState=$newState")
                val address = device.address ?: run {
                    Log.w(LOG_TAG, "No ble device address")
                    return
                }
                if (address == "00:00:00:00:00:00") {
                    Log.w(LOG_TAG, "Null ble device address is not supported")
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    val name = device.name ?: "Unknown"
                    clientDevices.putIfAbsent(address, GattClient(name))
                    Log.d(LOG_TAG, "Add client device[$name/$address]")
                    sendBroadcast(intentManager.genBleDeviceAddedBroadcastIntent(name, address))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    removeClient(address)
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                val gattServer = bluetoothGattServer ?: return

                if (characteristic.uuid != bleServerConfig.responseCharUuid) {
                    return
                }

                Log.d(
                    LOG_TAG,
                    "Characteristic read request: ${characteristic.uuid}"
                )

                val address = device.address ?: run {
                    Log.d(LOG_TAG, "No ble device address on request")
                    return
                }
                val gattClient = clientDevices[address] ?: run {
                    Log.w(LOG_TAG, "Not connected device: ${device.name}")
                    return
                }

                synchronized(gattClient) {
                    var resp = gattClient.resp
                    while (resp == null) {
                        gattClient.wait()
                        resp = gattClient.resp
                    }

                    if (offset > resp.size) {
                        gattServer.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_INVALID_OFFSET,
                            offset,
                            null
                        )
                        return
                    }

                    val chunk = resp.copyOfRange(
                        offset,
                        resp.size
                    )
                    gattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        offset,
                        chunk
                    )
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                val gattServer = bluetoothGattServer ?: return

                // We have gotten the value, confirm the request
                if (responseNeeded) {
                    gattServer.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        null
                    )
                }

                if (characteristic.uuid != bleServerConfig.requestCharUuid) {
                    return
                }

                Log.d(
                    LOG_TAG,
                    "Characteristic write request: ${characteristic.uuid}, value size=${value.size}"
                )

                val address = device.address ?: run {
                    Log.d(LOG_TAG, "No ble device address on request")
                    return
                }

                val gattClient = clientDevices[address] ?: run {
                    Log.w(LOG_TAG, "Not connected device: ${device.name}")
                    return
                }

                synchronized(gattClient) {
                    val reqBuf = gattClient.reqBuf
                    reqBuf.addAll(value.asIterable())
                    if (reqBuf.size >= 4) {
                        val len = ((reqBuf.elementAt(0).toInt() and 0xFF) shl 24) or
                                ((reqBuf.elementAt(1).toInt() and 0xFF) shl 16) or
                                ((reqBuf.elementAt(2).toInt() and 0xFF) shl 8) or
                                (reqBuf.elementAt(3).toInt() and 0xFF)
                        if (reqBuf.size >= 4 + len) {
                            repeat(4) {
                                reqBuf.removeFirst()
                            }
                            val msgBytes = List(len) { reqBuf.removeFirst() }
                            val jsonStr = String(msgBytes.toByteArray(), Charsets.UTF_8)
                            gattClient.curRequestId =
                                handleIncomingBleRequest(address, gattClient.name, jsonStr, null)
                            if (gattClient.curRequestId != null) {
                                gattClient.resp = null
                            }
                        }
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
            override fun onServiceAdded(
                status: Int,
                service: BluetoothGattService
            ) {
                Log.d(
                    LOG_TAG,
                    "onServiceAdded status=$status uuid=${service.uuid}"
                )

                if (status == BluetoothGatt.GATT_SUCCESS && service.uuid == bleServerConfig.serviceUuid) {
                    startAdvertising()
                }
            }
        }

        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        val service = BluetoothGattService(
            bleServerConfig.serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val requestChar = BluetoothGattCharacteristic(
            bleServerConfig.requestCharUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
        )

        val responseChar = BluetoothGattCharacteristic(
            bleServerConfig.responseCharUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM
        )

        service.addCharacteristic(requestChar)
        service.addCharacteristic(responseChar)

        bluetoothGattServer?.addService(service)
        Log.i(LOG_TAG, "GATT Server configured with custom service and characteristics")
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        val gattServer = bluetoothGattServer ?: return
        Log.i(LOG_TAG, "Start le advertising: ${gattServer.services}")
        Log.d(
            LOG_TAG,
            "adapter: enabled=${adapter.isEnabled}, multipleAdvertisementSupported=${adapter.isMultipleAdvertisementSupported}, le2M=${adapter.isLe2MPhySupported}, leCoded=${adapter.isLeCodedPhySupported}, advertiser=${adapter.bluetoothLeAdvertiser}"
        )
        if (!adapter.isEnabled) {
            Log.w(LOG_TAG, "Bluetooth is disabled, cannot start advertising")
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(bleServerConfig.serviceUuid))
            .build()

        val tmpAdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                Log.i(LOG_TAG, "LE Advertising started successfully")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.e(LOG_TAG, "LE Advertising failed to start: errorCode=$errorCode")
            }
        }

        advertiseCallback = null
        advertiser.startAdvertising(settings, data, tmpAdvertiseCallback)
        advertiseCallback = tmpAdvertiseCallback
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertising() {
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val callback = advertiseCallback ?: return
        advertiseCallback = null
        advertiser.stopAdvertising(callback)
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ]
    )
    private fun startScanning() {
        synchronized(scanningLock) {
            if (scanning) {
                return
            }
            scanning = true
            sendBroadcast(intentManager.genBleScanningStateBroadcastIntent(true))
            Log.d(LOG_TAG, "Starting scanning servers: ${bleClientConfig.serviceUuid}")

            val scanner = adapter.bluetoothLeScanner ?: return
            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(bleClientConfig.serviceUuid))
                .build()

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)
                .build()

            val tmpScanCallback = object : ScanCallback() {
                @RequiresPermission(
                    allOf = [
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                    ]
                )
                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult,
                ) {
                    val device = result.device
                    val address = device.address ?: return

                    if (serverDevices.containsKey(address)) {
                        Log.d(LOG_TAG, "Already connected to device: $address, skipping")
                        return
                    }

                    val lastConnected = lastConnecteds[address]
                    if (lastConnected != null && lastConnected.plusMinutes(5) < LocalDateTime.now()) {
                        Log.d(
                            LOG_TAG,
                            "Try connecting to device in last 5 minutes: $address, skipping"
                        )
                        return
                    }

                    lastConnecteds[address] = LocalDateTime.now()

                    Log.d(
                        LOG_TAG,
                        "Found GATT server: address=$address, name=${device.name}, rssi=${result.rssi}",
                    )

                    connectServer(device)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(LOG_TAG, "BLE scan failed: $errorCode, disable ble")
                    appContext.persistentStore.saveBleEnabled(false)
                    applicationContext.sendBroadcast(intentManager.genBleToggleBroadcastIntent())
                }
            }

            scanCallback = null
            scanner.startScan(
                listOf(scanFilter),
                scanSettings,
                tmpScanCallback,
            )
            scanCallback = tmpScanCallback
            Log.d(LOG_TAG, "Scanning servers")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun stopScanning() {
        synchronized(scanningLock) {
            if (scanning) {
                scanning = false
                sendBroadcast(intentManager.genBleScanningStateBroadcastIntent(false))
                Log.d(LOG_TAG, "Stopping scanning servers")

                val scanner = adapter.bluetoothLeScanner ?: return
                val callback = scanCallback ?: return
                scanCallback = null
                scanner.stopScan(callback)
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectServer(device: BluetoothDevice) {
        val address = device.address ?: return

        val gattClientCallback = object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                Log.d(
                    LOG_TAG,
                    "Connection state changed: status=$status, newState=$newState",
                )

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(LOG_TAG, "GATT connection failed: $status")
                    removeServer(address)
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(LOG_TAG, "Connected to $address")

                        // GATT operations must be performed asynchronously.
                        if (!gatt.discoverServices()) {
                            Log.e(LOG_TAG, "discoverServices() failed to start")
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(LOG_TAG, "Disconnected from $address")
                        removeServer(address)
                        lastConnecteds.remove(address)
                        if (serverDevices.isEmpty()) {
                            // signal to startScanning
                            scope.launch {
                                scanningEventChannel?.send(ScanningEvent.FORCE_START)
                            }
                        }
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                Log.d(LOG_TAG, "Services discovered: ${gatt.device.name}/$address")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(LOG_TAG, "Service discovery failed: $status")
                    removeServer(address)
                    return
                }

                val service = gatt.getService(bleClientConfig.serviceUuid)

                if (service == null) {
                    Log.e(LOG_TAG, "Service ${bleClientConfig.serviceUuid} was not found")
                    return
                }

                Log.d(LOG_TAG, "Found service ${bleClientConfig.serviceUuid}")

                val request = service.getCharacteristic(bleClientConfig.requestCharUuid) ?: run {

                    Log.w(LOG_TAG, "No request char found")
                    return@onServicesDiscovered
                }
                service.getCharacteristic(bleClientConfig.responseCharUuid) ?: run {
                    Log.w(LOG_TAG, "No response char found")
                    return@onServicesDiscovered
                }

                // Enable notification
                val descriptor = request.getDescriptor(CCCD_UUID) ?: run {
                    Log.w(LOG_TAG, "No notification descriptor for request char")
                    return@onServicesDiscovered
                }

                gatt.setCharacteristicNotification(request, true)

                // turn it off then turn on
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            @Deprecated(
                message = "for legacy os support",
                replaceWith = ReplaceWith("onCharacteristicRead(gatt, characteristic, status)")
            )
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                @Suppress("DEPRECATION")
                onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(
                        LOG_TAG,
                        "Characteristic read failed: uuid=${characteristic.uuid}, status=$status",
                    )
                    return
                }

                if (characteristic.uuid != bleClientConfig.requestCharUuid) {
                    return
                }

                Log.d(
                    LOG_TAG,
                    "Characteristic read: uuid=${characteristic.uuid}, value=${value.toHexString()}",
                )

                val gattServer = serverDevices[address] ?: run {
                    Log.w(LOG_TAG, "Not connected device: ${device.name}")
                    return
                }

                synchronized(gattServer) {
                    val reqBuf = gattServer.reqBuf
                    reqBuf.addAll(value.asIterable())
                    if (reqBuf.size >= 4) {
                        val len = ((reqBuf.elementAt(0).toInt() and 0xFF) shl 24) or
                                ((reqBuf.elementAt(1).toInt() and 0xFF) shl 16) or
                                ((reqBuf.elementAt(2).toInt() and 0xFF) shl 8) or
                                (reqBuf.elementAt(3).toInt() and 0xFF)
                        if (reqBuf.size >= 4 + len) {
                            repeat(4) {
                                reqBuf.removeFirst()
                            }
                            val msgBytes = List(len) { reqBuf.removeFirst() }
                            val jsonStr = String(msgBytes.toByteArray(), Charsets.UTF_8)
                            handleIncomingBleRequest(
                                address,
                                gattServer.name,
                                jsonStr,
                                gattServer.curRequestId
                            )
                        }
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            @Deprecated(
                message = "for legacy os support",
                replaceWith = ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)")
            )
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                @Suppress("DEPRECATION")
                onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid != bleClientConfig.requestCharUuid) {
                    return
                }

                Log.d(
                    LOG_TAG,
                    "Characteristic changed: uuid=${characteristic.uuid}, value=${value.toHexString()}",
                )

                val gattServer = serverDevices[address] ?: run {
                    Log.w(LOG_TAG, "Not connected device: ${device.name}")
                    return
                }
                synchronized(gattServer) {
                    try {
                        gattServer.curRequestId = uuidFromBytes(value).toString()
                        Log.i(
                            LOG_TAG,
                            "Starting to read request[${gattServer.curRequestId}] from: ${device.name}"
                        )
                        gattServer.gattConn.readCharacteristic(characteristic)
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Failed to parse request id: ", e)
                        gattServer.curRequestId = null
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            @Deprecated(
                message = "for legacy os support",
                replaceWith = ReplaceWith("onDescriptorRead(gatt, descriptor, status, value)")
            )
            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                @Suppress("DEPRECATION")
                onDescriptorRead(gatt, descriptor, status, descriptor.value ?: ByteArray(0))
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onDescriptorRead(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
                value: ByteArray
            ) {
                Log.d(
                    LOG_TAG,
                    "Descriptor read: char=${descriptor.characteristic.uuid}, uuid=${descriptor.uuid}, status=$status, value=${value.toHexString()}",
                )

                if (descriptor.characteristic.uuid == bleClientConfig.requestCharUuid && descriptor.uuid == CCCD_UUID) {
                    if (!BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.contentEquals(value)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            gatt.writeDescriptor(descriptor)
                        }
                    } else {
                        for (d in descriptor.characteristic.descriptors ?: listOf()) {
                            if (d != null && d.uuid == bleClientConfig.readyDescriptorUuid) {
                                // read ready descriptor
                                gatt.readDescriptor(d)
                                break
                            }
                        }
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                Log.d(
                    LOG_TAG,
                    "Descriptor write: char=${descriptor.characteristic.uuid}, uuid=${descriptor.uuid}, status=$status",
                )
                gatt.readDescriptor(descriptor)
            }
        }

        val gattConn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            val settings =
                BluetoothGattConnectionSettings.Builder().setTransport(BluetoothDevice.TRANSPORT_LE)
                    .setAutoConnectEnabled(false).build()
            device.connectGatt(settings, Executors.newSingleThreadExecutor(), gattClientCallback) ?: run {
                Log.e(LOG_TAG, "Unable to create gatt connection to the servers")
                return
            }
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(this, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        }
        val name = device.name ?: "Unknown"
        serverDevices[address] = GattServer(device, name, gattConn)
        newDevices.add(address)
        sendBroadcast(intentManager.genBleDeviceAddedBroadcastIntent(name, address))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun removeServer(address: String) {
        Log.d(LOG_TAG, "Remove server device[$address]")
        serverDevices.remove(address)?.gattConn?.close()
        sendBroadcast(intentManager.genBleDeviceRemovedBroadcastIntent(address))
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun removeClient(address: String) {
        Log.d(LOG_TAG, "Remove client device[$address]")
        clientDevices.remove(address)
        sendBroadcast(intentManager.genBleDeviceRemovedBroadcastIntent(address))
    }

    private fun handleIncomingBleRequest(
        deviceAddress: String,
        deviceName: String,
        jsonStr: String,
        expectedRequestId: String?
    ): String? {
        Log.i(LOG_TAG, "Incoming BLE Request: $jsonStr")
        var requestId: String?
        try {
            val json = JSONObject(jsonStr)
            requestId = json.getString(EXTRA_REQUEST_ID)
            if (expectedRequestId != null && !expectedRequestId.equals(requestId, true)) {
                Log.e(LOG_TAG, "Expected requestId[$expectedRequestId], but: $requestId")
                return null
            }
            val name = json.getString(EXTRA_NAME)
            val serviceIdentifier = json.getString(EXTRA_SERVICE_IDENTIFIER)
            val pubKey = if (!json.isNull(EXTRA_PUB_KEY)) json.getString(EXTRA_PUB_KEY) else null

            notifyClient.showNotification(
                "New OTP BLE Request",
                "ID: #${requestId.takeLast(6)}",
                intentManager.genBleRequestActivityIntent(
                    deviceAddress,
                    deviceName,
                    requestId,
                    name,
                    serviceIdentifier,
                    pubKey
                )
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to parse incoming BLE Request: ", e)
            // not sent correctly, clear the requestId
            requestId = null
        }
        return requestId
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendBleResponseToClient(
        deviceAddress: String,
        requestId: String,
        otpCode: String?,
        errMsg: String?
    ) {
        Log.d(
            LOG_TAG,
            "ble response to client is received, device: $deviceAddress, requestId: $requestId, otpCode: $otpCode, errMsg: $errMsg"
        )
        val gattClient = clientDevices[deviceAddress] ?: run {
            Log.w(LOG_TAG, "No connected device[$deviceAddress] to send BLE response to")
            return
        }
        try {
            val json = JSONObject().apply {
                put(EXTRA_REQUEST_ID, requestId)
                put("body", JSONObject().apply {
                    if (otpCode != null) {
                        put("type", "Ok")
                        put("otp_code", otpCode)
                    } else if (errMsg != null) {
                        put("type", "Err")
                        put("message", errMsg)
                    }
                })
            }

            synchronized(gattClient) {
                if (gattClient.curRequestId != requestId) {
                    Log.e(
                        LOG_TAG,
                        "Expect requestId[${gattClient.curRequestId}], but $requestId, skip"
                    )
                    return
                }
                val data = json.toString().toByteArray(Charsets.UTF_8)
                val len = data.size
                val header = byteArrayOf(
                    (len ushr 24).toByte(),
                    (len ushr 16).toByte(),
                    (len ushr 8).toByte(),
                    len.toByte()
                )
                gattClient.resp = header + data
                gattClient.notifyAll()
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to construct/send BLE Response[$requestId]: ", e)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun sendBleResponseToServer(
        deviceAddress: String,
        requestId: String,
        otpCode: String?,
        errMsg: String?
    ) {
        Log.d(
            LOG_TAG,
            "ble response to server is received, device: $deviceAddress, requestId: $requestId, otpCode: $otpCode, errMsg: $errMsg"
        )
        val gattServer = serverDevices[deviceAddress] ?: run {
            Log.w(LOG_TAG, "No connected device[$deviceAddress] to send BLE response to")
            return
        }

        val characteristic = gattServer.gattConn.getService(bleClientConfig.serviceUuid)
            ?.getCharacteristic(bleClientConfig.responseCharUuid) ?: run {
            Log.w(
                LOG_TAG,
                "No response char found on device[$deviceAddress] to send BLE response to"
            )
            return
        }

        try {
            val json = JSONObject().apply {
                put(EXTRA_REQUEST_ID, requestId)
                put("body", JSONObject().apply {
                    if (otpCode != null) {
                        put("type", "Ok")
                        put("otp_code", otpCode)
                    } else if (errMsg != null) {
                        put("type", "Err")
                        put("message", errMsg)
                    }
                })
            }

            synchronized(gattServer) {
                if (gattServer.curRequestId != requestId) {
                    Log.e(
                        LOG_TAG,
                        "Expect requestId[${gattServer.curRequestId}], but $requestId, skip"
                    )
                    return
                }
                val data = json.toString().toByteArray(Charsets.UTF_8)
                val len = data.size
                val header = byteArrayOf(
                    (len ushr 24).toByte(),
                    (len ushr 16).toByte(),
                    (len ushr 8).toByte(),
                    len.toByte()
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattServer.gattConn.writeCharacteristic(
                        characteristic,
                        header + data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = header + data
                    @Suppress("DEPRECATION")
                    gattServer.gattConn.writeCharacteristic(
                        characteristic,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to construct/send BLE Response[$requestId]: ", e)
        }
    }
}

private fun uuidFromBytes(bytes: ByteArray): UUID {
    require(bytes.size == 16) { "Packed UUID missing bytes" }
    val buffer = ByteBuffer.wrap(bytes)
    val mostSignificantBits = buffer.long
    val leastSignificantBits = buffer.long
    return UUID(mostSignificantBits, leastSignificantBits)
}
