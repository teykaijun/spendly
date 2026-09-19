package com.spendly.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.spendly.ui.calendar.CalendarScreen
import com.spendly.ui.inbox.InboxScreen
import com.spendly.ui.inbox.InboxViewModel
import com.spendly.ui.quickadd.QuickAddScreen
import com.spendly.ui.settings.SettingsScreen

enum class Tab(val label: String, val icon: ImageVector) {
    Add("Add", Icons.Default.Add),
    Calendar("Calendar", Icons.Default.DateRange),
    Inbox("Inbox", Icons.Default.Notifications),
    Settings("Settings", Icons.Default.Settings),
}

@Composable
fun SpendlyRoot(
    requestedTab: Tab?,
    onRequestedTabHandled: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Add) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // A notification tap can ask for a specific tab at any point in the lifecycle.
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabHandled()
        }
    }

    val inboxViewModel: InboxViewModel = viewModel()
    val pendingCount by inboxViewModel.pendingCount.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = { Text(entry.label) },
                        icon = {
                            if (entry == Tab.Inbox && pendingCount > 0) {
                                BadgedBox(badge = { Badge { Text(pendingCount.toString()) } }) {
                                    Icon(entry.icon, contentDescription = entry.label)
                                }
                            } else {
                                Icon(entry.icon, contentDescription = entry.label)
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (tab) {
                Tab.Add -> QuickAddScreen(
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                    onSeeCalendar = { tab = Tab.Calendar },
                )

                Tab.Calendar -> CalendarScreen()

                Tab.Inbox -> InboxScreen(
                    viewModel = inboxViewModel,
                    snackbarHostState = snackbarHostState,
                    scope = scope,
                )

                Tab.Settings -> SettingsScreen()
            }
        }
    }
}
