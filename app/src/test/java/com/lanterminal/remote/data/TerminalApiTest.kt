package com.lanterminal.remote.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class TerminalApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TerminalApi

    private val windowJson = """
        {"id":1,"title":"rodolfo@OMEN:~","x":0,"y":0,"width":2560,"height":1394,
         "moveMode":false,"focused":true,"minimized":false,"pid":42}
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = TerminalApi(Connection(server.hostName, server.port, "secret"))
    }

    @After
    fun tearDown() {
        api.close()
        server.shutdown()
    }

    @Test
    fun listWindowsSendsTokenAndParsesWindows() = runBlocking {
        server.enqueue(MockResponse().setBody("[$windowJson]"))

        val windows = api.listWindows()

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/windows", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals(
            listOf(TermWindow(1, "rodolfo@OMEN:~", 0, 0, 2560, 1394, moveMode = false, focused = true, minimized = false)),
            windows,
        )
    }

    @Test
    fun createWindowReturnsNewId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":7}"""))

        assertEquals(7, api.createWindow())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/windows", request.path)
    }

    @Test
    fun windowActionsHitTheRightEndpoints() = runBlocking {
        repeat(4) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }

        api.setMoveMode(3, true)
        api.resetPosition(3)
        api.focus(3)
        api.closeWindow(3)

        val move = server.takeRequest()
        assertEquals("POST /windows/3/move-mode", "${move.method} ${move.path}")
        assertTrue(JSONObject(move.body.readUtf8()).getBoolean("enabled"))
        server.takeRequest().let { assertEquals("POST /windows/3/reset-position", "${it.method} ${it.path}") }
        server.takeRequest().let { assertEquals("POST /windows/3/focus", "${it.method} ${it.path}") }
        server.takeRequest().let { assertEquals("DELETE /windows/3", "${it.method} ${it.path}") }
    }

    @Test
    fun wrongTokenBecomesApiException() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        try {
            api.listWindows()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
            assertEquals("Wrong token", e.message)
        }
    }

    @Test
    fun serverErrorMessageIsSurfaced() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"no such window"}"""))
        try {
            api.focus(99)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(404, e.code)
            assertEquals("no such window", e.message)
        }
    }

    @Test
    fun eventsEmitPushedWindowLists() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"windows","windows":[$windowJson]}""")
            }
        }))

        val windows = withTimeout(5_000) { api.events().first() }

        assertEquals(1, windows.single().id)
        val request = server.takeRequest()
        assertEquals("/events", request.path)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
    }

    @Test
    fun eventsFailWithWrongToken() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            withTimeout(5_000) { api.events().first() }
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(401, e.code)
        }
    }

    @Test
    fun connectionCompleteness() {
        assertTrue(Connection("10.0.0.2", 8765, "t").isComplete)
        assertFalse(Connection("", 8765, "t").isComplete)
        assertFalse(Connection("10.0.0.2", 8765, "").isComplete)
        assertFalse(Connection("10.0.0.2", 0, "t").isComplete)
    }
}
