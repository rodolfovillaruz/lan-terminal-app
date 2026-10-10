package com.lanterminal.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lanterminal.remote.ui.MainViewModel
import com.lanterminal.remote.ui.SettingsScreen
import com.lanterminal.remote.ui.WindowsScreen
import com.lanterminal.remote.ui.theme.LanTerminalTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LanTerminalTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Keep the live connection open only while the app is on screen.
    LifecycleStartEffect(vm) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }

    if (!state.loaded) {
        Box(Modifier.fillMaxSize())
        return
    }

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val configured = state.connection?.isComplete == true

    if (!configured || settingsOpen) {
        BackHandler(enabled = configured) { settingsOpen = false }
        SettingsScreen(
            initial = state.connection,
            canGoBack = configured,
            onBack = { settingsOpen = false },
            onSave = vm::testAndSave,
        )
    } else {
        WindowsScreen(
            state = state,
            onOpenSettings = { settingsOpen = true },
            onRefresh = vm::refresh,
            onCreate = vm::createWindow,
            onMoveMode = vm::setMoveMode,
            onFullscreen = vm::setFullscreen,
            onResetPosition = vm::resetPosition,
            onFocus = vm::focus,
            onClose = vm::closeWindow,
            onMessageShown = vm::messageShown,
        )
    }
}
