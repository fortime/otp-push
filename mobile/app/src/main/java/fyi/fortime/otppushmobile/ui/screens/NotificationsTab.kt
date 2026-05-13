package fyi.fortime.otppushmobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import fyi.fortime.otppushmobile.data.OtpRequestDto
import fyi.fortime.otppushmobile.data.PaginatedResponse
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.util.safeApiCall
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsTab(
    client: HttpClient,
    persistentStore: PersistentStore,
    onSelectRequest: (OtpRequestDto) -> Unit,
    onUnauthorized: () -> Unit
) {
    var items by remember { mutableStateOf(listOf<OtpRequestDto>()) }
    var page by remember { mutableLongStateOf(1L) }
    var hasMore by remember { mutableStateOf(true) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    suspend fun fetchData(reset: Boolean = false) {
        if (isLoading || (!hasMore && !reset)) return
        isLoading = true
        if (reset) {
            page = 1L
            hasMore = true
        }

        val token = persistentStore.getToken() ?: return onUnauthorized()
        val baseUrl = persistentStore.getServerUrl()

        client.safeApiCall(
            context = context,
            builder = {
                method = HttpMethod.Get
                url("$baseUrl/api/mobile/requests?page=$page&limit=20")
                header("Authorization", "Bearer $token")
            },
            onUnauthorized = onUnauthorized,
            serializer = { it.body<PaginatedResponse<OtpRequestDto>>() }
        )?.let { paginated ->
            if (reset) {
                items = paginated.items
            } else {
                items = items + paginated.items
            }
            hasMore = items.size < paginated.total
            page++
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
                    onClick = { onSelectRequest(item) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Service: ${item.otp_record_name} (#${item.id.takeLast(6)})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Requested at: ${item.created_at}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap to capture and submit OTP",
                            color = MaterialTheme.colorScheme.primary
                        )
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
}
