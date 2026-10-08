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
wireless session; a phone's Bluetooth never interrupts a running session. Cancelling pauses
automatic connection until the driver connects again.

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
automatically and selectable in Settings › Display › Layout:
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
build. Android Auto does not need these files. Settings › Diagnostics shows whether they are present.

## Licence

DiAuto is licensed under the GNU AGPL v3 and DiPlay under the GNU GPL v3. GPL v3 §13 allows
combining them; CarLink as a whole is distributed under the **GNU Affero General Public
License v3** (see `vendor/diauto/LICENSE` and `vendor/diplay/LICENSE`). If you distribute CarLink,
or let people use it over a network, you must offer its complete source code.
