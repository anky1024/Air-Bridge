# AirBridgeTouch

AirBridgeTouch is a native Android application for offline, peer-to-peer file transfer between nearby Android phones/tablets using Android Wi‑Fi Direct / Wi‑Fi P2P. It does not require internet access, a cloud server, mobile data, Bluetooth, or a router for peer discovery and transfer.

**Package:** `com.airbridge.touch`  
**Version:** `0.1.0-dev`  
**Min SDK:** Android 8.0 / API 26  
**Compile/target SDK:** API 36  
**Implementation status:** source generated and committed; not yet compiled in this chat environment; no physical-device test has been run by the assistant.

## Implemented in this branch

| Area | Status |
|---|---|
| Android Gradle project | Added Kotlin/Compose app module and Gradle configuration |
| Command-line build intent | Added `gradlew`, `gradlew.bat`, wrapper properties; binary wrapper JAR must be generated or restored before wrapper execution |
| Real Wi‑Fi Direct transport | Uses `WifiP2pManager`, channel initialization, peer discovery, peer list callbacks, connection requests, connection info, disconnect cleanup |
| Capability checks | Checks Wi‑Fi Direct feature/framework availability, Wi‑Fi enabled state, and API-specific runtime permission state |
| Runtime permissions | Requests `NEARBY_WIFI_DEVICES` on Android 13+, `ACCESS_FINE_LOCATION` on older supported Android versions, and notification permission on Android 13+ |
| Protocol framing | Versioned binary frames with magic, type, version, length, maximum frame validation, and stream-safe `readFully` handling |
| File-transfer protocol engine | Streaming send/receive flow with transfer request, acceptance/rejection, data chunks, completion ACK, cancellation cleanup hooks, byte count and SHA‑256 validation |
| Security validators | Safe filename validation, path traversal rejection, configurable size-limit validation, constant-time hash compare |
| State machine | Basic transport transition guard with tests |
| UI | Compose Material 3 home/diagnostics/nearby devices screen using real transport state; no fake peer data |
| Unit tests | Protocol and state-machine tests added, but not executed here |
| CI content | `ci-workflow.yml` is committed at repo root; move to `.github/workflows/android-ci.yml` when workflow-file write permission is available |

## Not yet verified

| Item | Status |
|---|---|
| Gradle compile | Not run in this chat environment |
| Unit tests | Source committed; not executed here |
| Lint | Not run here |
| Debug APK | Not built here |
| Pixel 10a physical test | Not run |
| OPPO A78 5G physical test | Not run |
| Cross-device transfer | Protocol engine implemented; socket integration with Android file pickers still needs end-to-end verification and hardening |
| Production readiness | Not claimed |

## How Wi‑Fi Direct is used

AirBridgeTouch uses Android public Wi‑Fi P2P APIs only:

1. Check `android.hardware.wifi.direct` and framework services.
2. Initialize `WifiP2pManager` and `WifiP2pManager.Channel`.
3. Request API-specific permissions.
4. Register Wi‑Fi P2P broadcast receiver using non-exported flags where supported.
5. Start discovery with `discoverPeers`.
6. Request peer list from framework callbacks.
7. Let the user choose a real discovered peer.
8. Connect using `WifiP2pManager.connect`.
9. Read `WifiP2pInfo` to determine actual group-owner state/address.
10. Use the socket protocol for transfer after connection establishment.

The app never silently changes the user's normal Wi‑Fi configuration and does not use root, hidden APIs, or manufacturer-specific hacks.

## Compatibility matrix

| Android version/API | Required permissions | Discovery support | Connection behavior | Foreground-service notes | Known restrictions |
|---|---|---|---|---|---|
| Android 8–12L / API 26–32 | `ACCESS_FINE_LOCATION`, Wi‑Fi state permissions | Requires Wi‑Fi Direct hardware and location services behavior allowed by OS/OEM | Framework may show system confirmation; group owner decided by framework | Foreground service may be needed for long transfers in background | Some OEMs throttle discovery/background callbacks; location denial prevents peer discovery |
| Android 13–14 / API 33–34 | `NEARBY_WIFI_DEVICES`; `POST_NOTIFICATIONS` for notifications | Nearby Wi‑Fi permission required; hardware feature still not a guarantee | Group-owner negotiation is device/OEM dependent | Notification permission affects visible transfer notification | Wi‑Fi Direct may not coexist with infrastructure Wi‑Fi on all devices |
| Android 15–16 / API 35–36 | `NEARBY_WIFI_DEVICES`; notification permission where applicable | Same as above; target SDK 36 behavior must be tested on real devices | Socket path must use actual group-owner address from `WifiP2pInfo` | Use minimal `dataSync` foreground service only when implemented for background transfers | Current branch has active-transfer service declaration removed until service is fully wired |
| Unsupported hardware | N/A | Not available | Not available | N/A | App must show unsupported state; file transfer cannot work over Wi‑Fi Direct |

## Wire protocol summary

All protocol frames are big-endian.

