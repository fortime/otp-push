package fyi.fortime.otppushmobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fyi.fortime.otppushmobile.data.CachedOtpRecords
import fyi.fortime.otppushmobile.data.CreateOtpRecordRequest
import fyi.fortime.otppushmobile.data.OtpRecordDto
import fyi.fortime.otppushmobile.data.PaginatedResponse
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
fun OtpRecordsTab(
    client: HttpClient,
    persistentStore: PersistentStore,
    onSelectRecord: (OtpRecordDto) -> Unit,
    onFillRecord: (OtpRecordDto) -> Unit,
    onUnauthorized: () -> Unit
) {
    var items by remember { mutableStateOf(listOf<OtpRecordDto>()) }
    var page by remember { mutableLongStateOf(1L) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var recordToDelete by remember { mutableStateOf<OtpRecordDto?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    fun setShowAddDialog(b: Boolean) {
        showAddDialog = b
    }

    fun setRecordToDelete(r: OtpRecordDto?) {
        recordToDelete = r
    }

    suspend fun fetchData(reset: Boolean = false) {
        if (isLoading || (!hasMore && !reset)) return
        isLoading = true
        if (reset) {
            page = 1L
            hasMore = true
        }

        val token = persistentStore.getToken() ?: return onUnauthorized()
        val baseUrl = persistentStore.getServerUrl()

        val paginated = client.safeApiCall(
            context = context,
            builder = {
                method = HttpMethod.Get
                url("$baseUrl/api/otp-records?page=$page&limit=20")
                header("Authorization", "Bearer $token")
            },
            onUnauthorized = onUnauthorized,
            serializer = { it.body<PaginatedResponse<OtpRecordDto>>() }
        )
        if (paginated == null) {
            if (reset) {
                // use the cached records
                val cached = persistentStore.getCachedOtpRecords()
                items = cached.records
                hasMore = cached.hasMore
                page = cached.page
            }
        } else {
            items = if (reset) {
                paginated.items
            } else {
                items + paginated.items
            }
            hasMore = items.size < paginated.total
            page++
            persistentStore.saveCachedOtpRecords(CachedOtpRecords(items, page, hasMore))
        }

        isLoading = false
    }

    LaunchedEffect(Unit) {
        fetchData(reset = true)
    }

    // Infinite scroll trigger
    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= items.size - 5
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && hasMore) {
            fetchData()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    isRefreshing = true
                    fetchData(reset = true)
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelectRecord(item) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    item.service_identifier,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { onFillRecord(item) },
                                    contentPadding = PaddingValues(
                                        horizontal = 12.dp,
                                        vertical = 0.dp
                                    ),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Fill", style = MaterialTheme.typography.labelMedium)
                                }

                                IconButton(onClick = { recordToDelete = item }) {
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

                if (isLoading && !isRefreshing) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { setShowAddDialog(true) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Record")
        }
    }

    if (recordToDelete != null) {
        AlertDialog(
            onDismissRequest = { setRecordToDelete(null) },
            title = { Text("Delete OTP Record") },
            text = { Text("Are you sure you want to delete '${recordToDelete?.name}'? This will also delete all associated API tokens.") },
            confirmButton = {
                Button(
                    onClick = {
                        val record = recordToDelete ?: return@Button
                        scope.launch {
                            val token = persistentStore.getToken() ?: return@launch onUnauthorized()
                            val baseUrl = persistentStore.getServerUrl()

                            client.safeApiCall(
                                context = context,
                                builder = {
                                    method = HttpMethod.Delete
                                    url("$baseUrl/api/otp-records/${record.id}")
                                    header("Authorization", "Bearer $token")
                                },
                                onUnauthorized = onUnauthorized,
                                successCode = HttpStatusCode.NoContent,
                                serializer = { /* no body */ }
                            )?.let {
                                fetchData(reset = true)
                                setRecordToDelete(null)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { setRecordToDelete(null) }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddDialog) {
        AddOtpRecordDialog(
            onDismiss = { setShowAddDialog(false) },
            onConfirm = { name, identifier ->
                scope.launch {
                    val token = persistentStore.getToken() ?: return@launch onUnauthorized()
                    val baseUrl = persistentStore.getServerUrl()

                    client.safeApiCall(
                        context = context,
                        builder = {
                            method = HttpMethod.Post
                            url("$baseUrl/api/otp-records")
                            header("Authorization", "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(CreateOtpRecordRequest(name, identifier))
                        },
                        onUnauthorized = onUnauthorized,
                        successCode = HttpStatusCode.Created,
                        serializer = { /* no body */ }
                    )?.let {
                        fetchData(reset = true)
                        setShowAddDialog(false)
                    }
                }
            }
        )
    }
}

@Composable
fun AddOtpRecordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var identifier by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add OTP Record") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (e.g. Github)") })
                OutlinedTextField(
                    value = identifier,
                    onValueChange = { identifier = it },
                    label = { Text("Service Identifier (e.g. email)") })
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, identifier) }) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
