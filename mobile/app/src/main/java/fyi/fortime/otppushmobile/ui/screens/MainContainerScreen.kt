package fyi.fortime.otppushmobile.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fyi.fortime.otppushmobile.data.PersistentStore
import fyi.fortime.otppushmobile.data.UserDto
import io.ktor.client.HttpClient

@Composable
fun MainContainerScreen(
    client: HttpClient,
    persistentStore: PersistentStore,
    currentUser: UserDto?,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSelectRequest: (fyi.fortime.otppushmobile.data.OtpRequestDto) -> Unit,
    onSelectOtpRecord: (fyi.fortime.otppushmobile.data.OtpRecordDto) -> Unit,
    onFillOtpRecord: (fyi.fortime.otppushmobile.data.OtpRecordDto) -> Unit,
    onUnauthorized: () -> Unit,
    onLogout: () -> Unit
) {
    val isAdmin = currentUser?.admin == true

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Records") },
                    label = { Text("Records") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    icon = {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = "Notifications"
                        )
                    },
                    label = { Text("Notifications") }
                )
                if (isAdmin) {
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { onTabSelected(3) },
                        icon = { Icon(Icons.Default.Person, contentDescription = "Users") },
                        label = { Text("Users") }
                    )
                }
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    icon = { Icon(Icons.Default.AccountCircle, contentDescription = "Me") },
                    label = { Text("Me") }
                )
            }
        }
    ) { innerPadding ->
        Surface(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> OtpRecordsTab(
                    client,
                    persistentStore,
                    onSelectOtpRecord,
                    onFillOtpRecord,
                    onUnauthorized
                )

                1 -> NotificationsTab(client, persistentStore, onSelectRequest, onUnauthorized)
                3 -> UsersTab(client, persistentStore, onUnauthorized) // Admin Tab
                2 -> MeTab(persistentStore, onLogout)
            }
        }
    }
}
