package io.github.juanfunkin.wiiudownloader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.juanfunkin.wiiudownloader.R
import io.github.juanfunkin.wiiudownloader.Repo

private const val TAB_LIBRARY = 0
private const val TAB_QUEUE = 1
private const val TAB_SETTINGS = 2

@Composable
fun AppRoot(onPickFolder: () -> Unit, onStart: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_LIBRARY) }
    val queue by Repo.queue.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_LIBRARY,
                    onClick = { tab = TAB_LIBRARY },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_library)) },
                )
                NavigationBarItem(
                    selected = tab == TAB_QUEUE,
                    onClick = { tab = TAB_QUEUE },
                    icon = {
                        BadgedBox(badge = { if (queue.isNotEmpty()) Badge { Text("${queue.size}") } }) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(R.string.tab_queue)) },
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS,
                    onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (tab) {
            TAB_LIBRARY -> LibraryScreen(content)
            TAB_QUEUE -> QueueScreen(
                onStart = onStart,
                onOpenSettings = { tab = TAB_SETTINGS },
                modifier = content,
            )
            else -> SettingsScreen(onPickFolder = onPickFolder, modifier = content)
        }
    }
}
