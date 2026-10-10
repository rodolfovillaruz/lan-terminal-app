package com.lanterminal.remote.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanterminal.remote.data.ApiException
import com.lanterminal.remote.data.Connection
import com.lanterminal.remote.data.SettingsStore
import com.lanterminal.remote.data.TermWindow
import com.lanterminal.remote.data.TerminalApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface LinkStatus {
    data object NotConfigured : LinkStatus
    data object Connecting : LinkStatus
    data object Live : LinkStatus
    data class Offline(val reason: String) : LinkStatus
}

data class UiState(
    val loaded: Boolean = false,
    val connection: Connection? = null,
    val status: LinkStatus = LinkStatus.NotConfigured,
    val windows: List<TermWindow> = emptyList(),
    /** Window ids with a request in flight, so their buttons can be disabled. */
    val busy: Set<String> = emptySet(),
    val creating: Boolean = false,
    val refreshing: Boolean = false,
    /** One-shot message for the snackbar. */
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var api: TerminalApi? = null
    private var eventsJob: Job? = null
    private var started = false

    init {
        viewModelScope.launch {
            settings.connection.distinctUntilChanged().collect { conn ->
                api?.close()
                api = if (conn.isComplete) TerminalApi(conn) else null
                _state.update {
                    it.copy(
                        loaded = true,
                        connection = conn,
                        windows = emptyList(),
                        status = if (conn.isComplete) LinkStatus.Connecting else LinkStatus.NotConfigured,
                    )
                }
                if (started) startEvents()
            }
        }
    }

    /** Called when the screen becomes visible; keeps the socket closed in the background. */
    fun onStart() {
        started = true
        startEvents()
    }

    fun onStop() {
        started = false
        eventsJob?.cancel()
        eventsJob = null
    }

    private fun startEvents() {
        eventsJob?.cancel()
        val api = api ?: return
        eventsJob = viewModelScope.launch {
            var backoff = 1_000L
            while (isActive) {
                _state.update { if (it.status !is LinkStatus.Offline) it.copy(status = LinkStatus.Connecting) else it }
                try {
                    api.events().collect { windows ->
                        backoff = 1_000L
                        _state.update { it.copy(windows = windows, status = LinkStatus.Live) }
                    }
                    _state.update { it.copy(status = LinkStatus.Offline("Desktop app closed the connection")) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(status = LinkStatus.Offline(describe(e))) }
                    // A wrong token will not fix itself; wait for new settings instead of retrying.
                    if (e is ApiException && e.code == 401) return@launch
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    fun refresh() {
        val api = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                val windows = api.listWindows()
                _state.update { it.copy(windows = windows) }
                if (_state.value.status !is LinkStatus.Live) startEvents()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showMessage(describe(e))
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    fun createWindow() {
        val api = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(creating = true) }
            try {
                api.createWindow()
                showMessage("Opened a terminal")
                syncIfNotLive(api)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showMessage(describe(e))
            } finally {
                _state.update { it.copy(creating = false) }
            }
        }
    }

    fun setMoveMode(id: String, enabled: Boolean) = act(id) { setMoveMode(id, enabled) }

    fun setFullscreen(id: String, enabled: Boolean) = act(id) { setFullscreen(id, enabled) }

    fun resetPosition(id: String) = act(id, "${labelOf(id)} moved to 0,0") { resetPosition(id) }

    fun focus(id: String) = act(id) { focus(id) }

    fun closeWindow(id: String) = act(id, "Closed ${labelOf(id)}") { closeWindow(id) }

    /** Ids are UUIDs, so messages name the window by its process instead. */
    private fun labelOf(id: String): String =
        _state.value.windows.find { it.id == id }?.process ?: "terminal"

    private fun act(id: String, success: String? = null, block: suspend TerminalApi.() -> Unit) {
        val api = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = it.busy + id) }
            try {
                api.block()
                success?.let(::showMessage)
                syncIfNotLive(api)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                showMessage(describe(e))
            } finally {
                _state.update { it.copy(busy = it.busy - id) }
            }
        }
    }

    /** The WebSocket pushes changes when it is up; otherwise fetch the list once. */
    private suspend fun syncIfNotLive(api: TerminalApi) {
        if (_state.value.status is LinkStatus.Live) return
        runCatching { api.listWindows() }.onSuccess { windows -> _state.update { it.copy(windows = windows) } }
    }

    /** Saves new settings, after checking them against the desktop app. */
    suspend fun testAndSave(connection: Connection): Result<Int> {
        val probe = TerminalApi(connection)
        return try {
            val count = probe.listWindows().size
            settings.save(connection)
            Result.success(count)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(Exception(describe(e)))
        } finally {
            probe.close()
        }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }

    fun messageShown() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        api?.close()
    }

    private fun describe(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "HTTP ${e.code}"
        is java.net.ConnectException -> "Can't reach the desktop app"
        is java.net.SocketTimeoutException -> "Desktop app didn't respond"
        is java.net.UnknownHostException -> "Unknown host"
        is IOException -> e.message ?: "Network error"
        else -> e.message ?: e.javaClass.simpleName
    }
}
