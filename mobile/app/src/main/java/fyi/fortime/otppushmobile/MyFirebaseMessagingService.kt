package fyi.fortime.otppushmobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.UpdateFcmTokenRequest
import fyi.fortime.otppushmobile.util.safeApiCall
import fyi.fortime.otppushmobile.util.sharedHttpClient
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

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Handle data payload
        remoteMessage.data.let { data ->
            val title = data["title"] ?: "New OTP Request"
            val message = data["body"] ?: "Tap to view and submit OTP"
            val requestId = data["request_id"]
            showNotification(title, message, requestId)
        }
    }

    override fun onNewToken(token: String) {
        // The token is sent to the server on the next login
        // But also update it now if we are already logged in
        val persistentStore = PersistentStore(this)
        val jwtToken = persistentStore.getToken()
        val deviceUuid = persistentStore.getDeviceUuid()
        val baseUrl = persistentStore.getServerUrl()

        if (jwtToken != null) {
            scope.launch {
                sharedHttpClient.safeApiCall(
                    context = this@MyFirebaseMessagingService,
                    builder = {
                        method = HttpMethod.Put
                        url("$baseUrl/api/mobile/fcm-token")
                        header("Authorization", "Bearer $jwtToken")
                        contentType(ContentType.Application.Json)
                        setBody(UpdateFcmTokenRequest(device_id = deviceUuid, fcm_token = token))
                    },
                    onUnauthorized = {
                        persistentStore.clearCredentials()
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

    private fun showNotification(title: String, message: String, requestId: String?) {
        val channelId = "otp_requests"
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            "OTP Requests",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for OTP requests"
            enableLights(true)
            enableVibration(true)
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (requestId != null) {
                putExtra("request_id", requestId)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with app icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
