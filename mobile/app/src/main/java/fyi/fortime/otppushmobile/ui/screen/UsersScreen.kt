package fyi.fortime.otppushmobile.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import fyi.fortime.otppushmobile.AppContext
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.UpdateLimitsRequest
import fyi.fortime.otppushmobile.data.UserDto
import fyi.fortime.otppushmobile.data.UserLimitsDto
import fyi.fortime.otppushmobile.util.ApiClient
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
fun UsersScreen(
    appContext: AppContext
) {
    val persistentStore = appContext.persistentStore
    val apiClient = appContext.apiClient
    var users by remember { mutableStateOf(listOf<UserDto>()) }
    var selectedUser by remember { mutableStateOf<UserDto?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun setRefreshing(r: Boolean) {
        isRefreshing = r
    }

    fun setSelectedUser(u: UserDto?) {
        selectedUser = u
    }

    suspend fun fetchUsers() {
        isLoading = true
        val token = persistentStore.getToken() ?: return
        val baseUrl = persistentStore.getServerUrl()

        apiClient.safeApiCall(
            builder = {
                method = HttpMethod.Get
                url("$baseUrl/api/http/admin/users")
                header("Authorization", "Bearer $token")
            },
            serializer = { it.body<List<UserDto>>() }
        )?.let { fetchedUsers ->
            users = fetchedUsers
        }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        fetchUsers()
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            scope.launch {
                setRefreshing(true)
                fetchUsers()
                setRefreshing(false)
            }
        },
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(users) { user ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { selectedUser = user }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = user.email,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatusBadge(label = if (user.admin) "Admin" else "User")
                            StatusBadge(label = if (user.enabled) "Enabled" else "Disabled")
                        }
                    }
                }
            }

            if (isLoading && !isRefreshing) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }

    if (selectedUser != null) {
        UserEditDialog(
            apiClient = apiClient,
            persistentStore = persistentStore,
            user = selectedUser!!,
            onDismiss = { setSelectedUser(null) },
            onSave = onSave@{ userDto, enabled, admin, limits, maxRecordsStr, maxTokensStr ->
                val maxRecords = maxRecordsStr.toIntOrNull() ?: return@onSave
                val maxTokens = maxTokensStr.toIntOrNull() ?: return@onSave
                val limits = limits // limits is UserLimitsDto?

                scope.launch {
                    val token = persistentStore.getToken() ?: return@launch
                    val baseUrl = persistentStore.getServerUrl()
                    val originalUser = userDto // Reference the passed userDto for comparison

                    if (enabled != originalUser.enabled) {
                        apiClient.safeApiCall(
                            builder = {
                                method = HttpMethod.Put
                                url("$baseUrl/api/http/admin/users/${originalUser.id}/enable")
                                header("Authorization", "Bearer $token")
                                contentType(ContentType.Application.Json)
                                setBody(enabled)
                            },
                            successCode = HttpStatusCode.OK,
                            serializer = { /* no body to deserialize */ }
                        ) ?: return@launch // If call failed, stop here
                    }
                    if (admin != originalUser.admin) {
                        apiClient.safeApiCall(
                            builder = {
                                method = HttpMethod.Put
                                url("$baseUrl/api/http/admin/users/${originalUser.id}/admin")
                                header("Authorization", "Bearer $token")
                                contentType(ContentType.Application.Json)
                                setBody(admin)
                            },
                            successCode = HttpStatusCode.OK,
                            serializer = { /* no body to deserialize */ }
                        ) ?: return@launch // If call failed, stop here
                    }

                    // Conditionally update limits only if they have changed from original fetched limits
                    val originalMaxRecords = limits?.max_otp_records
                    val originalMaxTokens = limits?.max_tokens_per_record

                    if (maxRecords != originalMaxRecords || maxTokens != originalMaxTokens) {
                        apiClient.safeApiCall(
                            builder = {
                                method = HttpMethod.Put
                                url("$baseUrl/api/http/admin/users/${originalUser.id}/limits")
                                header("Authorization", "Bearer $token")
                                contentType(ContentType.Application.Json)
                                setBody(UpdateLimitsRequest(maxRecords, maxTokens))
                            },
                            successCode = HttpStatusCode.OK,
                            serializer = { /* no body to deserialize */ }
                        ) ?: return@launch
                    }

                    setSelectedUser(null)
                    fetchUsers()
                }
            }
        )
    }
}

@Composable
fun UserEditDialog(
    apiClient: ApiClient,
    persistentStore: PersistentStore,
    user: UserDto,
    onDismiss: () -> Unit,
    onSave: (UserDto, Boolean, Boolean, UserLimitsDto?, String, String) -> Unit
) {
    var enabled by remember { mutableStateOf(user.enabled) }
    var admin by remember { mutableStateOf(user.admin) }
    var limits by remember { mutableStateOf<UserLimitsDto?>(null) } // To store fetched limits
    var maxRecords by remember { mutableStateOf("") }
    var maxTokens by remember { mutableStateOf("") }

    // Query limits on dialog open
    LaunchedEffect(Unit) {
        val token = persistentStore.getToken() ?: return@LaunchedEffect
        val baseUrl = persistentStore.getServerUrl()

        apiClient.safeApiCall(
            builder = {

                method = HttpMethod.Get
                url("$baseUrl/api/http/admin/users/${user.id}/limits")
                header("Authorization", "Bearer $token")
            },
            serializer = { it.body<UserLimitsDto>() }
        )?.let { fetchedLimits ->
            limits = fetchedLimits
            maxRecords = fetchedLimits.max_otp_records.toString()
            maxTokens = fetchedLimits.max_tokens_per_record.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit User: ${user.email}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Admin")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = admin, onCheckedChange = { admin = it })
                }
                OutlinedTextField(
                    value = maxRecords,
                    onValueChange = { maxRecords = it },
                    label = { Text("Max OTP Records") }
                )
                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it },
                    label = { Text("Max Tokens/Record") }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                // Pass all values to parent's onSave lambda
                onSave(user, enabled, admin, limits, maxRecords, maxTokens)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun StatusBadge(label: String) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
