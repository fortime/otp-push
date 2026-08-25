package fyi.fortime.otppushmobile

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

private const val LOG_TAG = "IntentManager"

class IntentManager(val context: Context) {
    companion object {
        const val ACTION_HTTP_REQUEST = "${BuildConfig.APPLICATION_ID}.HTTP_REQUEST"
        const val ACTION_BLE_TOGGLE = "${BuildConfig.APPLICATION_ID}.BLE_TOGGLE"
        const val ACTION_BLE_REQUEST = "${BuildConfig.APPLICATION_ID}.BLE_REQUEST"
        const val ACTION_BLE_RESPONSE = "${BuildConfig.APPLICATION_ID}.BLE_RESPONSE"
        const val ACTION_BLE_SCANNING_TOGGLE = "${BuildConfig.APPLICATION_ID}.BLE_SCANNING_TOGGLE"
        const val ACTION_BLE_SCANNING_STATE = "${BuildConfig.APPLICATION_ID}.BLE_SCANNING_STATE"
        const val ACTION_BLE_DEVICE_CLEARED = "${BuildConfig.APPLICATION_ID}.BLE_DEVICE_CLEARED"
        const val ACTION_BLE_DEVICE_ADDED = "${BuildConfig.APPLICATION_ID}.BLE_DEVICE_ADDED"
        const val ACTION_BLE_DEVICE_REMOVED = "${BuildConfig.APPLICATION_ID}.BLE_DEVICE_REMOVED"
        const val ACTION_BLE_REMOVE_DEVICE = "${BuildConfig.APPLICATION_ID}.BLE_REMOVE_DEVICE"
        const val ACTION_BLE_FETCH_DEVICES = "${BuildConfig.APPLICATION_ID}.BLE_FETCH_DEVICES"
        const val EXTRA_DEVICE_ADDRESS = "dev_addr"
        const val EXTRA_DEVICE_NAME = "dev_name"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_SERVICE_IDENTIFIER = "service_identifier"
        const val EXTRA_PUB_KEY = "pub_key"
        const val EXTRA_OTP_CODE = "otp_code"
        const val EXTRA_ERR_MSG = "err_msg"
        const val EXTRA_TOGGLE = "toggle"
        const val EXTRA_STATE = "state"
    }

