package com.lanterminal.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lanterminal.remote.data.TermWindow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowsScreen(
    state: UiState,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onMoveMode: (String, Boolean) -> Unit,
    onFullscreen: (String, Boolean) -> Unit,
    onResetPosition: (String) -> Unit,
    onFocus: (String) -> Unit,
    onClose: (String) -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var confirmClose by remember { mutableStateOf<TermWindow?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LAN Terminal")
                        state.connection?.let {
                            Text(
                                "${it.host}:${it.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    StatusPill(state.status)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Connection settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!state.creating) onCreate() },
                icon = {
                    if (state.creating) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Add, contentDescription = null)
                },
                text = { Text("New window") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.windows.isEmpty()) {
                    item { EmptyState(state.status) }
                }
                items(state.windows, key = { it.id }) { window ->
                    WindowCard(
                        window = window,
                        busy = window.id in state.busy,
                        onMoveMode = { onMoveMode(window.id, it) },
                        onFullscreen = { onFullscreen(window.id, it) },
                        onResetPosition = { onResetPosition(window.id) },
                        onFocus = { onFocus(window.id) },
                        onClose = { confirmClose = window },
                    )
                }
            }
        }
    }

    confirmClose?.let { window ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text("Close ${window.process}?") },
            text = { Text("The window closes and its shell, plus anything running in it, is ended.") },
            confirmButton = {
                TextButton(onClick = {
                    onClose(window.id)
                    confirmClose = null
                }) { Text("Close", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun StatusPill(status: LinkStatus) {
    val (label, color) = when (status) {
        LinkStatus.Live -> "Live" to Color(0xFF43A047)
        LinkStatus.Connecting -> "Connecting" to Color(0xFFFFA000)
        is LinkStatus.Offline -> "Offline" to MaterialTheme.colorScheme.error
        LinkStatus.NotConfigured -> "Not set up" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        Surface(shape = CircleShape, color = color, modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun EmptyState(status: LinkStatus) {
    val (title, body) = when (status) {
        is LinkStatus.Offline -> "Not connected" to "${status.reason}. Check that the desktop app is running and " +
            "this phone is on the same network. Pull down to retry."
        LinkStatus.Connecting -> "Connecting…" to "Waiting for the desktop app."
        LinkStatus.NotConfigured -> "Not set up" to "Open settings to enter the desktop address and token."
        LinkStatus.Live -> "No terminal windows" to "Tap New window to open one on the desktop."
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 96.dp, start = 24.dp, end = 24.dp),
    ) {
        Icon(
            Icons.Filled.Terminal,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WindowCard(
    window: TermWindow,
    busy: Boolean,
    onMoveMode: (Boolean) -> Unit,
    onFullscreen: (Boolean) -> Unit,
    onResetPosition: () -> Unit,
    onFocus: () -> Unit,
    onClose: () -> Unit,
) {
    Card(
        colors = if (window.moveMode) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        window.process,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        window.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onClose, enabled = !busy) {
                    Icon(Icons.Filled.Close, contentDescription = "Close window", tint = MaterialTheme.colorScheme.error)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "x ${window.x}, y ${window.y} · ${window.width}×${window.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (window.focused) Badge("Focused")
                if (window.minimized) Badge("Minimized")
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.OpenWith, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Move mode", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (window.moveMode) "Drag the window with the mouse; Esc on the desktop ends it"
                        else "Lets you drag the borderless window",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = window.moveMode, onCheckedChange = onMoveMode, enabled = !busy)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Fullscreen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fullscreen", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (window.fullscreen) "Turn off to restore the previous size and position"
                        else "Covers the whole screen, panel included",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = window.fullscreen, onCheckedChange = onFullscreen, enabled = !busy)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onResetPosition,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.VerticalAlignTop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset to 0,0")
                }
                OutlinedButton(
                    onClick = onFocus,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Focus")
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
