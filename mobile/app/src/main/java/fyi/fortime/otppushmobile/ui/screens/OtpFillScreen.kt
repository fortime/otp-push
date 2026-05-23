package fyi.fortime.otppushmobile.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpFillScreen(
    otpRecordName: String?,
    serviceIdentifier: String?,
    onBack: () -> Unit
) {
    var accountName by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fill OTP for $otpRecordName") },
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
            Text("Helper for autofill or manual entry", style = MaterialTheme.typography.bodyMedium)

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

private fun getOtpFromClipboard(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    val text = clip.getItemAt(0).text?.toString() ?: return null

    val otpRegex = Regex("""\b\d{6,8}\b""")
    return otpRegex.find(text)?.value
}
