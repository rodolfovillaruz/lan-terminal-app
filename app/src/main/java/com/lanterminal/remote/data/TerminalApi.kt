package com.lanterminal.remote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Client for the desktop app's REST API and its /events WebSocket. */
class TerminalApi(private val connection: Connection) {

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    suspend fun listWindows(): List<TermWindow> =
        call(request("/windows").get().build()) { parseWindows(JSONArray(it)) }

    suspend fun createWindow(): Int =
        call(post("/windows")) { JSONObject(it).getInt("id") }

    suspend fun setMoveMode(id: Int, enabled: Boolean) {
        call(post("/windows/$id/move-mode", JSONObject().put("enabled", enabled))) {}
    }

    suspend fun resetPosition(id: Int) {
        call(post("/windows/$id/reset-position")) {}
    }

    suspend fun focus(id: Int) {
        call(post("/windows/$id/focus")) {}
    }

    suspend fun closeWindow(id: Int) {
        call(request("/windows/$id").delete().build()) {}
    }

    /**
     * Emits the full window list every time it changes on the desktop. Completes when the
     * server closes the socket and fails with the cause when the connection drops.
     */
    fun events(): Flow<List<TermWindow>> = callbackFlow {
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (msg.optString("type") == "windows") {
                    trySend(parseWindows(msg.getJSONArray("windows")))
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(if (response?.code == 401) unauthorized() else t)
            }
        }
        val socket = client.newWebSocket(
            Request.Builder()
                .url("ws://${connection.host}:${connection.port}/events")
                .header("Authorization", "Bearer ${connection.token}")
                .build(),
            listener,
        )
        awaitClose { socket.cancel() }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // Detects a desktop that went away (sleep, Wi-Fi drop) instead of waiting forever.
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private fun request(path: String) = Request.Builder()
        .url(connection.baseUrl + path)
        .header("Authorization", "Bearer ${connection.token}")

    private fun post(path: String, body: JSONObject = JSONObject()) =
        request(path).post(body.toString().toRequestBody(JSON)).build()

    private suspend fun <T> call(request: Request, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) throw unauthorized()
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    throw ApiException(response.code, error?.ifBlank { null } ?: "HTTP ${response.code}")
                }
                parse(body)
            }
        }

    private fun unauthorized() = ApiException(401, "Wrong token")

    private companion object {
        val JSON = "application/json".toMediaType()

        fun parseWindows(array: JSONArray): List<TermWindow> = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TermWindow(
                id = o.getInt("id"),
                title = o.optString("title"),
                x = o.optInt("x"),
                y = o.optInt("y"),
                width = o.optInt("width"),
                height = o.optInt("height"),
                moveMode = o.optBoolean("moveMode"),
                focused = o.optBoolean("focused"),
                minimized = o.optBoolean("minimized"),
            )
        }
    }
}
