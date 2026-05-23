package fyi.fortime.otppushmobile.util

import android.content.Context
import android.util.Log
import android.widget.Toast
import fyi.fortime.otppushmobile.data.AuthResponse
import fyi.fortime.otppushmobile.data.PersistentStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val sharedHttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

private const val LOG_TAG = "ApiClient"

private val refreshMutex = Mutex()

// Data class to parse the common error response from the backend
@Serializable
data class ErrorResponse(val error: String)

// Helper function to extract error message from HttpResponse
suspend fun HttpResponse.getErrorMessage(): String {
    return try {
        val errorBody: ErrorResponse = this.body()
        errorBody.error
    } catch (e: Exception) {
        Log.w(LOG_TAG, "failed to deserialize error: $e")
        "Error: ${this.status}"
    }
}

// A generic wrapper for API calls that handles common error patterns
suspend fun <T> HttpClient.safeApiCall(
    context: Context,
    builder: HttpRequestBuilder.() -> Unit,
    onUnauthorized: () -> Unit,
    successCode: HttpStatusCode = HttpStatusCode.OK,
    serializer: suspend (HttpResponse) -> T
): T? {
    try {
        val persistentStore = PersistentStore(context)
        // Capture the token currently in use
        val tokenUsedInThisRequest = persistentStore.getToken()

        val response: HttpResponse = this.request(builder)

        when (response.status) {
            successCode -> {
                // Check for renewal signal
                if (response.headers["X-Renew-Token"] == "true") {
                    // Pass the token used in THIS request to the refresh function
                    // to handle race conditions correctly inside the mutex.
                    refreshToken(this@safeApiCall, persistentStore, tokenUsedInThisRequest)
                }

                try {
                    return serializer(response)
                } catch (e: Exception) {
                    Toast.makeText(context, "Unexpected response: ${e.message}", Toast.LENGTH_LONG)
                        .show()
                    return null
                }
            }

            HttpStatusCode.Unauthorized -> {
                onUnauthorized()
                Toast.makeText(context, "Session expired", Toast.LENGTH_SHORT).show()
                return null
            }

            else -> {
                val errorMessage = response.getErrorMessage()
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                return null
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Network error: ${e.message}", Toast.LENGTH_SHORT).show()
        return null
    }
}

private suspend fun refreshToken(
    client: HttpClient,
    persistentStore: PersistentStore,
    usedToken: String?
) {
    refreshMutex.withLock {
        val currentToken = persistentStore.getToken()

        // If the token in the store is already different from the one used in the 
        // request that triggered this call, it means another thread already refreshed it.
        if (currentToken != usedToken || currentToken == null) return

        val baseUrl = persistentStore.getServerUrl()
        try {
            val response: HttpResponse = client.request {
                method = HttpMethod.Post
                url("$baseUrl/api/auth/refresh")
                header("Authorization", "Bearer $currentToken")
                contentType(ContentType.Application.Json)
            }

            if (response.status == HttpStatusCode.OK) {
                val authResponse = response.body<AuthResponse>()
                persistentStore.saveToken(authResponse.access_token)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "failed to refresh token, error: $e")
            // Silent failure, next request will retry or fail
        }
    }
}
