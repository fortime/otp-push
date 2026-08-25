package fyi.fortime.otppushmobile.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import fyi.fortime.otppushmobile.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private const val LOG_TAG = "PersistentStore"

class PersistentStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TOKEN_KEY = "jwt_token"
        private const val USER_KEY = "user_profile"
        private const val OTP_RECORDS_CACHE_KEY = "otp_records_cache"
        private const val SERVER_URL_KEY = "server_url"
        private const val DEVICE_UUID_KEY = "device_uuid"
        private const val IS_DEVICE_CREATED_KEY = "is_device_created"
        private const val MAIN_SCREEN_TAB_INDEX_KEY = "main_screen_tab_index"
        private const val BLE_ENABLED_KEY = "ble_enabled"
        private const val BLE_SERVER_MODE_KEY = "ble_server_mode"
        private const val BLE_CLIENT_CONFIG_KEY = "ble_client_config"
        private const val BLE_SERVER_CONFIG_KEY = "ble_server_config"
        private const val DEFAULT_URL = BuildConfig.DEFAULT_SERVER_URL
    }

    fun saveToken(token: String) {
        prefs.edit { putString(TOKEN_KEY, token) }
    }

    fun getToken(): String? {
        return prefs.getString(TOKEN_KEY, null)
    }

    fun saveUser(user: UserDto) {
        prefs.edit { putString(USER_KEY, Json.encodeToString(user)) }
    }

    fun getUser(): UserDto? {
        val json = prefs.getString(USER_KEY, null) ?: return null
        return try {
            Json.decodeFromString<UserDto>(json)
        } catch (_: Exception) {
            null
        }
    }

    fun getDeviceUuid(): String {
        var uuid = prefs.getString(DEVICE_UUID_KEY, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit { putString(DEVICE_UUID_KEY, uuid) }
        }
        return uuid
    }

    fun generateNewDeviceUuid(): String {
        val uuid = UUID.randomUUID().toString()
        prefs.edit {
            putString(DEVICE_UUID_KEY, uuid)
            putBoolean(IS_DEVICE_CREATED_KEY, false)
        }
        return uuid
    }

    fun isDeviceCreated(): Boolean {
        return prefs.getBoolean(IS_DEVICE_CREATED_KEY, false)
    }

    fun setDeviceCreated(created: Boolean) {
        prefs.edit { putBoolean(IS_DEVICE_CREATED_KEY, created) }
    }

    fun saveCachedOtpRecords(records: CachedOtpRecords) {
        prefs.edit { putString(OTP_RECORDS_CACHE_KEY, Json.encodeToString(records)) }
    }

    fun getCachedOtpRecords(): CachedOtpRecords {
        val empty = CachedOtpRecords(emptyList(), 1, true)
        val json = prefs.getString(OTP_RECORDS_CACHE_KEY, null) ?: return empty
        return try {
            Json.decodeFromString<CachedOtpRecords>(json)
        } catch (_: Exception) {
            empty
        }
    }

    fun clearCredentials() {
        prefs.edit { remove(TOKEN_KEY).remove(USER_KEY).remove(OTP_RECORDS_CACHE_KEY) }
    }

    fun saveServerUrl(url: String) {
        prefs.edit { putString(SERVER_URL_KEY, url) }
    }

    fun getServerUrl(): String {
        return prefs.getString(SERVER_URL_KEY, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun saveMainScreenTabIndex(index: Int) {
        prefs.edit { putInt(MAIN_SCREEN_TAB_INDEX_KEY, index) }
    }

    fun getMainScreenTabIndex(): Int {
        return prefs.getInt(MAIN_SCREEN_TAB_INDEX_KEY, 0)
    }

    fun isBleEnabled(): Boolean {
        return prefs.getBoolean(BLE_ENABLED_KEY, false)
    }

    fun saveBleEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(BLE_ENABLED_KEY, enabled) }
    }

    fun isBleServerMode(): Boolean {
        return prefs.getBoolean(BLE_SERVER_MODE_KEY, true)
    }

    fun saveBleServerMode(bleServerMode: Boolean) {
        prefs.edit { putBoolean(BLE_SERVER_MODE_KEY, bleServerMode) }
    }

    fun getBleClientConfig(): BleClientConfig {
        val default = BleClientConfig(
            UUID.fromString("a0000000-1034-49ce-abbe-8fb26e433894"),
            UUID.fromString("a0100000-1034-49ce-abbe-8fb26e433894"),
            UUID.fromString("a0100001-1034-49ce-abbe-8fb26e433894"),
            UUID.fromString("a0010000-1034-49ce-abbe-8fb26e433894"),
            10.seconds,
        )

        val json = prefs.getString(BLE_CLIENT_CONFIG_KEY, null) ?: return default
        return try {
            val raw = Json.decodeFromString<RawBleClientConfig>(json)
            return BleClientConfig(
                UUID.fromString(raw.serviceUuid),
                UUID.fromString(raw.requestCharUuid),
                UUID.fromString(raw.responseCharUuid),
                UUID.fromString(raw.readyDescriptorUuid),
                raw.scanningTimeout,
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Invalid ble client config[$json]: $e")
            default
        }
    }

    fun saveBleClientConfig(config: BleClientConfig) {
        val raw = RawBleClientConfig(
            config.serviceUuid.toString(),
            config.requestCharUuid.toString(),
            config.responseCharUuid.toString(),
            config.readyDescriptorUuid.toString(),
            config.scanningTimeout,
        )
        prefs.edit { putString(BLE_CLIENT_CONFIG_KEY, Json.encodeToString(raw)) }
    }

    fun getBleServerConfig(): BleServerConfig {
        val default = BleServerConfig(
            UUID.fromString("a1000000-1034-49ce-abbe-8fb26e433894"),
            UUID.fromString("a1100000-1034-49ce-abbe-8fb26e433894"),
            UUID.fromString("a1100001-1034-49ce-abbe-8fb26e433894"),
        )

        val json = prefs.getString(BLE_SERVER_CONFIG_KEY, null) ?: return default
        return try {
            val raw = Json.decodeFromString<RawBleServerConfig>(json)
            return BleServerConfig(
                UUID.fromString(raw.serviceUuid),
                UUID.fromString(raw.requestCharUuid),
                UUID.fromString(raw.responseCharUuid),
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Invalid ble server config[$json]: $e")
            default
        }
    }

    fun saveBleServerConfig(config: BleServerConfig) {
        val raw = RawBleServerConfig(
            config.serviceUuid.toString(),
            config.requestCharUuid.toString(),
            config.responseCharUuid.toString(),
        )
        prefs.edit { putString(BLE_SERVER_CONFIG_KEY, Json.encodeToString(raw)) }
    }
}
