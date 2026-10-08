# CarLink

**CarPlay and Android Auto in one app.** Install CarLink on the car's head unit (or on an Android
tablet or phone used as the car display). Plug in or connect your phone over Bluetooth: CarLink
detects an iPhone and starts CarPlay, or an Android phone and starts Android Auto.

CarLink combines two open-source projects, imported with their full history:

- **DiPlay** (CarPlay) by shihabal3amri: `vendor/diplay`
- **DiAuto** (Android Auto) by shihabal3amri, based on Headunit Revived and OpenHeadunit: `vendor/diauto`

> These engines focus on BYD DiLink head units. They may work elsewhere, but other head units
> are not verified. CarLink is not affiliated with or endorsed by Apple or Google. CarPlay is a
> trademark of Apple Inc.; Android Auto is a trademark of Google LLC.

## What you get

- **Plug-and-play Home.** One status and one action: permission or Bluetooth setup when needed,
  then "Connect <phone>?", a step-by-step Connecting view, the running session with its timer, or
  a plain-language problem with its fix. A Phones sheet lists paired phones and asks what kind an
  unidentified device is. Connection is automatic over USB and Bluetooth.
- **Native Settings.** Everything is CarLink's own screen, never an engine's settings app.
  **Essentials** (connection, display, sound and voice, navigation, car, general) are one setting
  written into both engines. **Show advanced** adds every engine-only option, grouped by engine
  and tagged when it applies at the next connection. **Lab** (seven taps on the version) holds
  experimental and debug options.
- **Same Wi-Fi for both engines.** When the car and phone are on the same Wi-Fi, CarLink offers to
  use it instead of the car hotspot or Wi-Fi Direct; it needs that Wi-Fi's password once (Home
  asks, or Settings › Connection). CarPlay uses it directly. For Android Auto, CarLink names the
  router in the network the engine hands the phone (an access-point override); this path is
  **not yet verified on a real phone**.
- **Bluetooth starts wireless Android Auto.** An Android phone starts wireless Android Auto when
  its Bluetooth connects to the display. CarLink wakes the phone over Bluetooth when you tap
  Connect, but on a tablet or phone used as the car display you may need to connect the phone in
  Bluetooth settings once; Home says so and offers the button while it waits.
- **Diagnostics and connection log.** Device checks with one-tap fixes, the live session, and a
  connection log merging CarPlay, Android Auto and CarLink's own decisions (addresses hidden),
  filterable and shareable. Opened from Home when something fails, or Settings › General.
- **One identity.** The Cast logo (a car display with a docked phone) on the launcher, status bar
  and in the app; engine notifications, media sessions and visible strings say CarLink.

Screenshots of every screen and state: [docs/screenshots](docs/screenshots/README.md).

What is verified: the UI (emulator and the screenshot gallery), detection logic and settings
mapping (unit tests). USB, Bluetooth, Wi-Fi Direct and real CarPlay/Android Auto sessions depend on
real hardware; the engines themselves are DiPlay's and DiAuto's and are mainly tested by their
authors on BYD DiLink.

## How detection works

