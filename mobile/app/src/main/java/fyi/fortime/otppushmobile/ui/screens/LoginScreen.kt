package fyi.fortime.otppushmobile.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.messaging.FirebaseMessaging
import fyi.fortime.otppushmobile.data.AuthResponse
import fyi.fortime.otppushmobile.data.GoogleAuthRequest
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.UserDto
import fyi.fortime.otppushmobile.util.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Composable
fun LoginScreen(
    client: HttpClient,
    persistentStore: PersistentStore,
    onLoginSuccess: (UserDto) -> Unit
) {
    var serverUrlInput by remember { mutableStateOf(persistentStore.getServerUrl()) }
    var isLoadingConfig by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val credentialManager = remember { CredentialManager.create(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("OTP Push Mobile", style = MaterialTheme.typography.headlineLarge)

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = {
                serverUrlInput = it
                persistentStore.saveServerUrl(it)
            },
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoadingConfig) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = {
                    scope.launch {
                        isLoadingConfig = true
                        val config = fetchAuthConfig(client, serverUrlInput, context)
                        isLoadingConfig = false

                        if (config != null) {
                            try {
                                val googleIdOption = GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(false)
                                    .setServerClientId(config.google_client_id)
                                    .setAutoSelectEnabled(true)
                                    .build()

                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(googleIdOption)
                                    .build()

                                val result = credentialManager.getCredential(
                                    context = context,
                                    request = request
                                )

                                val credential = result.credential
                                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                    val googleIdTokenCredential =
                                        GoogleIdTokenCredential.createFrom(credential.data)
                                    val idToken = googleIdTokenCredential.idToken
                                    performLogin(
                                        scope,
                                        client,
                                        persistentStore,
                                        context,
                                        idToken,
                                        onLoginSuccess
                                    )
                                } else {
                                    Log.e("Auth", "Unexpected credential type: ${credential.type}")
                                }
                            } catch (e: GetCredentialException) {
                                Log.e("Auth", "Credential Manager error", e)
                                Toast.makeText(
                                    context,
                                    "Login failed: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Login with Google")
            }
        }
    }
}

private suspend fun fetchAuthConfig(
    client: HttpClient,
    serverUrl: String,
    context: Context
): fyi.fortime.otppushmobile.data.AuthConfig? {
    return client.safeApiCall(
        context = context,
        builder = {
            method = HttpMethod.Get
            url("$serverUrl/api/auth/config")
        },
        onUnauthorized = {},
        serializer = { it.body<fyi.fortime.otppushmobile.data.AuthConfig>() }
    )
}

private fun performLogin(
    scope: CoroutineScope,
    client: HttpClient,
    persistentStore: PersistentStore,
    context: Context,
    idToken: String,
    onSuccess: (UserDto) -> Unit
) {
    scope.launch {
        val baseUrl = persistentStore.getServerUrl()
        val deviceId = persistentStore.getDeviceUuid()
        val fcmToken = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: Exception) {
            Log.e("Auth", "FCM token error", e)
            null
        }

        val createDevice = !persistentStore.isDeviceCreated()

        val response = client.safeApiCall(
            context = context,
            builder = {
                method = HttpMethod.Post
                url("$baseUrl/api/auth/google")
                contentType(Application.Json)
                setBody(
                    GoogleAuthRequest(
                        id_token = idToken,
                        device_id = deviceId,
                        fcm_token = fcmToken,
                        create_device = createDevice
                    )
                )
            },
            onUnauthorized = { /* Login call shouldn't trigger unauthorized logout typically */ },
            serializer = { it }
        )

        if (response != null) {
            if (response.status == HttpStatusCode.OK) {
                val authResponse = response.body<AuthResponse>()
                persistentStore.saveToken(authResponse.access_token)
                persistentStore.saveUser(authResponse.user)
                persistentStore.setDeviceCreated(true)
                onSuccess(authResponse.user)
            } else if (response.status == HttpStatusCode.Conflict) {
                // Device UUID conflict, generate new one and retry
                Toast.makeText(context, "Device conflict, retrying with new ID", Toast.LENGTH_SHORT)
                    .show()
                persistentStore.generateNewDeviceUuid()
                performLogin(scope, client, persistentStore, context, idToken, onSuccess)
            }
        }
    }
}
