# CarLink

**CarPlay and Android Auto in one app.** Install CarLink on an Android car stereo, or on an
Android tablet or phone used as the car's display. Plug in or connect your phone: an **iPhone
gets CarPlay**, an **Android phone gets Android Auto**, by cable (USB) or wirelessly. CarLink
works out which phone it is; there is nothing to choose.

CarLink is generic: it is meant for any Android head unit or tablet, not one car brand. The two
engines it is built on were written for BYD DiLink head units; their BYD-only features stay off
unless you pick the BYD car profile.

CarLink combines two open-source engines, imported with their full history:

- **DiPlay** (CarPlay) by shihabal3amri: `vendor/diplay`
- **DiAuto** (Android Auto) by shihabal3amri, based on Headunit Revived and Open Headunit: `vendor/diauto`

> CarLink is not affiliated with or endorsed by Apple or Google. CarPlay is a trademark of
> Apple Inc.; Android Auto is a trademark of Google LLC.

## What you get

- **One Home screen.** One status and one action: a permission or Bluetooth fix when one is
  needed, "Connect <phone>?", a step-by-step Connecting view, the running session with its timer,
  or a plain-language problem with its one-tap fix. A Phones sheet lists paired phones.
- **Native settings.** Every screen is CarLink's own, never an engine's settings app. Settings
  shared by both engines (picture, interface size, day/night, driving side, sound routing,
  location, connection method) are one setting written into each engine before each session.
  **Show advanced** adds every engine-only option inside its category; **Lab** (seven taps on the
  version) holds experimental and debug options.
- **A floating switcher** over CarPlay and Android Auto: a small bubble that opens a panel to
  switch to another paired phone or open CarLink's Home.
- **Diagnostics.** Device checks with one-tap fixes, the live session, and a connection log
  that merges CarPlay, Android Auto and CarLink's own decisions (addresses hidden), shareable.
- **One identity.** Engine notifications, media sessions, the name the phone shows for the car
  and the projection screens' own chrome say CarLink.

Screenshots of every screen and state: [docs/screenshots](docs/screenshots/README.md).

## How connections work