| Field | Size | Meaning |
|---|---:|---|
| Magic | 4 bytes | `0x41425431` (`ABT1`) |
| Type | 2 bytes | Message type code |
| Version | 2 bytes | Protocol version, currently `1` |
| Payload length | 4 bytes | Payload byte count; max 1 MiB |
| Payload | variable | Type-specific payload |

Message types include `HELLO`, `HELLO_ACK`, `TRANSFER_REQUEST`, `TRANSFER_RESPONSE`, `TRANSFER_START`, `DATA_CHUNK`, `PROGRESS_ACK`, `TRANSFER_COMPLETE`, `CANCEL`, `ERROR`, and `CLOSE`.

Security limits:

| Limit | Value |
|---|---:|
| Max frame payload | 1 MiB |
| Max data chunk | 256 KiB |
| Max filename bytes | 180 UTF‑8 bytes |
| Default max transfer size | 2 GiB |
| Hash | SHA‑256 |

A transfer is successful only after expected byte count is received, SHA‑256 matches, output finalization succeeds, and the receiver sends a completion ACK.

## Security assumptions and limitations

Wi‑Fi Direct link setup provides local wireless link protection, but local peer-to-peer networks are still treated as untrusted. AirBridgeTouch validates protocol framing, filenames, file sizes, byte counts, and final SHA‑256 hashes. Incoming transfers require explicit approval in the protocol design.

This branch does **not** claim application-layer end-to-end encryption. If stronger peer authentication/encryption is required, use a reviewed construction such as TLS 1.3 with certificate pinning/TOFU or Noise Protocol Framework via a maintained library. Do not invent cryptography.

## Windows command-line build setup

1. Install JDK 17, for example Eclipse Temurin 17.
2. Set `JAVA_HOME`, for example:

```powershell
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17"
setx PATH "%PATH%;%JAVA_HOME%\bin"
```

3. Install Android SDK command-line tools from Android Developers.
4. Set SDK variables:

```powershell
setx ANDROID_HOME "C:\Android\Sdk"
setx ANDROID_SDK_ROOT "C:\Android\Sdk"
setx PATH "%PATH%;%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools"
```

5. Install SDK packages:

```powershell
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

6. Ensure Gradle wrapper JAR exists. If `gradle/wrapper/gradle-wrapper.jar` is missing, install Gradle once and run:

```powershell
gradle wrapper --gradle-version 8.13
```

7. Build and test:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lint
.\gradlew.bat assembleDebug
```

8. Install on each phone:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

9. Collect diagnostics:

```powershell
adb logcat -c
adb logcat -v time | findstr /i "AirBridgeTouch WifiP2p WifiDirect"
```

## Physical-device test plan

| Test | Pixel 10a | OPPO A78 5G | Expected result |
|---|---|---|---|
| Capability screen | Unexecuted | Unexecuted | Shows supported only if framework and permission checks pass |
| Discovery Pixel → OPPO | Unexecuted | Unexecuted | OPPO appears as real Wi‑Fi Direct peer |
| Discovery OPPO → Pixel | Unexecuted | Unexecuted | Pixel appears as real Wi‑Fi Direct peer |
| Connection Pixel initiates | Unexecuted | Unexecuted | System connection flow completes or reports rejection/timeout |
| Connection OPPO initiates | Unexecuted | Unexecuted | Same as above |
| Internet disabled | Unexecuted | Unexecuted | Discovery/connection still works if Wi‑Fi Direct supported |
| Router disconnected | Unexecuted | Unexecuted | Transfer does not require router |
| Rejection | Unexecuted | Unexecuted | Sender sees rejection; no success shown |
| Large file | Unexecuted | Unexecuted | Bounded memory, progress updates, hash verified |
| Interruption | Unexecuted | Unexecuted | Transfer fails cleanly; no resume is claimed |

## Pixel and OPPO troubleshooting

| Symptom | Action |
|---|---|
| No peers found | Keep both phones unlocked, enable Wi‑Fi, grant Nearby Wi‑Fi/Location permission, turn off VPN/hotspot, retry discovery on both devices |
| OPPO does not appear | Check OEM battery/network restrictions; keep app foreground; retry after toggling Wi‑Fi |
| Pixel discovers but connection fails | Accept any system Wi‑Fi Direct prompt; retry after `Disconnect`; reboot Wi‑Fi stack if stale group remains |
| Ordinary Wi‑Fi drops | Some devices cannot maintain infrastructure Wi‑Fi and Wi‑Fi Direct simultaneously; this is OEM/device behavior |
| Transfer interrupted in background | Keep app foreground until foreground service path is fully wired and tested |

## Release preparation

| Step | Status |
|---|---|
| Generate/restore official Gradle wrapper JAR | Required before wrapper-based builds |
| Move `ci-workflow.yml` to `.github/workflows/android-ci.yml` | Required for GitHub Actions |
| Run `testDebugUnitTest` | Required |
| Run `lint` | Required |
| Build debug APK | Required |
| Physical-device transfer tests | Required |
| Add release signing config using secure local/CI secrets | Required; never commit keystore/passwords |
| Build signed AAB | Required for Play release |
| Privacy/security review | Required |

Release signing requires a securely managed private key. No keystore, password, API secret, or private credential is included in this repository.
