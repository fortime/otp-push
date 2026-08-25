package fyi.fortime.otppushmobile.ui.screen

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fyi.fortime.otppushmobile.AppContext
import fyi.fortime.otppushmobile.data.UserDto
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpMethod

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContainerScreen(
    appContext: AppContext
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var selectedIndex by remember { mutableIntStateOf(value = appContext.persistentStore.getMainScreenTabIndex()) }
    val options = listOf(
        Pair("Http", Icon(Icons.Default.Public, contentDescription = "Http Otp Push")),
        Pair("Bluetooth", Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth Otp Push"))
    )
    val top = appContext.history.top()
    val needLogin =
        !appContext.loggedIn && ((top == null && selectedIndex == 0) || top?.needLogin ?: false)
    val title = if (needLogin) {
        "Otp Push Mobile"
    } else {
        top?.title ?: "Otp Push Mobile"
    }

    // Fetch user info on startup if missing
    LaunchedEffect(appContext.loggedIn) {
        if (appContext.loggedIn && appContext.currentUser() == null) {
            val token = appContext.persistentStore.getToken()
            val baseUrl = appContext.persistentStore.getServerUrl()
            if (token != null) {
                appContext.apiClient.safeApiCall(
                    builder = {
                        method = HttpMethod.Get
                        url("$baseUrl/api/http/users/me")
                        header("Authorization", "Bearer $token")
                    },
                    serializer = { it.body<UserDto>() }
                )?.let { user ->
                    appContext.persistentStore.saveUser(user)
                }
            }
        }
    }

    BackHandler {
        if (appContext.history.isNotEmpty()) {
            appContext.history.pop()
        } else {
            activity?.finish()
        }
    }

    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (appContext.history.isNotEmpty() && !needLogin) {
                        IconButton(onClick = { appContext.history.pop() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (top == null || needLogin) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SingleChoiceSegmentedButtonRow {
                        options.forEachIndexed { index, option ->
                            SegmentedButton(
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = options.size
                                ),
                                onClick = {
                                    appContext.persistentStore.saveMainScreenTabIndex(index)
                                    selectedIndex = index
                                    if (selectedIndex == 1) {
                                        appContext.history.clear()
                                    }
                                },
                                selected = index == selectedIndex,
                                icon = { option.second },
                                label = { Text(option.first) }
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            if (needLogin) {
                LoginScreen(
                    appContext = appContext,
                    onLoginSuccess = {
                        appContext.loggedIn = true
                    }
                )
            } else if (top == null) {
                when (selectedIndex) {
                    0 -> HttpScreen(appContext)
                    1 -> BluetoothScreen(appContext)
                }
            } else {
                top.Content()
            }
        }
    }
}