    fun genHttpRequestActivityIntent(requestId: String): Intent {
        // for notification
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            action = ACTION_HTTP_REQUEST
            putExtra(EXTRA_REQUEST_ID, requestId)
        }
    }

    fun genBleToggleBroadcastIntent(): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_TOGGLE).apply {
            `package` = context.packageName
        }
    }

    fun genBleRequestActivityIntent(
        deviceAddress: String,
        deviceName: String,
        requestId: String,
        name: String,
        serviceIdentifier: String,
        pubKey: String?
    ): Intent {
        // for notification
        return Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            action = ACTION_BLE_REQUEST
            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(EXTRA_DEVICE_NAME, deviceName)
            putExtra(EXTRA_REQUEST_ID, requestId)
            putExtra(EXTRA_NAME, name)
            putExtra(EXTRA_SERVICE_IDENTIFIER, serviceIdentifier)
            if (!pubKey.isNullOrEmpty()) {
                putExtra(EXTRA_PUB_KEY, pubKey)
            }
        }
    }

    fun genBleScanningToggleBroadcastIntent(toggle: Boolean): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_SCANNING_TOGGLE).apply {
            `package` = context.packageName
            putExtra(EXTRA_TOGGLE, toggle)
        }
    }

    fun genBleScanningStateBroadcastIntent(state: Boolean): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_SCANNING_STATE).apply {
            `package` = context.packageName
            putExtra(EXTRA_STATE, state)
        }
    }

    fun genBleDeviceClearedBroadcastIntent(): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_DEVICE_CLEARED).apply {
            `package` = context.packageName
        }
    }

    fun genBleDeviceAddedBroadcastIntent(name: String, address: String): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_DEVICE_ADDED).apply {
            `package` = context.packageName
            putExtra(EXTRA_DEVICE_NAME, name)
            putExtra(EXTRA_DEVICE_ADDRESS, address)
        }
    }

    fun genBleDeviceRemovedBroadcastIntent(address: String): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_DEVICE_REMOVED).apply {
            `package` = context.packageName
            putExtra(EXTRA_DEVICE_ADDRESS, address)
        }
    }

    fun genBleRemoveDeviceBroadcastIntent(address: String): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_REMOVE_DEVICE).apply {
            `package` = context.packageName
            putExtra(EXTRA_DEVICE_ADDRESS, address)
        }
    }

    fun genBleFetchDevicesBroadcastIntent(): Intent {
        // for `sendBroadcast`
        return Intent(ACTION_BLE_FETCH_DEVICES).apply {
            `package` = context.packageName
        }
    }

    fun genBleResponseIntent(
        deviceAddress: String,
        requestId: String,
        otpCode: String?,
        errMsg: String?
    ): Intent {
        return Intent(ACTION_BLE_RESPONSE).apply {
            `package` = context.packageName

            putExtra(EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(EXTRA_REQUEST_ID, requestId)
            if (otpCode != null) {
                putExtra(EXTRA_OTP_CODE, otpCode)
            }
            if (errMsg != null) {
                putExtra(EXTRA_ERR_MSG, errMsg)
            }
        }
    }

    fun genHttpRequestListener(receiver: (String) -> Unit): (Intent) -> Unit {
        return cb@{ intent ->
            if (intent.action == ACTION_HTTP_REQUEST) {
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return@cb
                receiver(requestId)
            }
        }
    }

    fun genBleToggleReceiver(receiver: () -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_TOGGLE) {
                    Log.d(LOG_TAG, "receive intent: $intent")
                    receiver()
                }
            }
        }, IntentFilter(ACTION_BLE_TOGGLE))
    }

    fun genBleRequestListener(receiver: (String, String, String, String, String, String?) -> Unit): (Intent) -> Unit {
        return cb@{ intent ->
            if (intent.action == ACTION_BLE_REQUEST) {
                val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return@cb
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: return@cb
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return@cb
                val name = intent.getStringExtra(EXTRA_NAME) ?: return@cb
                val serviceIdentifier = intent.getStringExtra(EXTRA_SERVICE_IDENTIFIER) ?: return@cb
                val pubKey = intent.getStringExtra(EXTRA_PUB_KEY)
                receiver(deviceAddress, deviceName, requestId, name, serviceIdentifier, pubKey)
            }
        }
    }

    fun genBleResponseReceiver(receiver: (String, String, String?, String?) -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_RESPONSE) {
                    val deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return
                    val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
                    val otpCode = intent.getStringExtra(EXTRA_OTP_CODE)
                    val errMsg = intent.getStringExtra(EXTRA_ERR_MSG)
                    receiver(deviceAddress, requestId, otpCode, errMsg)
                }
            }
        }, IntentFilter(ACTION_BLE_RESPONSE))
    }

    fun genBluetoothStateReceiver(receiver: (Intent) -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    receiver(intent)
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    fun genBleScanningToggleReceiver(receiver: (Boolean) -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_SCANNING_TOGGLE) {
                    val toggle = intent.getBooleanExtra(EXTRA_TOGGLE, false)
                    receiver(toggle)
                }
            }
        }, IntentFilter(ACTION_BLE_SCANNING_TOGGLE))
    }

    fun genBleScanningStateReceiver(receiver: (Boolean) -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_SCANNING_STATE) {
                    val state = intent.getBooleanExtra(EXTRA_STATE, false)
                    receiver(state)
                }
            }
        }, IntentFilter(ACTION_BLE_SCANNING_STATE))
    }

    fun genBleDeviceClearedReceiver(receiver: () -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_DEVICE_CLEARED) {
                    receiver()
                }
            }
        }, IntentFilter(ACTION_BLE_DEVICE_CLEARED))
    }

    fun genBleDeviceAddedReceiver(receiver: (String, String) -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_DEVICE_ADDED) {
                    val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: return
                    val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return
                    receiver(name, address)
                }
            }
        }, IntentFilter(ACTION_BLE_DEVICE_ADDED))
    }

    fun genBleDeviceRemovedReceiver(receiver: (String) -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_DEVICE_REMOVED) {
                    val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return
                    receiver(address)
                }
            }
        }, IntentFilter(ACTION_BLE_DEVICE_REMOVED))
    }

    fun genBleRemoveDeviceReceiver(receiver: (String) -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_REMOVE_DEVICE) {
                    val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return
                    receiver(address)
                }
            }
        }, IntentFilter(ACTION_BLE_REMOVE_DEVICE))
    }

    fun genBleFetchDevicesReceiver(receiver: () -> Unit): Pair<BroadcastReceiver, IntentFilter> {
        return Pair(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_BLE_FETCH_DEVICES) {
                    receiver()
                }
            }
        }, IntentFilter(ACTION_BLE_FETCH_DEVICES))
    }
}
