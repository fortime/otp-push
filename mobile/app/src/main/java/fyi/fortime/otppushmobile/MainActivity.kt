package fyi.fortime.otppushmobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import fyi.fortime.otppushmobile.data.OtpRecordDto
import fyi.fortime.otppushmobile.data.OtpRequestDto
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
                MainApp(
                    initialRequestId = pendingRequestId
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    @Composable
    fun MainApp(initialRequestId: String? = null) {
        val context = LocalContext.current
        var isLoggedIn by remember { mutableStateOf(persistentStore.getToken() != null) }
        var currentUser by remember { mutableStateOf(persistentStore.getUser()) }
        var selectedOtpRequest by remember {
            mutableStateOf<OtpRequestDto?>(
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
                        serializer = { response -> response.body<OtpRequestDto>() }
                    )?.let { request ->
                        selectedOtpRequest = request
                    }
                }
            }
        }
        var selectedOtpRecord by remember {
            mutableStateOf<OtpRecordDto?>(
                null
            )
        }
        var fillOtpRecord by remember {
            mutableStateOf<OtpRecordDto?>(
                null
            )
        }
        var selectedTab by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()

        fun handleLogout() {
            val token = persistentStore.getToken()
            val baseUrl = persistentStore.getServerUrl()
            val deviceId = persistentStore.getDeviceUuid()

            if (token != null) {
                scope.launch {
                    client.safeApiCall(
                        context = context,
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

            persistentStore.clearCredentials()
            isLoggedIn = false
            selectedOtpRequest = null
            currentUser = null
            selectedOtpRecord = null
            fillOtpRecord = null
            selectedTab = 0
        }

        fun setSelectedOtpRecord(r: OtpRecordDto?) {
            selectedOtpRecord = r
        }

        fun setFillOtpRecord(r: OtpRecordDto?) {
            fillOtpRecord = r
        }

        fun setSelectedOtpRequest(r: OtpRequestDto?) {
            selectedOtpRequest = r
        }

        fun setSelectedTab(t: Int) {
            selectedTab = t
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
                setSelectedOtpRequest(null)
            }
            OtpSubmissionScreen(
                client = client,
                persistentStore = persistentStore,
                currentRequestId = selectedOtpRequest!!.id,
                otpRecordName = selectedOtpRequest!!.otp_record_name,
                serviceIdentifier = selectedOtpRequest!!.service_identifier,
                pubKey = selectedOtpRequest!!.pub_key,
                onUnauthorized = { handleLogout() },
                onBack = { setSelectedOtpRequest(null) },
                onSuccess = { setSelectedOtpRequest(null) }
            )
        } else if (fillOtpRecord != null) {
            BackHandler {
                setFillOtpRecord(null)
            }
            OtpFillScreen(
                otpRecordName = fillOtpRecord!!.name,
                serviceIdentifier = fillOtpRecord!!.service_identifier,
                onBack = { setFillOtpRecord(null) }
            )
        } else if (selectedOtpRecord != null) {
            BackHandler {
                setSelectedOtpRecord(null)
            }
            OtpRecordTokensScreen(
                client = client,
                persistentStore = persistentStore,
                otpRecordId = selectedOtpRecord!!.id,
                otpRecordName = selectedOtpRecord!!.name,
                onBack = { setSelectedOtpRecord(null) },
                onUnauthorized = { handleLogout() }
            )
        } else {
            MainContainerScreen(
                client = client,
                persistentStore = persistentStore,
                currentUser = currentUser,
                selectedTab = selectedTab,
                onTabSelected = { setSelectedTab(it) },
                onSelectRequest = { setSelectedOtpRequest(it) },
                onSelectOtpRecord = { setSelectedOtpRecord(it) },
                onFillOtpRecord = { setFillOtpRecord(it) },
                onUnauthorized = { handleLogout() },
                onLogout = { handleLogout() }
            )
        }
    }
}