| The phone… | CarLink decides | Engine |
|---|---|---|
| is plugged in and has Apple's USB vendor ID (0x05AC) | iPhone | CarPlay, wired |
| is plugged in and exposes Android interfaces (accessory, MTP, PTP, ADB, RNDIS) | Android phone | Android Auto, wired |
| connects over Bluetooth and publishes the iAP2 service, or is named "iPhone"/"iPad" | iPhone | CarPlay, wireless |
| connects over Bluetooth and publishes phone services without iAP2 | Android phone | Android Auto, wireless |
| was identified before (a successful session or the driver's choice) | as remembered | |

USB flash drives and other peripherals are ignored. A phone without enough evidence is queried
over Bluetooth SDP first; nothing starts until CarLink is sure. Plugging in a phone may replace a
wireless session; a phone's Bluetooth never interrupts a running session or covers its picture.
Unplugging the phone ends a wired session. Cancelling pauses automatic connection until the
driver connects again (the phone re-enumerating on USB right after a Disconnect does not restart it).

When the driver taps Connect for an Android phone, CarLink wakes that phone over Bluetooth at once
(Android Auto's wireless setup starts on a Bluetooth connection) and keeps retrying until it
answers. The engines' own toasts are logged (`CarLink-EngineToast`) instead of shown, except the few
Android Auto forces past its own "toasts off" setting because nothing else would explain them; CarLink's
own decisions are logged under `CarLink-*` tags at INFO.

## Architecture

```
            ┌──────────────── com.carlink.app (one APK) ────────────────┐
            │  main process                         │  ":aa" process    │
 USB ──────▶│  UsbRouterActivity ─┐                 │                   │
 Bluetooth ▶│  SystemEventReceiver┼▶ EngineCoordinator ── commands ───▶ AndroidAutoCommandReceiver
 Boot ─────▶│  HomeActivity ──────┘   │      ▲      │   ◀── state ───── AndroidAutoStateReporter
            │                         ▼      │      │                   │      │
            │                CarPlayEngine  CarPlayStatusBus             │   AapService (DiAuto)
            │                     │                 │                   │
            │            DiPlayActivity / CarPlayHostActivity (DiPlay)  │
            └───────────────────────────────────────────────────────────┘
```

- **One engine at a time.** `EngineCoordinator` stops one engine before starting the other, so
  they never share the Wi-Fi Direct group, hotspot, VPN slot, audio focus or media session.
- **Process isolation.** Android Auto runs in its own process. DiAuto's singletons, car mode,
  Conscrypt and its "kill process on disconnect" option cannot affect a CarPlay session.
- **Engines keep their own logic.** CarLink starts CarPlay through DiPlay's own connect pre-flight
  and Android Auto through the same service intents DiAuto's entry points send.
- **Minimal engine changes.** Every edit inside `vendor/` is a small, documented hook:
  [docs/VENDOR_CHANGES.md](docs/VENDOR_CHANGES.md). Engines update with `git subtree pull`.

CarLink ships in **English only**, including the engines' screens (`androidResources.localeFilters`
in `app/build.gradle.kts`). The UI follows [design/DESIGN_SPEC.md](design/DESIGN_SPEC.md). It has two size profiles, chosen
automatically and selectable in Settings › General › Layout:
**Car display** (76dp touch targets, 24sp minimum text, sized from the panel's pixels because
head-unit DPI reports are unreliable) and **Phone or tablet** (Material 3 sizes). Layouts adapt
to the window: phone portrait, landscape, tablet, head unit and BYD split screen.

## Build

Requirements: JDK 25 for the Gradle daemon, Android SDK platforms 36 and 37, build-tools 35 and 36,
NDK `29.0.14206865` (Android Auto engine) and `28.2.13676358` (CarPlay engine), CMake `3.22.1`.
Set `ANDROID_HOME` or put `sdk.dir=...` in an untracked `local.properties`.

```sh
python3 scripts/resource_collisions.py --check
./gradlew :app:testDebugUnitTest :androidauto:testGithubDebugUnitTest \
          :carplay-shared:testDebugUnitTest :carplay-common:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK (`com.carlink.app.hudtest`) is in `app/build/outputs/apk/debug/`. It installs
alongside DiAuto and DiPlay. Run one projection app at a time.

### CarPlay authentication files

Ordinary source builds contain **no CarPlay accessory identity**, exactly like DiPlay's. CarPlay
cannot connect without the two files DiPlay's releases use (`offline-mfi/identity.pk8` and
`offline-mfi/certificate.p7b`). Provide them only for a local car-test build:

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/dir-containing-offline-mfi ./gradlew :app:assembleDebug
```

Read DiPlay's README about what this identity is and its limits before distributing such a
build. Android Auto does not need these files. Diagnostics (Settings › General) shows whether they are present.

## Development and tests

Work in this repository follows the project skill
[.claude/skills/carlink-dev](.claude/skills/carlink-dev/SKILL.md); its `scripts/dev.sh` wraps the
build, the four unit-test suites, installing on the emulator (`carlink_headunit`), screenshots at
head-unit and phone size, and logs. UI changes are checked on the emulator; connection work needs
a real head unit or tablet and phones. The screenshot gallery renders without any device:

```sh
./gradlew --no-daemon :app:recordScreenshots   # rewrites docs/screenshots
```

## Licence

DiAuto is licensed under the GNU AGPL v3 and DiPlay under the GNU GPL v3. GPL v3 §13 allows
combining them; CarLink as a whole is distributed under the **GNU Affero General Public
License v3** (see `vendor/diauto/LICENSE` and `vendor/diplay/LICENSE`). If you distribute CarLink,
or let people use it over a network, you must offer its complete source code.
