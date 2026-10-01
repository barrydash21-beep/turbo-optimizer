# TurboBoost

TurboBoost is an Android utility that prepares the device before you start a game. It closes background apps, sets the display refresh rate, applies Android's game mode and, if you turn it on, blocks other apps' network traffic. It puts your previous settings back when the game closes.

You choose which apps count as games. The app has no built-in game list.

It works without root. Device-level settings are changed through [Shizuku](https://shizuku.rikka.app/), which runs commands with the same permissions as ADB.

## What a boost does

Every change is made to device settings only. Before the first change, your current settings are saved, and Restore writes those saved values back.

| Step | What it changes | Profiles |
|---|---|---|
| Close background apps | Force-stops running third-party apps (`am force-stop`), except this app, the selected game, Shizuku, your launcher, keyboard, SMS app and dialer | All |
| Free cached memory | `sync` then a page-cache drop. Only works with root-mode Shizuku; skipped otherwise | Balanced, Turbo |
| Game mode | Android's own `cmd game mode` (battery, standard or performance) for the selected game | All |
| Animations | Sets window and transition animation scales to 0 | Balanced, Turbo |
| Do Not Disturb and auto-sync | Priority-only Do Not Disturb and pauses master auto-sync | Turbo |
| Refresh rate | Holds the display at the rate you picked (60/90/120 Hz, limited to what the panel supports) and enables fixed performance mode | All |
| Traffic blocker (optional) | A local VPN that drops traffic from every app except your games, Shizuku and Google Play services. Your games are excluded from the tunnel, so their traffic never passes through it. DNS for blocked apps is set to 1.1.1.1 | Opt-in |

While the game runs, a monitor service checks whether it is still open. It also reads the frame timing Android reports for the game's window (`dumpsys SurfaceFlinger`) and the device thermal status (`dumpsys thermalservice`). When the game closes, it restores your settings and shows a short session summary.

TurboBoost never turns off thermal throttling. It does not change other apps' files, memory, processes or settings, apart from force-stopping them, which Android lets the user do from Settings.

## Permissions

| Permission | Why |
|---|---|
| Shizuku (`moe.shizuku.manager.permission.API_V23`) | Runs the commands above at ADB level, without root |
| `INTERACT_ACROSS_USERS_FULL` | Declared only to protect the Shizuku provider. It is a signature permission, so the app is never granted it |
| `INTERNET` | Needed for the local VPN and for the latency reading on the dashboard |
| `BIND_VPN_SERVICE` (service) | The optional traffic blocker. Android asks for your consent the first time |
| `WRITE_SYNC_SETTINGS` | Pauses and restores master auto-sync (Turbo profile only) |
| `POST_NOTIFICATIONS` | Notifications for the running monitor and traffic blocker |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Keeps the monitor and traffic blocker alive while you are in the game |

Package visibility is limited to apps with a launcher icon (for the game picker) and a few named packages (Shizuku, Google Play services and the apps the memory step targets). The app does not request `QUERY_ALL_PACKAGES`.

## Requirements

- Android 8.0 (API 26) or newer. Game mode needs Android 12 or newer and is skipped on older versions.
- Shizuku installed and running. See the [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/).

## Install

1. Download `TurboBoost-v1.0.0.apk` and `TurboBoost-v1.0.0.apk.sha256` from the [Releases](../../releases) page.
2. Check the file before installing (see below).
3. Open the APK on your phone and allow installs from that source when asked.
4. Open TurboBoost, grant Shizuku access, tap **+ ADD GAME** and pick your game.

### Verify the download

The SHA-256 of the APK must match the `.sha256` file and the value in the release notes.

```sh
# Linux / macOS / Git Bash
sha256sum -c TurboBoost-v1.0.0.apk.sha256
```

```powershell
# Windows PowerShell
(Get-FileHash TurboBoost-v1.0.0.apk -Algorithm SHA256).Hash
```

To confirm the signing certificate, run `apksigner verify --print-certs TurboBoost-v1.0.0.apk` and compare its SHA-256 digest with the one in the release notes.

## Build from source

Requires the Android SDK (API 35) and JDK 17 or 21. Android Studio's bundled JDK works.

```sh
./gradlew testDebugUnitTest   # unit tests
./gradlew assembleDebug       # debug APK
```

A signed release build reads its keystore from Gradle properties, never from the repository. Add these to `~/.gradle/gradle.properties`:

```properties
TURBOBOOST_STORE_FILE=/path/to/your-release.jks
TURBOBOOST_STORE_PASSWORD=...
TURBOBOOST_KEY_ALIAS=...
TURBOBOOST_KEY_PASSWORD=...
```

Then run `./gradlew assembleRelease`. Without these properties the release APK is built unsigned.

## Disclaimer

This is an independent, unofficial device utility. It is not affiliated with, endorsed by, or sponsored by Activision, Tencent/TiMi, or any game publisher. It does not modify, inject into, read, or alter any game files, game memory, or network traffic, and it provides no gameplay advantage. All trademarks belong to their respective owners. Use at your own risk.

## License

[MIT](LICENSE)
