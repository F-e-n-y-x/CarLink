---
name: carlink-device
description: Test CarLink connections on the user's real tablet/head unit and diagnose them from the logs of CarLink, DiAuto (Android Auto) and DiPlay (CarPlay). Use this whenever a phone does not connect, connects then drops, connects slowly, picks the wrong Wi-Fi path, or before claiming that any connection change works — "iPhone won't connect wirelessly", "Android Auto stuck on connecting", "test it on the tablet", "why did it disconnect". The emulator cannot test connections (no Bluetooth, no real Wi-Fi); never call a connection fix done without this skill's report from a real device.
---

# CarLink on a real device

The emulator only proves screens. Bluetooth, Wi-Fi Direct, hotspots, Same Wi-Fi, USB CarPlay and
USB Android Auto only exist on real hardware, so every connection change is verified here.

## Target

The user's tablet `192.168.10.103:5555` (Redmi Pad Pro 5G, Android 16, ~1458x911 dp landscape,
281 dpi) over wireless adb. Full use is allowed (install, launch, start connections, read logs).
The phones (iPhone, Samsung S25 Ultra) are in the user's hands and not on adb: tell the user
exactly when a phone must be unlocked/nearby or plugged in, and what to tap, before a test.

## Loop

```sh
S=.claude/skills/carlink-dev/scripts/dev.sh
$S build && CARLINK_TARGET=tablet $S install      # install + launch on the tablet
# ask the user to connect / unlock the phone, then:
python3 .claude/skills/carlink-device/scripts/report.py --since HH:MM   # device local time
python3 .claude/skills/carlink-device/scripts/report.py --since HH:MM --all   # every engine line
```

`report.py` is read-only. It merges CarLink's logcat lines, DiAuto's journal
(`files/diagnostics/current.log`) and DiPlay's log (`files/logs/diplay.log`, older runs in
`previous*.log`) into one timeline in device time, then prints one verdict per attempt (path,
connected/failed/dropped, cause). Logcat rotates within minutes on a busy device; the two engine
files keep older history.

## Reading the evidence

| Signal | Meaning |
|---|---|
| `session engine=… path=…` | CarLink's chosen wireless path and why (password saved, open, refused, hotspot) |
| AA `[RX] WifiConnectStatus status=-3` ~0.1 s after credentials | the phone refused the network it was handed (missing/wrong password) |
| AA `WiFi read timeout (15000ms)` after video started | the Wi-Fi link stalled |
| AA `isUserExit=true` | someone used Back/exit in the projection, not a failure |
| AA `sessions in a row ended without a single video frame` | link too weak or path wrong |
| CarPlay `tcpAccepted=0 … waitingFor=WiFi_discovery_or_AirPlay_TCP` then `No AirPlay TCP` | iPhone authenticated over Bluetooth but never reached the receiver over Wi-Fi (Bonjour/multicast blocked on that network) |
| CarPlay `Video: first frame rendered` | picture is up |

## Rules

- State what was verified on the device and what was not. A connection change is "done" only
  with a report showing it connect and stay up (≥ 1 minute) on the tablet.
- Never print or log Wi-Fi passwords; the report masks MAC addresses.
- Proven paths on this tablet (2026-10-09): Android Auto over Wi-Fi Direct and Same Wi-Fi;
  CarPlay over Wi-Fi Direct. CarPlay over Same Wi-Fi fails on the user's router (no Bonjour).
  Update this list as tests prove or disprove paths.
