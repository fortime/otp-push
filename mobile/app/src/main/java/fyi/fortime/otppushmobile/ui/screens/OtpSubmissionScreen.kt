package fyi.fortime.otppushmobile.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

private const val EC_ENVELOPE_VERSION = "v1"
private const val EC_ENVELOPE_ALGORITHM = "ecdh-aes-256-gcm"
private const val EC_ENVELOPE_INFO = "otp-push password ecdh-aes-256-gcm v1"
private const val RSA_ENVELOPE_VERSION = "v1"
private const val RSA_ENVELOPE_ALGORITHM = "rsa-oaep-sha256"
private const val RSA_ENVELOPE_INFO = "otp-push password rsa-oaep-sha256 v1"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpSubmissionScreen(
    client: HttpClient,
    persistentStore: PersistentStore,
    currentRequestId: String?,
    otpRecordName: String?,
    serviceIdentifier: String?,
    pubKey: String?,
    onUnauthorized: () -> Unit,
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    var accountName by remember { mutableStateOf("") }
    var secretValue by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPasswordRequest = !pubKey.isNullOrBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isPasswordRequest) {
                            "Submit Password for $otpRecordName"
                        } else {
                            "Submit OTP for $otpRecordName"
                        }
                    )
                },
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
                        if (isPasswordRequest) "Awaiting Password for Request" else "Awaiting OTP for Request",
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
                    if (!isPasswordRequest && it == serviceIdentifier) {
                        val clipboardOtp = getOtpFromClipboard(context)
                        if (clipboardOtp != null) {
                            secretValue = clipboardOtp
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

            Text(if (isPasswordRequest) "Password" else "OTP")
            TextField(
                value = secretValue,
                onValueChange = { secretValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentType =
                            if (isPasswordRequest) ContentType.Password else ContentType.SmsOtpCode
                    },
                visualTransformation =
                    if (isPasswordRequest) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isPasswordRequest) KeyboardType.Password else KeyboardType.Number
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (currentRequestId != null && secretValue.isNotBlank()) {
                        submitOtp(
                            scope,
                            client,
                            persistentStore,
                            context,
                            currentRequestId,
                            secretValue,
                            pubKey,
                            onUnauthorized
                        ) {
                            secretValue = ""
                            onSuccess()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = currentRequestId != null && secretValue.isNotBlank()
            ) {
                Text(if (isPasswordRequest) "Submit Password" else "Submit OTP")
            }

            if (!isPasswordRequest) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = {
                        val otp = getOtpFromClipboard(context)
                        if (otp != null) {
                            secretValue = otp
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Check Clipboard for OTP")
                }
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
    secretValue: String,
    pubKey: String?,
    onUnauthorized: () -> Unit,
    onSuccess: () -> Unit
) {
    scope.launch {
        val token = persistentStore.getToken() ?: return@launch onUnauthorized()
        val baseUrl = persistentStore.getServerUrl()
        val submittedValue = try {
            if (pubKey.isNullOrBlank()) {
                secretValue
            } else {
                encryptWithPublicKey(secretValue, pubKey)
            }
        } catch (e: Exception) {
            Log.w("OtpPush", "failed to encrypt password", e)
            Toast.makeText(context, "Failed to encrypt password", Toast.LENGTH_LONG).show()
            return@launch
        }

        client.safeApiCall(
            context = context,
            builder = {
                method = HttpMethod.Post
                url("$baseUrl/api/mobile/otp/submit")
                header("Authorization", "Bearer $token")
                contentType(Application.Json)
                setBody(SubmitOtpRequest(requestId, submittedValue))
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

private fun encryptWithPublicKey(plainText: String, pem: String): String {
    val publicKey = parsePublicKey(pem)
    return when (publicKey.algorithm.uppercase()) {
        "RSA" -> encryptWithRsaPublicKey(plainText, publicKey)
        "EC", "ECDH", "ECDSA" -> encryptWithEcPublicKey(plainText, publicKey)
        else -> throw IllegalArgumentException(
            "Unsupported password encryption key algorithm: ${publicKey.algorithm}"
        )
    }
}

private fun encryptWithRsaPublicKey(plainText: String, publicKey: PublicKey): String {
    val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
    cipher.init(
        Cipher.ENCRYPT_MODE,
        publicKey,
        OAEPParameterSpec(
            "SHA-256",
            "MGF1",
            MGF1ParameterSpec.SHA256,
            PSource.PSpecified.DEFAULT
        )
    )
    val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
    val infoBytes = RSA_ENVELOPE_INFO.toByteArray(StandardCharsets.UTF_8)
    return listOf(
        RSA_ENVELOPE_VERSION,
        RSA_ENVELOPE_ALGORITHM,
        base64UrlNoPadding(infoBytes),
        base64UrlNoPadding(encrypted)
    ).joinToString(".")
}

private fun encryptWithEcPublicKey(plainText: String, publicKey: PublicKey): String {
    val ecPublicKey = publicKey as? ECPublicKey
        ?: throw IllegalArgumentException("EC public key is not an ECPublicKey")
    val keyPairGenerator = KeyPairGenerator.getInstance("EC")
    keyPairGenerator.initialize(ecPublicKey.params)
    val ephemeralKeyPair = keyPairGenerator.generateKeyPair()

    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(ephemeralKeyPair.private)
    agreement.doPhase(ecPublicKey, true)
    val sharedSecret = agreement.generateSecret()
    val infoBytes = EC_ENVELOPE_INFO.toByteArray(StandardCharsets.UTF_8)
    val aesKey = deriveEcAesKey(sharedSecret, ephemeralKeyPair.public.encoded, infoBytes)

    val iv = ByteArray(12)
    SecureRandom().nextBytes(iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(infoBytes)
    val ciphertext = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))

    return listOf(
        EC_ENVELOPE_VERSION,
        EC_ENVELOPE_ALGORITHM,
        base64UrlNoPadding(infoBytes),
        base64UrlNoPadding(ephemeralKeyPair.public.encoded),
        base64UrlNoPadding(iv),
        base64UrlNoPadding(ciphertext)
    ).joinToString(".")
}

private fun deriveEcAesKey(
    sharedSecret: ByteArray,
    ephemeralPublicKey: ByteArray,
    info: ByteArray
): ByteArray {
    val salt = ephemeralPublicKey
    val pseudoRandomKey = hmacSha256(salt, sharedSecret)
    return hmacSha256(pseudoRandomKey, info + byteArrayOf(1)).copyOf(32)
}

private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun base64UrlNoPadding(bytes: ByteArray): String {
    return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

private fun parsePublicKey(pem: String): PublicKey {
    PEMParser(StringReader(pem)).use { parser ->
        val parsed = parser.readObject()
        val converter = JcaPEMKeyConverter()
        return when (parsed) {
            is SubjectPublicKeyInfo -> converter.getPublicKey(parsed)
            is X509CertificateHolder -> converter.getPublicKey(parsed.subjectPublicKeyInfo)
            else -> throw IllegalArgumentException("Unsupported PEM public key format")
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
