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
- `detect/` tells iPhones from Android phones; `entry/` holds the USB, boot and Bluetooth entry
  points; `settings/` the unified settings (all in `:carlink-core`); `ui/` and `switcher/` the
  screens (in `:app`).
- **Process isolation.** Android Auto runs in its own `:aa` process, so DiAuto's singletons, car
  mode, Conscrypt and its "kill process on disconnect" option cannot affect a CarPlay session.
- **Minimal engine changes.** Every edit inside `vendor/` is a small, documented hook:
  [VENDOR_CHANGES.md](VENDOR_CHANGES.md). Engines update with `git subtree pull`.
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
