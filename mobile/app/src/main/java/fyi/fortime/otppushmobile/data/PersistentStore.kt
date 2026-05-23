package fyi.fortime.otppushmobile.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import fyi.fortime.otppushmobile.BuildConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
            uuid = java.util.UUID.randomUUID().toString()
            prefs.edit { putString(DEVICE_UUID_KEY, uuid) }
        }
        return uuid
    }

    fun generateNewDeviceUuid(): String {
        val uuid = java.util.UUID.randomUUID().toString()
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

    fun clearToken() {
        prefs.edit { remove(TOKEN_KEY).remove(USER_KEY).remove(OTP_RECORDS_CACHE_KEY) }
    }

    fun saveServerUrl(url: String) {
        prefs.edit { putString(SERVER_URL_KEY, url) }
    }

    fun getServerUrl(): String {
        return prefs.getString(SERVER_URL_KEY, DEFAULT_URL) ?: DEFAULT_URL
    }
}
