# AirBridgeTouch — Device Test Plan

Test devices: Google Pixel 10a, OPPO A78 5G (both advertise `android.hardware.wifi.direct`).

**Status: NOT EXECUTED.** Every row below must be run on physical devices before any claim of verified functionality. Do not mark a test passed unless it actually ran and passed.

## Pre-conditions (both devices)

| # | Pre-condition |
|---|---|
| P1 | AirBridgeTouch debug APK installed via `adb install -r` |
| P2 | Airplane mode OFF; device Wi-Fi ON; mobile data state irrelevant |
| P3 | Location enabled (Android ≤ 12) or Nearby Wi-Fi Devices permission granted (Android 13+) |
| P4 | Both devices on the same table, within 1–5 m |
| P5 | Logcat capture running on both: `adb logcat -v time \| findstr /i "AirBridgeTouch WifiP2p"` |

## Test matrix

| # | Test | Pixel 10a | OPPO A78 5G | Expected result |
|---|---|---|---|---|
| 1 | Capability screen | Unexecuted | Unexecuted | Shows supported + Wi-Fi on + permissions granted |
| 2 | Discovery Pixel initiates | Unexecuted | Unexecuted | OPPO appears in peer list |
| 3 | Discovery OPPO initiates | Unexecuted | Unexecuted | Pixel appears in peer list |
| 4 | Connect Pixel → OPPO | Unexecuted | Unexecuted | System prompt accepted; state CONNECTED |
| 5 | Connect OPPO → Pixel | Unexecuted | Unexecuted | Same as #4 |
| 6 | Internet fully disabled (airplane + Wi-Fi on) | Unexecuted | Unexecuted | Discovery and transfer still work |
| 7 | Home router available but phones not joined | Unexecuted | Unexecuted | No dependency on router |
| 8 | Send Pixel → OPPO (small file 1 KB) | Unexecuted | Unexecuted | SUCCESS + hash verified |
| 9 | Send OPPO → Pixel (small file 1 KB) | Unexecuted | Unexecuted | SUCCESS + hash verified |
| 10 | Send 100 MB video | Unexecuted | Unexecuted | Streaming works; bounded memory; progress updates |
| 11 | Send 1 GB+ file if time permits | Unexecuted | Unexecuted | Completes without OOM; battery acceptable |
| 12 | Receiver rejects transfer | Unexecuted | Unexecuted | Sender sees rejection; no SUCCESS shown |
| 13 | Cancel mid-transfer | Unexecuted | Unexecuted | Both sides clean up; partial file not finalized |
| 14 | Toggle Wi-Fi mid-transfer | Unexecuted | Unexecuted | Transfer fails cleanly; history marks FAILED |
| 15 | Background the app mid-transfer | Unexecuted | Unexecuted | Document behavior on both OEMs |
| 16 | Repeat connect/disconnect 10× | Unexecuted | Unexecuted | No stale-group or socket leak failures |
| 17 | Ordinary Wi-Fi coexistence (both on home Wi-Fi) | Unexecuted | Unexecuted | Document per-device whether Wi-Fi stays connected during P2P |
| 18 | Discovery with VPN active | Unexecuted | Unexecuted | Document any suppression |
| 19 | Empty file (0 bytes) | Unexecuted | Unexecuted | SUCCESS; hash of empty content |
| 20 | Rejection of oversized file | Unexecuted | Unexecuted | Receiver rejects over configured limit |

## Benchmarks (after functional tests pass)

Record on both devices: file size, elapsed time, observed MB/s, and whether ordinary Wi-Fi stayed connected. Do not promise speeds in the app or README; report only measured values.

| File size | Pixel → OPPO MB/s | OPPO → Pixel MB/s | Notes |
|---|---|---|---|
| 1 MB | Unexecuted | Unexecuted | |
| 100 MB | Unexecuted | Unexecuted | |
| 1 GB | Unexecuted | Unexecuted | |
