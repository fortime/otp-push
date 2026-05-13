package fyi.fortime.otppushmobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.ui.screens.LoginScreen
import fyi.fortime.otppushmobile.ui.screens.MainContainerScreen
import fyi.fortime.otppushmobile.ui.screens.OtpFillScreen
import fyi.fortime.otppushmobile.ui.screens.OtpRecordTokensScreen
import fyi.fortime.otppushmobile.ui.screens.OtpSubmissionScreen
import fyi.fortime.otppushmobile.ui.theme.OtpPushMobileTheme
import fyi.fortime.otppushmobile.util.safeApiCall
import fyi.fortime.otppushmobile.util.sharedHttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val client = sharedHttpClient

    private lateinit var persistentStore: PersistentStore
    private var pendingRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        persistentStore = PersistentStore(this)
        pendingRequestId = intent.getStringExtra("request_id")
        enableEdgeToEdge()
        setContent {
            OtpPushMobileTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainApp(
                        modifier = Modifier.padding(innerPadding),
                        initialRequestId = pendingRequestId
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requestId = intent.getStringExtra("request_id")
        if (requestId != null) {
            // How to propagate this to MainApp?
            // Re-creating the setContent is one way, or use a state
            // Let's just handle it via an event or update state if possible
        }
    }

    @Composable
    fun MainApp(modifier: Modifier = Modifier, initialRequestId: String? = null) {
        val context = LocalContext.current
        var isLoggedIn by remember { mutableStateOf(persistentStore.getToken() != null) }
        var currentUser by remember { mutableStateOf(persistentStore.getUser()) }
        var selectedOtpRequest by remember {
            mutableStateOf<fyi.fortime.otppushmobile.data.OtpRequestDto?>(
                null
            )
        }

        LaunchedEffect(initialRequestId) {
            if (initialRequestId != null) {
                val token = persistentStore.getToken()
                val baseUrl = persistentStore.getServerUrl()
                if (token != null) {
                    client.safeApiCall(
                        context = context,
                        builder = {
                            method = HttpMethod.Get
                            url("$baseUrl/api/mobile/requests/$initialRequestId")
                            header("Authorization", "Bearer $token")
                        },
                        onUnauthorized = { /* handle logout */ },
                        serializer = { response -> response.body<fyi.fortime.otppushmobile.data.OtpRequestDto>() }
                    )?.let { request ->
                        selectedOtpRequest = request
                    }
                }
            }
        }
        var selectedOtpRecord by remember {
            mutableStateOf<fyi.fortime.otppushmobile.data.OtpRecordDto?>(
                null
            )
        }
        var fillOtpRecord by remember {
            mutableStateOf<fyi.fortime.otppushmobile.data.OtpRecordDto?>(
                null
            )
        }
        var selectedTab by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()

        fun handleLogout() {
            val token = persistentStore.getToken()
            val baseUrl = persistentStore.getServerUrl()
            val deviceId = persistentStore.getDeviceUuid()
            val ctx = context

            if (token != null) {
                scope.launch {
                    client.safeApiCall(
                        context = ctx,
                        builder = {
                            method = HttpMethod.Delete
                            url("$baseUrl/api/mobile/logout")
                            header("Authorization", "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(fyi.fortime.otppushmobile.data.LogoutRequest(device_id = deviceId))
                        },
                        onUnauthorized = { /* already logging out */ },
                        serializer = { }
                    )
                }
            }

            persistentStore.clearToken()
            isLoggedIn = false
            selectedOtpRequest = null
            currentUser = null
            selectedOtpRecord = null
            fillOtpRecord = null
            selectedTab = 0
        }

        // Fetch user info on startup if missing
        LaunchedEffect(isLoggedIn) {
            if (isLoggedIn && currentUser == null) {
                val token = persistentStore.getToken()
                val baseUrl = persistentStore.getServerUrl()
                if (token != null) {
                    client.safeApiCall(
                        context = context,
                        builder = {
                            method = HttpMethod.Get
                            url("$baseUrl/api/users/me")
                            header("Authorization", "Bearer $token")
                        },
                        onUnauthorized = { handleLogout() },
                        serializer = { it.body<fyi.fortime.otppushmobile.data.UserDto>() }
                    )?.let { user ->
                        currentUser = user
                        persistentStore.saveUser(user)
                    }
                }
            }
        }

        if (!isLoggedIn) {
            LoginScreen(
                client = client,
                persistentStore = persistentStore,
                onLoginSuccess = { user ->
                    currentUser = user
                    isLoggedIn = true
                }
            )
        } else if (selectedOtpRequest != null) {
            BackHandler {
                selectedOtpRequest = null
            }
            OtpSubmissionScreen(
                client = client,
                persistentStore = persistentStore,
                currentRequestId = selectedOtpRequest!!.id,
                otpRecordName = selectedOtpRequest!!.otp_record_name,
                serviceIdentifier = selectedOtpRequest!!.service_identifier,
                onUnauthorized = { handleLogout() },
                onBack = { selectedOtpRequest = null },
                onSuccess = { selectedOtpRequest = null },
                modifier = modifier
            )
        } else if (fillOtpRecord != null) {
            BackHandler {
                fillOtpRecord = null
            }
            OtpFillScreen(
                otpRecordName = fillOtpRecord!!.name,
                serviceIdentifier = fillOtpRecord!!.service_identifier,
                onBack = { fillOtpRecord = null }
            )
        } else if (selectedOtpRecord != null) {
            BackHandler {
                selectedOtpRecord = null
            }
            OtpRecordTokensScreen(
                client = client,
                persistentStore = persistentStore,
                otpRecordId = selectedOtpRecord!!.id.toString(),
                otpRecordName = selectedOtpRecord!!.name,
                onBack = { selectedOtpRecord = null },
                onUnauthorized = { handleLogout() }
            )
        } else {
            MainContainerScreen(
                client = client,
                persistentStore = persistentStore,
                currentUser = currentUser,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onSelectRequest = { request -> selectedOtpRequest = request },
                onSelectOtpRecord = { record -> selectedOtpRecord = record },
                onFillOtpRecord = { record -> fillOtpRecord = record },
                onUnauthorized = { handleLogout() },
                onLogout = { handleLogout() }
            )
        }
    }
}
