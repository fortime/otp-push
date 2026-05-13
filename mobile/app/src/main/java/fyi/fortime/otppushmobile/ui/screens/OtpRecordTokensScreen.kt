package fyi.fortime.otppushmobile.ui.screens

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fyi.fortime.otppushmobile.data.ApiAccessTokenDto
import fyi.fortime.otppushmobile.data.CreateTokenRequest
import fyi.fortime.otppushmobile.data.CreateTokenResponse
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.util.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpRecordTokensScreen(
    client: HttpClient,
    persistentStore: PersistentStore,
    otpRecordId: String,
    otpRecordName: String,
    onBack: () -> Unit,
    onUnauthorized: () -> Unit
) {
    var tokens by remember { mutableStateOf<List<ApiAccessTokenDto>>(listOf()) }
    var isLoading by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var createdTokenResponse by remember { mutableStateOf<CreateTokenResponse?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun fetchTokens() {
        isLoading = true
        scope.launch {
            val token = persistentStore.getToken() ?: return@launch onUnauthorized()
            val baseUrl = persistentStore.getServerUrl()

            client.safeApiCall(
                context = context,
                builder = {
                    method = HttpMethod.Get
                    url("$baseUrl/api/otp-records/$otpRecordId/tokens")
                    header("Authorization", "Bearer $token")
                },
                onUnauthorized = onUnauthorized,
                serializer = { it.body<List<ApiAccessTokenDto>>() }
            )?.let { fetchedTokens ->
                tokens = fetchedTokens
            }
            isLoading = false
        }
    }

    LaunchedEffect(otpRecordId) {
        fetchTokens()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tokens for $otpRecordName") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Token")
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isLoading && tokens.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tokens) { apiToken ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) { // Left side for name and masked token
                                    Text(
                                        apiToken.name,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        apiToken.masked_token,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // For dates
                                        Text(
                                            "Created: ${apiToken.created_at}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        apiToken.last_used_at?.let {
                                            Text(
                                                "Last used: $it",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                                // Right side for delete button
                                IconButton(onClick = {
                                    scope.launch {
                                        val token = persistentStore.getToken()
                                            ?: return@launch onUnauthorized()
                                        val baseUrl = persistentStore.getServerUrl()
                                        client.safeApiCall(
                                            context = context,
                                            builder = {
                                                method = HttpMethod.Delete
                                                url("$baseUrl/api/otp-records/$otpRecordId/tokens/${apiToken.id}")
                                                header("Authorization", "Bearer $token")
                                            },
                                            onUnauthorized = onUnauthorized,
                                            successCode = HttpStatusCode.NoContent,
                                            serializer = { /* no body */ }
                                        )?.let {
                                            fetchTokens()
                                        }
                                    }
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Create API Token") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Token Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val token = persistentStore.getToken() ?: return@launch onUnauthorized()
                        val baseUrl = persistentStore.getServerUrl()
                        client.safeApiCall(
                            context = context,
                            builder = {
                                method = HttpMethod.Post
                                url("$baseUrl/api/otp-records/$otpRecordId/tokens")
                                header("Authorization", "Bearer $token")
                                contentType(ContentType.Application.Json)
                                setBody(CreateTokenRequest(name))
                            },
                            onUnauthorized = onUnauthorized,
                            successCode = HttpStatusCode.Created,
                            serializer = { it.body<CreateTokenResponse>() }
                        )?.let { response ->
                            createdTokenResponse = response
                            showAddDialog = false
                            fetchTokens()
                        }
                    }
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (createdTokenResponse != null) {
        val currentCreatedToken = createdTokenResponse!!
        val clipboardManager = LocalClipboard.current
        AlertDialog(
            onDismissRequest = { createdTokenResponse = null },
            title = { Text("Token Created") },
            text = {
                Column {
                    Text("Please copy your token now. It will not be shown again.")
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = currentCreatedToken.token,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val clipEntry = ClipEntry(
                                    ClipData.newPlainText(
                                        "token",
                                        currentCreatedToken.token
                                    )
                                )
                                clipboardManager.setClipEntry(clipEntry)
                                Toast.makeText(
                                    context,
                                    "Token copied to clipboard",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy Token")
                    }
                }
            },
            confirmButton = {
                Button(onClick = { createdTokenResponse = null }) {
                    Text("Done")
                }
            }
        )
    }
}

@Composable
fun SelectionContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer(modifier = modifier) {
        content()
    }
}
