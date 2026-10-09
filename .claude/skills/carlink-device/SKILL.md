---
name: carlink-device
description: Test CarLink connections on the user's real tablet/head unit and diagnose them from the logs of CarLink, DiAuto (Android Auto) and DiPlay (CarPlay), including connection timing. Use this whenever a phone does not connect, connects then drops, connects slowly, picks the wrong Wi-Fi path, or before claiming that any connection change works — "iPhone won't connect wirelessly", "Android Auto stuck on connecting", "why is wireless CarPlay slow", "test it on the tablet", "why did it disconnect". The emulator cannot test connections (no Bluetooth, no real Wi-Fi); never call a connection fix done without this skill's report from a real device.
---

# CarLink on a real device

The emulator only proves screens. Bluetooth, Wi-Fi Direct, hotspots, Same Wi-Fi, USB CarPlay and
USB Android Auto only exist on real hardware, so every connection change is verified here.

## Target

The user's tablet `192.168.10.103:5555` (Redmi Pad Pro 5G, Android 16, ~1458x911 dp landscape,
281 dpi) over wireless adb. Full use is allowed when the user has not said otherwise in the
current conversation (install, launch, start connections, read logs); never change the tablet's
system settings. The phones (iPhone 13, Samsung Galaxy S25 Ultra) are in the user's hands and not
on adb: tell the user exactly when a phone must be unlocked, nearby or plugged in, and what to
tap, before a test.

## Loop

```sh
S=.claude/skills/carlink-dev/scripts/dev.sh
$S build && CARLINK_TARGET=tablet $S install      # install + launch on the tablet
# the shrunk release build instead (package com.carlink.app, not debuggable; see carlink-dev):
$S build-release && CARLINK_TARGET=tablet $S install-release
# ask the user to connect / unlock the phone, then:
python3 .claude/skills/carlink-device/scripts/report.py --since HH:MM   # device local time
python3 .claude/skills/carlink-device/scripts/report.py --since HH:MM --all   # every engine line
python3 .claude/skills/carlink-device/scripts/report.py --package com.carlink.app --since HH:MM   # release build (logcat only)
```

`report.py` is read-only. It merges CarLink's logcat lines and its file log
(`files/logs/carlink.log`), DiAuto's journal (`files/diagnostics/current.log`) and DiPlay's log
(`files/logs/diplay.log`, older runs in `previous*.log`) into one timeline in device time, then
prints one verdict per attempt (engine, path, connected/failed/blocked/dropped, time from start
and from plug-in, cause). The timing lines below are part of the timeline. Logcat rotates within
minutes on a busy device; the three files keep older history.

## Timing

Every engine writes always-on INFO timing lines, in logcat and in its file:

| Line | Engine, file | Counts from |
|---|---|---|
| `WIRED_TIMING <milestone> plug+<ms>ms session+<ms>ms` | DiPlay, `diplay.log` | the iPhone found on USB (`plug`); CarPlay StartSession (`session`, `n/a` before it) |
| `WIRELESS_TIMING <phase> start+<ms>ms session+<ms>ms [detail]` | DiPlay, `diplay.log` | the controller start or the current bring-up (`start`); CarPlay StartSession (`session`) |
| `WIRELESS_TIMING <phase> start+<ms>ms [detail]` | DiAuto, `current.log` | `begin` (CarLink's wireless start or poke) |

CarLink's `carlink.log` adds `SessionManager` decisions ("start …", "… starting on WIFI_DIRECT",
"… connected on …", "… failed: …"), "settings written in N ms" and "Android Auto engine process
warmed in N ms". To measure: note the device time, have the user plug in or tap Connect, then
read `first-frame` against the first line of the attempt.

```sh
adb -s 192.168.10.103:5555 shell run-as com.carlink.app.hudtest grep -h TIMING files/logs/diplay.log files/diagnostics/current.log | tail -40
```

Measured on this tablet (2026-10-09, plug-in or Connect to the first picture):

| Connection | Time | Where it goes |
|---|---|---|
| CarPlay over USB | ≈ 3.4 s | `WIRED_TIMING`; NCM bulk-out readiness was the old bottleneck (see VENDOR_CHANGES) |
| Android Auto over USB | ≈ 3.8 s | accessory switch and re-enumeration; a cold `:aa` process adds ~4.5 s (Connection.prewarm) |
| Android Auto, wireless (Wi-Fi Direct) | ≈ 5 s | group create, poke, phone joins, TCP 5288 |
| CarPlay, wireless (Wi-Fi Direct) | ≈ 16-20 s | mostly the RFCOMM dial: paging an idle iPhone over Bluetooth (6.6 s per dial, retried on no answer); 0.2 s when the iPhone's Bluetooth was already connected |

## Reading the evidence

| Signal | Meaning |
|---|---|
| `CarLink-Session: start <ENGINE> <TRANSPORT>` / `… starting on <PATH> (attempt n)` | `SessionManager` started a session and the wireless path it chose |
| `… blocked on <PATH>: [PROBLEMS]` | pre-flight stopped it before any engine opened (Bluetooth/Wi-Fi/Location off, permissions, details missing) |
| `… failed (<REASON>); trying <PATH>` | a path-related failure moved to the next wireless path |
| `USB re-attach during the … session; left to the engine` | normal engine re-enumeration, not a new session |
| AA `[RX] WifiConnectStatus status=-3` ~0.1 s after credentials | the phone refused the network it was handed (missing/wrong password) |
| AA `WiFi read timeout (15000ms)` after video started | the Wi-Fi link stalled |
| AA `isUserExit=true` | someone used Back/exit in the projection, not a failure |
| AA `sessions in a row ended without a single video frame` | link too weak or path wrong |
| CarPlay `tcpAccepted=0 … waitingFor=WiFi_discovery_or_AirPlay_TCP` then `No AirPlay TCP` | iPhone authenticated over Bluetooth but never reached the receiver over Wi-Fi (Bonjour/multicast blocked on that network) |
| CarPlay `read failed, socket might closed or timeout` | the iPhone did not answer the RFCOMM dial; retried after 1.5 s up to 8 times |
| CarPlay `Video: first frame rendered` / `WIRED_TIMING first-frame` / `WIRELESS_TIMING first-frame` | picture is up |
| `CarLink-Session: stall: … ; <ACTION>` | the stall watchdog acted on a phase past its deadline (`StallPlaybook`) |
| `… failed: BLUETOOTH (iPhone not reachable over Bluetooth: …)` | two RFCOMM dials in a row got no answer (capped at about 13 s) |
| AA `stale accessory re-switch result=…` / `USB session ended before any frame; sending the accessory switch again` | a phone left in accessory mode by an earlier session was switched again (K2); `unplug the phone and plug it in again` within 5 s if it ignored it |
| AA `USB-only session; wireless bring-up skipped.` | a USB Android Auto session armed no P2P group or poke (K10) |

## Rules

- State what was verified on the device and what was not. A connection change is "done" only
  with a report showing it connect and stay up (≥ 1 minute) on the tablet.
- Never print or log Wi-Fi passwords; the report masks MAC addresses.
- Proven paths on this tablet (2026-10-09): **CarPlay over USB**, **Android Auto over USB**,
  **Android Auto over Wi-Fi Direct** and **CarPlay over Wi-Fi Direct** (the defaults), and Android
  Auto over Same Wi-Fi. CarPlay over Same Wi-Fi fails on the user's router (no Bonjour between
  clients). The car-hotspot path has not been tested (the tablet has no car hotspot). Update this
  list as tests prove or disprove paths.
