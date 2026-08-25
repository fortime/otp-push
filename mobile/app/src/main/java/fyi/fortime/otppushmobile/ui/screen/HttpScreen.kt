package fyi.fortime.otppushmobile.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fyi.fortime.otppushmobile.AppContext
import fyi.fortime.otppushmobile.HistoryRecord
import fyi.fortime.otppushmobile.ui.component.MenuItem

@Composable
fun HttpScreen(
    appContext: AppContext,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MenuItem(
            icon = Icons.AutoMirrored.Filled.List,
            title = "Records",
            subtitle = "Otp Records",
            onClick = {
                appContext.history.push(HistoryRecord("Otp Records", true, {
                    RecordsScreen(appContext)
                }))
            },
        )
        MenuItem(
            icon = Icons.Default.Notifications,
            title = "Requests",
            subtitle = "Otp Requests",
            onClick = {
                appContext.history.push(HistoryRecord("Otp Requests", true, {
                    RequestsScreen(appContext)
                }))
            },
        )
        MenuItem(
            icon = Icons.Default.People,
            title = "Users",
            subtitle = "",
            onClick = {
                appContext.history.push(HistoryRecord("Users", true, {
                    UsersScreen(appContext)
                }))
            },
        )
        MenuItem(
            icon = Icons.Default.AccountCircle,
            title = "Me",
            subtitle = "",
            onClick = {
                appContext.history.push(HistoryRecord("Me", true, {
                    MeScreen(appContext)
                }))
            },
        )
    }
}