| The phone… | CarLink decides | Engine |
|---|---|---|
| is plugged in and has Apple's USB vendor ID (0x05AC) | iPhone | CarPlay over USB |
| is plugged in and exposes Android interfaces (accessory, MTP, PTP, ADB, RNDIS) | Android phone | Android Auto over USB |
| connects over Bluetooth and publishes the iAP2 service, or is named "iPhone"/"iPad" | iPhone | wireless CarPlay |
| connects over Bluetooth and publishes phone services without iAP2 | Android phone | wireless Android Auto |
| was identified before (a successful session or the driver's choice) | as remembered | |

USB flash drives and other peripherals are ignored. A phone without enough evidence is queried
over Bluetooth SDP first; nothing starts until CarLink is sure.

- **Wi-Fi Direct by default.** Wireless sessions use a Wi-Fi Direct link between the phone and
  the display, which needs no router and no hotspot. On a car head unit whose own hotspot
  details are known, wireless CarPlay tries that hotspot first. The path that worked last time
  for a phone is tried first next time; only a network-related failure moves on to the next path.
- **Same Wi-Fi is opt-in.** Settings › Connection (or the card Home offers once) can use the Wi-Fi
  network the display is already on. It needs that network's password once. It is never chosen
  automatically (see Known limits).
- **Plugging in starts at once.** A cable is a deliberate act, so a USB phone starts its engine
  straight away when nothing else runs.
- **The last phone connects by itself.** When Home comes to the front (the app opens, the screen
  wakes), CarLink starts the last-used phone wirelessly, as a car stereo does when the car starts.
  It does this at most once a minute and not right after a session ended. After you press
  Disconnect or Cancel, automatic starts pause until you connect again or for 10 minutes.
- **A second phone asks first.** Plugging in another phone while a session runs shows
  "Switch to <phone>?" (Switch / Not now) instead of taking over.
- **Unplugging ends a wired session** and brings Home back; the engines' own USB re-enumeration
  while connecting is not mistaken for an unplug.
- **One engine at a time.** A new session starts only after the previous one has fully stopped,
  so the two engines never share the Wi-Fi Direct group, VPN slot, audio focus or media session.

### First-time setup

1. Open CarLink and allow the permissions it asks for (nearby devices, location, microphone,
   notifications, display over other apps).
2. **Pair from the phone.** On the phone, open Bluetooth settings and pair with this display
   ("Pair a phone" on Home opens the display's Bluetooth settings so it is visible). A tablet
   cannot start the car-kit side of pairing itself, so pairing is always started on the phone.
3. Tap **Connect** on Home (or plug in the cable). On the phone, accept "Use CarPlay" or
   Android Auto's prompt the first time. Keep the phone unlocked during the first connection.

A cable works without pairing.

### Connection times

Measured on the test tablet (Redmi Pad Pro 5G, Android 16) with the user's iPhone and Samsung
Galaxy S25 Ultra, from plug-in or Connect to the first picture, 2026-10-09:

| Connection | Measured on the test tablet |
|---|---|
| CarPlay over USB | about 3.4 s |
| Android Auto over USB | about 3.8 s |
| Android Auto, wireless (Wi-Fi Direct) | about 5 s |
| CarPlay, wireless (Wi-Fi Direct) | about 16-20 s, dominated by paging the iPhone over Bluetooth |

Other head units, tablets and phones are expected to be in the same range but have not been
measured. Each engine writes always-on timing lines (`WIRED_TIMING`, `WIRELESS_TIMING`) to its
log, so any device can be measured; see the `carlink-device` skill.

### Known limits

- **During wireless Android Auto the phone uses mobile data.** The phone joins the display's
  Wi-Fi link, which has no internet, so its apps go over mobile data. Allow Android Auto to use
  mobile data on the phone. No app on the display can change this.
- **Same Wi-Fi CarPlay needs a router that passes mDNS (Bonjour).** Many home and mobile routers
  block it between clients; then the iPhone authenticates over Bluetooth but never reaches the
  display, and Home suggests Wi-Fi Direct. It does not work on the user's router. Same Wi-Fi
  Android Auto needs the network's password and the router's exact address (BSSID), so both
  stay opt-in.
- **A tablet is not a Bluetooth car kit.** It has no hands-free or car-audio role, so the phone
  never connects to it by itself when you get in. CarLink starts the connection instead (Home
  shown, Connect, plug-in). A car head unit with those roles is reconnected by the phone itself.
- **CarPlay needs authentication files** that are not part of this repository (see Build). Without
  them Android Auto still works and Home says why CarPlay cannot sign in.

## Architecture

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
  [docs/VENDOR_CHANGES.md](docs/VENDOR_CHANGES.md). Engines update with `git subtree pull`.
- **Reusable backend.** `:carlink-core` (with the engines) is a library another app, such as a
  car-stereo launcher, can depend on: [docs/INTEGRATION.md](docs/INTEGRATION.md).

Design and engineering references: [design/REBUILD_V2.md](design/REBUILD_V2.md) (the current
connection design), [docs/dissection/DIPLAY.md](docs/dissection/DIPLAY.md) and
[docs/dissection/DIAUTO.md](docs/dissection/DIAUTO.md) (how each engine works and fails),
[design/HOME_V2_RATIONALE.md](design/HOME_V2_RATIONALE.md) (the current UI). CarLink ships in
**English only**, including the engines' screens (`androidResources.localeFilters` in
`app/build.gradle.kts`). It picks a size profile automatically (Settings › General › Layout):
**Car display** (large touch targets and text, sized from the panel's pixels) or **Phone or
tablet** (Material 3 sizes).

## Build

Requirements: JDK 25 for the Gradle daemon, Android SDK platforms 36 and 37, build-tools 35 and 36,
NDK `29.0.14206865` (Android Auto engine) and `28.2.13676358` (CarPlay engine), CMake `3.22.1`.
Set `ANDROID_HOME` (and no conflicting `ANDROID_SDK_ROOT`) or put `sdk.dir=...` in an untracked
`local.properties`.

```sh
python3 scripts/resource_collisions.py --check
./gradlew :app:testDebugUnitTest :carlink-core:testDebugUnitTest :androidauto:testGithubDebugUnitTest \
          :carplay-shared:testDebugUnitTest :carplay-common:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK (`com.carlink.app.hudtest`) is in `app/build/outputs/apk/debug/`. It installs
alongside DiAuto and DiPlay. Run one projection app at a time.

### CarPlay authentication files (not in git)

Ordinary source builds contain **no CarPlay accessory identity**, exactly like DiPlay's. CarPlay
cannot connect without the two files DiPlay's releases use (`offline-mfi/identity.pk8` and
`offline-mfi/certificate.p7b`). They are never committed (`.gitignore` blocks `*.pk8` and
`*.p7b`). Provide them only for a local car-test build:

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/dir-containing-offline-mfi ./gradlew :app:assembleDebug
```

Read DiPlay's README about what this identity is and its limits before distributing such a
build. Android Auto does not need these files. Diagnostics shows whether they are present.

## Development

Work in this repository follows two project skills:

- [.claude/skills/carlink-dev](.claude/skills/carlink-dev/SKILL.md): architecture rules, the
  build and test loop (`scripts/dev.sh build|test|install|shot|size|logs`), UI checks on the
  emulator, vendor-hook rules and commit rules.
- [.claude/skills/carlink-device](.claude/skills/carlink-device/SKILL.md): testing connections
  on a real tablet or head unit and reading the merged CarLink / DiPlay / DiAuto logs
  (`scripts/report.py`). The emulator has no Bluetooth or real Wi-Fi, so a connection change is
  only done once it is verified on a device.

The screenshot gallery renders every screen state without any device or emulator:

```sh
./gradlew --no-daemon :app:recordScreenshots   # rewrites docs/screenshots
```

## Licence

DiAuto is licensed under the GNU AGPL v3 and DiPlay under the GNU GPL v3. GPL v3 §13 allows
combining them; CarLink as a whole is distributed under the **GNU Affero General Public
License v3** (see `vendor/diauto/LICENSE` and `vendor/diplay/LICENSE`). If you distribute CarLink,
or let people use it over a network, you must offer its complete source code.
