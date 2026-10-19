# CarLink architecture

How CarLink is put together: the modules, the processes, how a phone is detected and how a
session runs. For the rules that keep it this way, see
[`.claude/skills/carlink-dev/SKILL.md`](../.claude/skills/carlink-dev/SKILL.md); for the current
connection design and its reasoning, [design/REBUILD_V2.md](../design/REBUILD_V2.md).

## Processes and modules

```
                ┌──────────────────── com.carlink.app (one APK) ─────────────────────┐
                │ main process                                    │ ":aa" process     │
 USB ──────────▶│ UsbRouterActivity / UsbWatcher ─┐                │                   │
 Bluetooth ────▶│ SystemEventReceiver ─▶ Home ────┼─▶ SessionManager                    │
 Home / Connect▶│ HomeActivity, switcher ─────────┘   │  WirelessPolicy, DevicePreflight │
                │                                     ├─▶ CarPlayAdapter ─▶ DiPlay       │
                │                                     │    (CarPlayHostActivity)         │
                │                                     └─▶ AndroidAutoAdapter ── broadcasts ─▶ AaCommandReceiver
                │                                                 │   ◀── events ──── AaEngineHooks
                │                                                 │      AapService (DiAuto)
                └────────────────────────────────────────────────────────────────────┘
```

Modules:

| Module | Directory | Holds |
|---|---|---|
| `:app` | `app/` | The APK: `CarLinkApplication`, Home and the other screens (`ui/`), the floating switcher (`switcher/`), `entry/HomeLauncher`, Diagnostics, `engine/EngineChrome`, UI copy and theme |
| `:carlink-core` | `carlink-core/` | The connection backend as a library: `connection/` (`CarLinkCore`, `SessionManager`, adapters, `aa/`), `detect/`, `settings/`, `core/`, the USB/boot/Bluetooth entry points, `EventLog`, and the engines' manifest overrides |
| `:carplay-shared`, `:carplay-common` | `vendor/diplay/` | The CarPlay engine (DiPlay), main process |
| `:androidauto`, `:androidauto-contract` | `vendor/diauto/` | The Android Auto engine (DiAuto), `:aa` process |

- `carlink-core/src/main/java/com/carlink/core/connection/` is the connection layer:
  `SessionManager` (one state machine, one engine at a time), `WirelessPolicy` (which paths, in
  which order), `DevicePreflight` and `CarPlayPreflight` (problems with a one-tap fix before
  anything opens), `CarPlayAdapter` and `AndroidAutoAdapter` (drive each engine through small
  hooks), and `connection/aa/` (the Android Auto side that runs in `:aa`). The library's
  packages are `com.carlink.core.*`; the app's stay `com.carlink.app.*`.
- `detect/` tells iPhones from Android phones and probes whether the stereo's Bluetooth is an
  external module apps cannot use (`ExternalBluetoothProbe`, [EXTERNAL_BLUETOOTH.md](../design/EXTERNAL_BLUETOOTH.md)); `entry/` holds the USB, boot and Bluetooth entry
  points; `settings/` the unified settings (all in `:carlink-core`); `ui/` and `switcher/` the
  screens (in `:app`).
- **Process isolation.** Android Auto runs in its own `:aa` process, so DiAuto's singletons, car
  mode, Conscrypt and its "kill process on disconnect" option cannot affect a CarPlay session.
- **Minimal engine changes.** Every edit inside `vendor/` is a small, documented hook:
  [VENDOR_CHANGES.md](VENDOR_CHANGES.md). Both engines update by applying the upstream diff since the recorded upstream commit (a subtree pull finds no merge base).
- **Reusable backend.** `:carlink-core` (with the engines) is a library another app, such as a
  car-stereo launcher, can depend on: [INTEGRATION.md](INTEGRATION.md).

Design and engineering references: [design/REBUILD_V2.md](../design/REBUILD_V2.md) (the current
connection design), [dissection/DIPLAY.md](dissection/DIPLAY.md) and
[dissection/DIAUTO.md](dissection/DIAUTO.md) (how each engine works and fails),
[design/HOME_V2_RATIONALE.md](../design/HOME_V2_RATIONALE.md) (the current UI). CarLink ships in
**English only**, including the engines' screens (`androidResources.localeFilters` in
[`app/build.gradle.kts`](../app/build.gradle.kts)). It picks a size profile automatically (Settings › General › Layout):
**Car display** (large touch targets and text, sized from the panel's pixels) or **Phone or
tablet** (Material 3 sizes).

## Detection and sessions

What the driver sees of this (which phone gets which engine, Wi-Fi Direct first, automatic
reconnection, the switch prompt, one engine at a time) is described in
[USER_GUIDE.md › How connections work](USER_GUIDE.md#how-connections-work). The rules behind it
(`SessionManager`, `WirelessPolicy`, `PathMemory`, the stall playbook) are in the `carlink-dev`
skill and [design/REBUILD_V2.md](../design/REBUILD_V2.md).

## Android 17 background audio

CarLink targets SDK 37. On Android 17 an app that targets 37 and plays audio, requests audio focus
or changes volume while it is in the background needs a foreground service with while-in-use (WIU)
capability; otherwise `requestAudioFocus` returns `AUDIOFOCUS_REQUEST_FAILED` and playback fails
silently ([bg-audio](https://developer.android.com/about/versions/17/changes/bg-audio)). A
foreground service gets WIU only when it is started while the app is visible (or through an
exemption CarLink does not have: system component, notification or widget tap, a PendingIntent
from another visible app). The same WIU decides whether the `microphone` type is granted
(Android 14+). A running service keeps the WIU it got at its start.

How each engine complies:

- **CarPlay.** `DiPlaySessionService` is started only by `CarPlayHostActivity.startCarPlay`, i.e.
  by the visible projection activity; DiPlay's reconnects (`restartCarPlay`) keep the service
  running, and a new CarLink session reopens the activity first (`CarPlayAdapter.openHost`).
- **Android Auto.** `AapService` runs in `:aa` and is started by `AaCommandHandler`. Every start
  goes through `AaCommandHandler.start`: when the service is not running yet and no CarLink
  screen (Home, either projection, any process) is in front, it opens Home
  (`CarLinkHost.OpenReason.ENGINE_START`, allowed because CarLink may draw over other apps) and
  waits up to 3 s for it before starting the service. This covers a phone found by `UsbWatcher`
  while another app is in front and a retry after the driver left the projection. Bluetooth and
  boot already open Home first (`SystemEventReceiver`), and a USB plug-in that reaches
  `UsbRouterActivity` starts while that activity is in front.
- `AapService` also takes the `microphone` type (Assistant and calls keep the microphone while
  another app covers the projection) and falls back to `connectedDevice|mediaPlayback` if Android
  refuses it.

Check on an Android 17 emulator: `adb shell cmd audio set-hardening enable` (the shipped command
is `set-hardening`, not the documented `set-enable-hardening`), then
`adb shell dumpsys activity services com.andrerinas.openheadunit.aap.AapService` must show
`getFgsAllowWiu_forStart=PROC_STATE_TOP` (not `DENIED`) and `types=0x00000092`; `AudioHardening`
lines in logcat or `dumpsys audio` name an app that broke the rule.
