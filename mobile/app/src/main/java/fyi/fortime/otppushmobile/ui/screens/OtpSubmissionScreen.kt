package fyi.fortime.otppushmobile.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.SubmitOtpRequest
import fyi.fortime.otppushmobile.util.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpSubmissionScreen(
    client: HttpClient,
    persistentStore: PersistentStore,
    currentRequestId: String?,
    otpRecordName: String?,
    serviceIdentifier: String?,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    var accountName by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Submit OTP for $otpRecordName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (currentRequestId != null) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Awaiting OTP for Request",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = "#${currentRequestId.takeLast(6)}",
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                        )
                    }
                }
            } else {
                Text(
                    "Waiting for incoming requests...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text("Account ($serviceIdentifier)")
            TextField(
                value = accountName,
                onValueChange = {
                    accountName = it
                    if (it == serviceIdentifier) {
                        val clipboardOtp = getOtpFromClipboard(context)
                        if (clipboardOtp != null) {
                            otpCode = clipboardOtp
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentType = ContentType.Username
                    }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text("OTP")
            TextField(
                value = otpCode,
                onValueChange = { otpCode = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentType = ContentType.SmsOtpCode
                    }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (currentRequestId != null && otpCode.isNotBlank()) {
                        submitOtp(
                            scope,
                            client,
                            persistentStore,
                            context,
                            currentRequestId,
                            otpCode,
                            onUnauthorized
                        ) {
                            otpCode = ""
                            onSuccess()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = currentRequestId != null && otpCode.isNotBlank()
            ) {
                Text("Submit OTP")
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val otp = getOtpFromClipboard(context)
                    if (otp != null) {
                        otpCode = otp
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Check Clipboard for OTP")
            }
        }
    }
}

private fun submitOtp(
    scope: CoroutineScope,
    client: HttpClient,
    persistentStore: PersistentStore,
    context: Context,
    requestId: String,
    otp: String,
    onUnauthorized: () -> Unit,
    onSuccess: () -> Unit
) {
    scope.launch {
        val token = persistentStore.getToken() ?: return@launch onUnauthorized()
        val baseUrl = persistentStore.getServerUrl()

        client.safeApiCall(
            context = context,
            builder = {
                method = HttpMethod.Post
                url("$baseUrl/api/mobile/otp/submit")
                header("Authorization", "Bearer $token")
                contentType(Application.Json)
                setBody(SubmitOtpRequest(requestId, otp))
            },
            onUnauthorized = onUnauthorized,
            successCode = HttpStatusCode.OK,
            serializer = { /* no body */ }
        )?.let {
            Log.d("OtpPush", "OTP submitted successfully")
            onSuccess()
        }
    }
}

private fun getOtpFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).text?.toString() ?: return null

    val otpRegex = Regex("""\b\d{6,8}\b""")
    return otpRegex.find(text)?.value
}
