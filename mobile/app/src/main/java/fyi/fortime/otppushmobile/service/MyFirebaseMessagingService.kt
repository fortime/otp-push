package fyi.fortime.otppushmobile.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import fyi.fortime.otppushmobile.IntentManager
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.UpdateFcmTokenRequest
import fyi.fortime.otppushmobile.util.ApiClient
import fyi.fortime.otppushmobile.util.NotifyClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val LOG_TAG = "MyFirebaseMessagingService"

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private lateinit var intentManager: IntentManager
    private lateinit var notifyClient: NotifyClient

    override fun onCreate() {
        super.onCreate()

        Log.i(LOG_TAG, "Creating service")

        intentManager = IntentManager(applicationContext)
        notifyClient = NotifyClient(
            applicationContext,
            "otp_requests",
            "OTP Requests",
            "Channel for OTP requests"
        )
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Handle data payload
        remoteMessage.data.let { data ->
            val requestId = data["request_id"] ?: return
            val title = data["title"] ?: "New OTP Request"
            val message = data["body"] ?: "ID: #${requestId.takeLast(6)}"
            notifyClient.showNotification(
                title,
                message,
                intentManager.genHttpRequestActivityIntent(requestId)
            )
        }
    }

    override fun onNewToken(token: String) {
        // The token is sent to the server on the next login
        // But also update it now if we are already logged in
        val client = ApiClient(this) {}
        val persistentStore = PersistentStore(this)
        val jwtToken = persistentStore.getToken()
        val deviceUuid = persistentStore.getDeviceUuid()
        val baseUrl = persistentStore.getServerUrl()

        if (jwtToken != null) {
            scope.launch {
                client.safeApiCall(
                    builder = {
                        method = HttpMethod.Put
                        url("$baseUrl/api/http/mobile/fcm-token")
                        header("Authorization", "Bearer $jwtToken")
                        contentType(ContentType.Application.Json)
                        setBody(UpdateFcmTokenRequest(device_id = deviceUuid, fcm_token = token))
                    },
                    serializer = { }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
