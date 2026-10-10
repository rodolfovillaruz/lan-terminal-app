package com.lanterminal.remote.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the full window lifecycle against a real LAN Terminal desktop app. Skipped unless
 * LAN_TERMINAL_URL (e.g. http://127.0.0.1:8765) and LAN_TERMINAL_TOKEN are set.
 * It opens one extra terminal window on the desktop and closes it again.
 */
class LiveDesktopTest {
    @Test
    fun windowLifecycle() = runBlocking {
        val url = System.getenv("LAN_TERMINAL_URL")
        val token = System.getenv("LAN_TERMINAL_TOKEN")
        assumeTrue("LAN_TERMINAL_URL / LAN_TERMINAL_TOKEN not set", !url.isNullOrBlank() && !token.isNullOrBlank())

        val address = url!!.removePrefix("http://").trimEnd('/')
        val api = TerminalApi(Connection(address.substringBefore(':'), address.substringAfter(':').toInt(), token!!))
        var created: String? = null
        try {
            val before = api.listWindows().map { it.id }.toSet()
            val id = api.createWindow().also { created = it }
            assertTrue(id !in before)

            // The /events socket sends the current list as soon as it connects.
            val pushed = withTimeout(5_000) { api.events().first { list -> list.any { it.id == id } } }
            assertTrue(pushed.any { it.id == id })

            api.setMoveMode(id, true)
            assertTrue(api.listWindows().single { it.id == id }.moveMode)
            api.setMoveMode(id, false)
            assertFalse(api.listWindows().single { it.id == id }.moveMode)

            // Fullscreen is applied by the window manager, so wait for the window to report it.
            suspend fun awaitFullscreen(expected: Boolean) = withTimeoutOrNull(2_000) {
                while (api.listWindows().single { it.id == id }.fullscreen != expected) delay(100)
                true
            } == true
            api.setFullscreen(id, true)
            assertTrue("window did not go fullscreen", awaitFullscreen(true))
            api.setFullscreen(id, false)
            assertTrue("window did not leave fullscreen", awaitFullscreen(false))

            // The window manager applies the move asynchronously. Run with the screen unlocked:
            // KWin does not place windows normally while the lock screen is up.
            api.resetPosition(id)
            val moved = withTimeoutOrNull(2_000) {
                while (true) {
                    val w = api.listWindows().single { it.id == id }
                    if (w.x == 0 && w.y == 0) break
                    delay(100)
                }
                true
            }
            val at = api.listWindows().single { it.id == id }
            assertTrue("window still at ${at.x},${at.y} after reset", moved == true)

            api.focus(id)

            api.closeWindow(id)
            created = null
            // Closing is asynchronous on the desktop side; give it a moment.
            withTimeout(5_000) {
                while (api.listWindows().any { it.id == id }) delay(100)
            }
            assertEquals(before, api.listWindows().map { it.id }.toSet())

            val wrong = TerminalApi(Connection(address.substringBefore(':'), address.substringAfter(':').toInt(), "wrong"))
            try {
                wrong.listWindows()
                throw AssertionError("wrong token was accepted")
            } catch (e: ApiException) {
                assertEquals(401, e.code)
            } finally {
                wrong.close()
            }
        } finally {
            // Don't leave a stray terminal on the desktop if an assertion failed midway.
            created?.let { runCatching { api.closeWindow(it) } }
            api.close()
        }
    }
}
