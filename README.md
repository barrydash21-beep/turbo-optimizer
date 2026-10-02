# Turbo Optimizer: Android System Performance Tuning Utility

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg)](https://developer.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![UI: Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)
[![Framework: Shizuku](https://img.shields.io/badge/Framework-Shizuku%20API-orange.svg)](https://shizuku.rikka.app/)

**Turbo Optimizer** is an open-source Android device resource management and system performance tuning utility built with Kotlin and Jetpack Compose. It demonstrates how to orchestrate device-level system configurations—such as display refresh rate locks, window animation scaling, process memory management, and local network packet prioritization—to maximize hardware efficiency during intensive foreground application sessions.

Designed with an educational and architectural focus, this project serves as a reference implementation for Android developers interested in non-root system administration via [Shizuku](https://shizuku.rikka.app/), local loopback network filtering using Android's native `VpnService`, and real-time hardware telemetry parsing (`SurfaceFlinger` and `thermalservice`).

---

## Technical Scope: System Optimization vs. Application Tampering

This project operates strictly at the host Android operating system level. To comply with platform security standards and GitHub Acceptable Use Policies, the boundaries of this utility are clearly defined below:

| What Turbo Optimizer Does | What Turbo Optimizer DOES NOT Do |
|---|---|
| Adjusts standard Android system settings (animation scales, display refresh rates). | ❌ Does NOT modify, inject code into, or inspect third-party app binaries or memory. |
| Utilizes Android's native `cmd game mode` power management hints. | ❌ Does NOT bypass security controls, DRM, or anti-cheat engines. |
| Manages background process caches via standard Android commands (`am force-stop`). | ❌ Does NOT provide cheats, exploits, or unfair advantages. |
| Employs a local loopback `VpnService` to restrict background network sync. | ❌ Does NOT route traffic through remote servers or proxy tunnels. |
| Creates a system state snapshot and automatically restores settings upon exit. | ❌ Does NOT interact with or tamper with third-party application servers. |
| Monitors real-time device thermal status and display frame statistics. | ❌ Does NOT disable hardware thermal throttling or safety cutoffs. |

---

## Core Architectural Pillars

### 1. Shizuku as a System Administration Framework
Traditional Android system utilities often require root access (`su`), which can compromise the device security model. Turbo Optimizer integrates with **[Shizuku](https://shizuku.rikka.app/)**, a widely recognized open-source system management framework utilized by modern Android productivity applications (such as *App Ops*, *Hail*, and *Dhizuku*).

Shizuku provides a secure binder IPC bridge that allows apps to execute standard Android Debug Bridge (ADB) commands directly on the user's behalf after explicit user authorization. Turbo Optimizer uses Shizuku exclusively to interact with official Android system services (e.g., `cmd game mode`, `settings put system`, and `dumpsys`).

### 2. Local Loopback Network Optimization
Intensive foreground applications (such as real-time multimedia, rendering, or streaming) are highly sensitive to network latency jitter caused by unthrottled background application synchronization. 

Turbo Optimizer implements Android's native `VpnService` API strictly as an **on-device local loopback filter**:
- **Zero Remote Servers**: No traffic is transmitted to external servers, cloud proxies, or third-party endpoints. All packet processing occurs entirely within the local device memory.
- **Direct Foreground Traffic**: The target foreground application and essential system services (such as Google Play Services) are explicitly excluded from the VPN tunnel, allowing their network packets to communicate directly with the internet without any routing overhead or latency penalty.
- **Background Noise Suppression**: Background applications routed through the local virtual interface have non-critical sync packets filtered at the local TUN layer during active sessions, preventing bufferbloat and latency spikes.

### 3. State Preservation & Hardware Safety
- **State Snapshot & Auto-Restore**: Before applying any system modification, Turbo Optimizer captures a comprehensive snapshot of the device's active configuration. When the selected foreground session ends, an automated background monitor cleanly restores all original user preferences.
- **Strict Hardware Safety**: Device thermal safety limits are strictly respected. Turbo Optimizer **never** modifies thermal throttling thresholds or kernel governors, ensuring the device remains within safe operating temperatures.

---

## Optimization Profiles & System Adjustments

| Optimization Step | System Command / Mechanism | Profiles Applied |
|---|---|---|
| **Background Memory Optimization** | Reclaims cached RAM via standard `am force-stop` for non-essential third-party apps. | All Profiles |
| **System Performance Mode** | Applies Android's native power mode (`cmd game mode`) on supported Android 12+ devices. | All Profiles |
| **Window Animation Scaling** | Sets window and transition animation duration scales to `0.0` for immediate UI response. | Balanced, Turbo |
| **Display Refresh Rate Lock** | Locks display refresh rate to hardware-supported limits (60/90/120 Hz) via system display settings. | All Profiles |
| **Distraction & Sync Management** | Toggles Priority Do Not Disturb and pauses master sync (`ContentResolver.setMasterSyncAutomatically`). | Turbo Profile |
| **Background Traffic Reduction** | Activates the local loopback `VpnService` to pause background packet jitter. | Opt-in |
| **Session Diagnostics** | Parses `dumpsys SurfaceFlinger` (frame latency) and `dumpsys thermalservice` (thermal status). | Active Monitoring |

---

## Permissions Breakdown

Every permission declared in Turbo Optimizer serves a specific, transparent functional purpose:

| Permission | Technical Justification |
|---|---|
| `moe.shizuku.manager.permission.API_V23` | Communicates with the Shizuku system service for ADB-level system adjustments without root. |
| `BIND_VPN_SERVICE` | Required to create the local on-device network loopback interface for background packet filtering. |
| `INTERNET` | Used solely for device latency ping benchmarking against standard DNS endpoints (e.g., Cloudflare/Google DNS). |
| `WRITE_SYNC_SETTINGS` | Temporarily pauses and restores account auto-synchronization during intensive sessions. |
| `POST_NOTIFICATIONS` | Displays persistent foreground service notifications to inform the user that optimization is active. |
| `FOREGROUND_SERVICE` | Keeps the session monitor and local loopback service active while the target task runs in the foreground. |

> **Privacy Notice**: Turbo Optimizer does not collect, log, or transmit any personal data, device identifiers, or analytics. Package queries are strictly constrained to applications with launcher intents to populate the app selector.

---

## Educational Value for Android Developers

This repository serves as an educational reference architecture for modern Android engineering practices:
- **Modern UI Architecture**: Built entirely with declarative **Jetpack Compose** and Material 3 components.
- **Asynchronous Flow Management**: Leverages **Kotlin Coroutines** and `StateFlow` for reactive UI updates and thread-safe background processing.
- **IPC Architecture**: Demonstrates IPC patterns via Android Binder and Shizuku AIDL interfaces.
- **Network Stack Fundamentals**: Illustrates packet manipulation and TUN interface management using Android's low-level `VpnService` API.
- **Telemetry Processing**: Demonstrates how to parse Android OS diagnostic outputs (`dumpsys`) safely to produce real-time performance graphs.

---

## Requirements

- **Operating System**: Android 8.0 (API level 26) or higher.
  - *Note*: Android Game Mode features require Android 12 (API level 31) or higher.
- **System Administration**: [Shizuku](https://shizuku.rikka.app/) installed and activated (via Wireless Debugging or computer ADB).

---

## Building from Source

### Prerequisites
- Android Studio Ladybug (or newer) / IntelliJ IDEA
- Android SDK (API 35, Build Tools 35.0.0)
- Java Development Kit (JDK) 17 or 21

### Compilation Commands
```bash
# Clone the repository
git clone https://github.com/barrydash21-beep/turbo-optimizer.git
cd turbo-optimizer

# Run unit and integration tests
./gradlew testDebugUnitTest

# Assemble the debug APK
./gradlew assembleDebug
```
The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Release Builds
Release signing configurations are loaded securely from your global Gradle properties and are **never committed** to version control. To build a signed release APK, define the following variables in `~/.gradle/gradle.properties`:

```properties
TURBOBOOST_STORE_FILE=/path/to/your-keystore.jks
TURBOBOOST_STORE_PASSWORD=your_keystore_password
TURBOBOOST_KEY_ALIAS=your_key_alias
TURBOBOOST_KEY_PASSWORD=your_key_password
```

Then execute:
```bash
./gradlew assembleRelease
```

---

## Ethics, Platform Compliance & Disclaimer

- **Acceptable Use**: This software is intended exclusively for educational research, device performance analysis, and legitimate system-level resource optimization.
- **Platform Integrity**: This project does not circumvent platform security controls, breach terms of service of third-party software, or grant unauthorized access to external systems.
- **Trademark Disclaimer**: All product names, logos, and brands mentioned are property of their respective owners. Mention of any third-party frameworks or Android APIs is solely for technical identification and interoperability purposes and does not imply endorsement or affiliation.

---

## License

This project is licensed under the [MIT License](LICENSE) — see the [LICENSE](LICENSE) file for complete details.

```
Copyright (c) 2025-2026 Cee Jay
```
