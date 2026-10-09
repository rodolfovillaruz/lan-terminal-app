package com.lanterminal.remote.data

/** Where the desktop app's LAN API lives, plus the token from its config.json. */
data class Connection(
    val host: String,
    val port: Int,
    val token: String,
) {
    val baseUrl: String get() = "http://$host:$port"
    val isComplete: Boolean get() = host.isNotBlank() && token.isNotBlank() && port in 1..65535
}

/** One terminal window, as returned by GET /windows and pushed on /events. */
data class TermWindow(
    val id: String,
    /** The command running in the foreground (bash, vim, ...); not unique. */
    val process: String,
    val title: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val moveMode: Boolean,
    val focused: Boolean,
    val minimized: Boolean,
)

class ApiException(val code: Int, message: String) : Exception(message)
