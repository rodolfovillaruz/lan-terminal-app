# LAN Terminal Remote (Android)

Android remote for the [LAN Terminal](../browser-ssh-client-electron) desktop app. From your phone you can:

- see every terminal window that's open on the desktop, updated live
- open a new terminal window
- turn **move mode** on or off for a window, so it can be dragged with the mouse
- move a window back to **x=0, y=0**
- bring a window to the front
- close a window, after a confirmation

It's built with Kotlin, Jetpack Compose (Material 3) and OkHttp. It needs Android 10 (API 29) or later.

## Connecting

1. Start the desktop app (`npm start`).
2. Right-click its tray icon:
   - The **API: http://…** line shows the address to use.
   - **Copy API token** copies the token.

   The token is also stored in `~/.config/lan-terminal/config.json`.
3. In the app, enter the IP address, the port (default 8765) and the token, then tap **Connect**. You can paste the full `http://192.168.x.x:8765` address into the IP field; the app splits out the port. The app checks the connection before saving the settings.

While the app is open, it stays connected to the desktop's `/events` WebSocket, so the window list updates as soon as anything changes. When the app is in the background the connection is closed. If the connection drops, the app reconnects with backoff, waiting longer between tries, up to 15 seconds apart. If the token is wrong it stops retrying and shows "Wrong token" instead.

The API uses plain HTTP, so cleartext traffic is allowed (`res/xml/network_security_config.xml`). Only use it on a network you trust.

## Build

You need JDK 21 and the Android SDK (compileSdk 37).

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # install on a connected phone (USB debugging on)
```

## Tests

```sh
./gradlew testDebugUnitTest
```

- `TerminalApiTest` checks the API client against OkHttp's MockWebServer. It covers the endpoints, the auth header, error handling and WebSocket pushes.
- `LiveDesktopTest` goes through a full window lifecycle against a real running desktop app: create, live update, move mode, reset to 0,0, focus, close, and rejection of a wrong token. It's skipped unless you point it at a desktop app:

  ```sh
  LAN_TERMINAL_URL=http://127.0.0.1:8765 \
  LAN_TERMINAL_TOKEN=$(jq -r .token ~/.config/lan-terminal/config.json) \
  ./gradlew testDebugUnitTest
  ```

  It briefly opens one extra terminal window on the desktop. Run it with the screen unlocked; KWin doesn't place windows normally while the lock screen is up.

## Layout

| Path | What |
|---|---|
| `data/TerminalApi.kt` | REST calls and the `/events` WebSocket |
| `data/SettingsStore.kt` | Saved host, port and token (DataStore) |
| `ui/MainViewModel.kt` | Connection lifecycle, reconnects, window actions |
| `ui/WindowsScreen.kt` | Window list with per-window controls |
| `ui/SettingsScreen.kt` | Connection settings |
