package fyi.fortime.otppushmobile.ui.screen

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
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.messaging.FirebaseMessaging
import fyi.fortime.otppushmobile.AppContext
import fyi.fortime.otppushmobile.BuildConfig
import fyi.fortime.otppushmobile.data.AuthResponse
import fyi.fortime.otppushmobile.data.GoogleAuthRequest
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.UserDto
import fyi.fortime.otppushmobile.util.ApiClient
import fyi.fortime.otppushmobile.util.MockJwt
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val LOG_TAG = "LoginScreen"

@Composable
fun LoginScreen(
    appContext: AppContext,
    onLoginSuccess: (UserDto) -> Unit
) {
    val apiClient = appContext.apiClient
    val persistentStore = appContext.persistentStore
    var serverUrlInput by remember { mutableStateOf(persistentStore.getServerUrl()) }
    var isLoadingConfig by remember { mutableStateOf(false) }

    var showMockLogin by remember { mutableStateOf(false) }
    var mockUserId by remember { mutableStateOf("") }
    var mockSecret by remember { mutableStateOf("") }

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
        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = {
                serverUrlInput = it
                appContext.persistentStore.saveServerUrl(it)
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
                        val config = fetchAuthConfig(apiClient, serverUrlInput)
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

                                val result = try {
                                    credentialManager.getCredential(
                                        context = context,
                                        request = request
                                    )
                                } catch (e: NoCredentialException) {
                                    Log.e(LOG_TAG, "No credential return from `getCredential`", e)
                                    null
                                }

                                val credential = result?.credential
                                if (credential?.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                    val googleIdTokenCredential =
                                        GoogleIdTokenCredential.createFrom(credential.data)
                                    val idToken = googleIdTokenCredential.idToken
                                    performLogin(
                                        scope,
                                        apiClient,
                                        persistentStore,
                                        context,
                                        idToken,
                                        onLoginSuccess
                                    )
                                } else {
                                    Log.e(LOG_TAG, "Unexpected credential type: ${credential?.type}")
                                }
                            } catch (e: Exception) {
                                Log.e(LOG_TAG, "Credential Manager error", e)
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

        if (BuildConfig.MOCK_LOGIN) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { showMockLogin = !showMockLogin },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showMockLogin) "Hide Mock Login" else "Show Mock Login")
            }

            if (showMockLogin) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = mockUserId,
                    onValueChange = { mockUserId = it },
                    label = { Text("Mock User ID (UUID)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = mockSecret,
                    onValueChange = { mockSecret = it },
                    label = { Text("Mock JWT Secret") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        try {
                            val token = MockJwt.createToken(mockUserId, mockSecret)
                            performMockLogin(
                                scope,
                                apiClient,
                                persistentStore,
                                token,
                                onLoginSuccess
                            )
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "Mock login error", e)
                            Toast.makeText(
                                context,
                                "Mock Login failed: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Mock Login")
                }
            }
        }
    }
}

private fun performMockLogin(
    scope: CoroutineScope,
    apiClient: ApiClient,
    persistentStore: PersistentStore,
    token: String,
    onSuccess: (UserDto) -> Unit
) {
    scope.launch {
        val baseUrl = persistentStore.getServerUrl()
        persistentStore.saveToken(token)

        val user = apiClient.safeApiCall(
            builder = {
                method = HttpMethod.Get
                url("$baseUrl/api/http/users/me")
                header("Authorization", "Bearer $token")
            },
            serializer = { it.body<UserDto>() }
        )

        if (user != null) {
            persistentStore.saveUser(user)
            onSuccess(user)
        }
    }
}

private suspend fun fetchAuthConfig(
    apiClient: ApiClient,
    serverUrl: String,
): fyi.fortime.otppushmobile.data.AuthConfig? {
    return apiClient.safeApiCall(
        builder = {
            method = HttpMethod.Get
            url("$serverUrl/api/http/auth/config")
        },
        serializer = { it.body<fyi.fortime.otppushmobile.data.AuthConfig>() }
    )
}

private fun performLogin(
    scope: CoroutineScope,
    apiClient: ApiClient,
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
            Log.e(LOG_TAG, "FCM token error", e)
            null
        }

        val createDevice = !persistentStore.isDeviceCreated()

        val response = apiClient.safeApiCall(
            builder = {
                method = HttpMethod.Post
                url("$baseUrl/api/http/auth/google")
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
                performLogin(scope, apiClient, persistentStore, context, idToken, onSuccess)
            }
        }
    }
}
