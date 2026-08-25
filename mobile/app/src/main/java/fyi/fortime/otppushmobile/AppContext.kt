package fyi.fortime.otppushmobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fyi.fortime.otppushmobile.data.LogoutRequest
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.UserDto
import fyi.fortime.otppushmobile.util.ApiClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

class AppContext(val context: Context) {
    val apiClient = ApiClient(context) { clearLogin() }
    val persistentStore = PersistentStore(context)
    val history = History()
    val bleDevices = mutableStateListOf<Pair<String, String>>()
    var loggedIn by mutableStateOf(persistentStore.getToken() != null)
    var bleEnabled by mutableStateOf(persistentStore.isBleEnabled())
    var bleScanning by mutableStateOf(false)

    private var currentUser: UserDto? = null

    fun currentUser(): UserDto? {
        if (currentUser == null) {
            currentUser = persistentStore.getUser()
        }
        return currentUser
    }

    suspend fun logout() {
        val token = persistentStore.getToken()
        val baseUrl = persistentStore.getServerUrl()
        val deviceId = persistentStore.getDeviceUuid()

        apiClient.safeApiCall(
            builder = {
                method = HttpMethod.Delete
                url("$baseUrl/api/http/mobile/logout")
                header("Authorization", "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(LogoutRequest(device_id = deviceId))
            },
            serializer = { }
        )

        clearLogin()
    }

    private fun clearLogin() {
        persistentStore.clearCredentials()
        loggedIn = false
    }

    fun saveBleEnabled(enabled: Boolean) {
        bleEnabled = enabled
        persistentStore.saveBleEnabled(enabled)
    }
}

class History {
    private val records = mutableStateListOf<HistoryRecord>()

    fun top(): HistoryRecord? {
        return records.lastOrNull()
    }

    fun pop(): HistoryRecord? {
        return if (records.isEmpty()) {
            null
        } else {
            records.removeAt(records.size - 1)
        }
    }

    fun isNotEmpty(): Boolean {
        return records.isNotEmpty()
    }

    fun push(record: HistoryRecord) {
        records.add(record)
    }

    fun clear() {
        records.clear()
    }
}

class HistoryRecord(
    val title: String?,
    val needLogin: Boolean,
    private val content: @Composable () -> Unit
) {
    @Composable
    fun Content() {
        content()
    }
}
