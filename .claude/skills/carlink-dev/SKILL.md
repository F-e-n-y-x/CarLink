---
name: carlink-dev
description: How to build, change, test and ship the CarLink Android app (one APK that runs Apple CarPlay via the DiPlay engine and Android Auto via the DiAuto engine, auto-detecting the phone). Use this skill for ANY work in the CarLink repository — UI screens, settings, the connection layer (SessionManager, adapters, wireless paths), USB/Bluetooth/Wi-Fi connection logic, vendor engine changes, builds, tests, screenshots, emulator checks, commits — even when the request only says "fix the app", "make the UI better", "add a setting" or "why doesn't my iPhone connect".
---

# CarLink development

CarLink is one Android app for car head units, tablets and phones used as a car display. Plug in
or pair a phone: an iPhone gets CarPlay, an Android phone gets Android Auto, over USB or
wirelessly. It is generic (any Android stereo or tablet), not BYD-specific. The user wants a
polished, single, fully integrated app: plug-and-play for beginners, every engine setting
available to advanced users, and no trace of the two original apps.

Read `README.md`, `design/REBUILD_V2.md` (the current connection design),
`docs/dissection/DIPLAY.md` / `DIAUTO.md` (how each engine works and fails; cited as DP§… / DA§…),
`docs/VENDOR_CHANGES.md` and `docs/INTEGRATION.md` before a non-trivial change. The user's
decisions recorded there override older documents (each `design/` file says at the top whether it
is current or superseded).

## Architecture (v2)

```
entry/ (USB, boot, Bluetooth)  ui/ (Home, Settings, …)  switcher/ (bubble, switch prompt)
              \                       |                        /
               └──────────▶ connection/Connection.get(ctx): SessionManager
                                 │  WirelessPolicy, DevicePreflight (+ CarPlayPreflight),
                                 │  DeviceEnvironment, PathMemory, settings writers
                     ┌───────────┴────────────┐
              CarPlayAdapter            AndroidAutoAdapter ── explicit broadcasts ──▶ connection/aa/*
              (DiPlay, main process)                         (AaProtocol)            (":aa" process, DiAuto)
```

- **`SessionManager` is the only place that runs sessions.** Main thread. State:
  `state: StateFlow<SessionState>` (`Idle`, `Blocked(problems)`, `Active(path, attempt, phase)`,
  `Failed(reason)`) and `pendingSwitch: StateFlow<SessionRequest?>`. Triggers: `onUsbPhone`,
  `onUsbDetached`, `onBluetoothPhone`, `onHomeShown`, `connect`, `retry`, `disconnect`, `stop`,
  `acceptSwitch`/`declineSwitch`. Its rules, each from a failure seen on a device:
  - one engine at a time; a new session starts only after the previous one's **stop completed**
    (DP F14: an early start let the old teardown kill the new session's service);
  - wireless paths come from `WirelessPolicy`; only a `FailureReason.pathRelated` failure moves to
    the next path; the path that connected is remembered per phone (`PathMemory`);
  - automatic starts (Bluetooth, Home shown) never interrupt a running session and respect the
    driver's pause (`CarLinkPreferences.autoConnectPaused`, set by Disconnect/Cancel/unplug,
    expires after 10 min);
  - USB is deliberate: a USB phone starts at once when idle; during a session it sets
    `pendingSwitch` ("Switch to <phone>?"); the engines' own re-enumeration is not a new session;
  - an orphaned engine session found at start-up is stopped (the driver must see what runs);
  - no silent waits: a per-phone `ConnectionProfiles` history sets each phase's deadline (2.5 ×
    the phone's median, bounded); the driver is told why after ≤ 10 s, and a phase past its
    deadline gets the next `StallPlaybook` recovery (nudge, USB re-switch, retry, fresh Wi-Fi
    Direct, next path, re-plug, fail). `health: StateFlow<List<PhoneHealth>>` summarises it.
- **Adapters start exactly the session they are handed** (`EngineAdapter`: `start(plan, listener)`,
  `stop(onStopped)`, `hasSession()`), report `AdapterEvent`s, and never decide paths or retries
  across paths. `CarPlayAdapter` drives DiPlay through `CarPlayHostIntegration` (`hasSession`,
  `stopSession(completion)`, `onHostState`, `waitingView`); never read
  `CarPlayStatusBus.current == null` as "no session" (DP F13). `AndroidAutoAdapter` talks to
  `connection/aa/AaCommandReceiver` in `:aa` with explicit broadcasts and hears back from
  `AaEngineHooks` (engine events and CommManager states).
- **Wireless policy.** Automatic = Wi-Fi Direct first (proven for both engines on the tablet; the
  engines' authors recommend it); on a head unit with known hotspot details, CarPlay tries the
  car hotspot first. **Same Wi-Fi is never chosen automatically** (Android Auto needs the typed
  password and exact BSSID; CarPlay needs a router that passes mDNS). Any change to path choice is
  verified on the real tablet with the `carlink-device` skill before it is called done.
- **Settings are written per session.** `UnifiedSettings` (one setting for both engines) is
  translated by `CarPlaySettingsPolicy` (`connection/CarPlaySettings.kt`) and
  `AaSessionSettings` and written by `Connection` right before each start. DiAuto's settings
  belong to `:aa`: read/write them only through `engine/AndroidAutoSettingsProvider`
  (`AndroidAutoSettings.read/write`), never through a second `SharedPreferences` in the main
  process (each process caches prefs and would overwrite the other).
- **Android Auto stays in `:aa`.** DiAuto casts the application context to `App`, keeps
  singletons, enables car mode and may `System.exit`. `CarLinkApplication` extends `App` and only
  initializes the engine in `:aa`. DiAuto's key/media receivers stay disabled except during
  Android Auto sessions (`DeviceEnvironment.onEngaged`).
- **Detection is CarLink's job.** USB: Apple VID 0x05AC → CarPlay; Android interfaces → Android
  Auto; peripherals ignored (`detect/PhoneClassifier`). `UsbWatcher` watches USB itself because
  iPhones also expose PTP and Photos/MTP compete for the system's attach dispatch.
- **One app, never "app inside app".** CarLink's screens are the only UI the driver sees, apart
  from the two projection surfaces (`CarPlayHostActivity`, `AapProjectionActivity`), whose waiting
  view and chrome are CarLink's (`CarPlayWaitingView`, `engine/EngineChrome`). Never route the
  driver to `DiPlayActivity`, DiAuto's `MainActivity`/`SettingsActivity` or any engine settings
  screen.
- **Generic, not BYD.** `VehicleProfile.GENERIC` is the default; everything BYD-specific (HUD and
  cluster outputs, wheel codes, DiLink window claims) runs only with `BYD_DILINK`.
  The driver picks a `CarBrand` (makes popular in India, then luxury, then Other;
  `Connection.carBrand/setCarBrand`); only `CarBrand.BYD` implies `BYD_DILINK`, every other make is
  just a label. The brand never reaches the phone (accessory name, Android Auto car name and Wi-Fi
  Direct identity stay the same).
- **Reusable backend: `:carlink-core`.** `connection/`, `detect/`, `settings/`, `core/`, the USB,
  boot and Bluetooth entry points (`entry/UsbRouterActivity`, `UsbWatcher`,
  `SystemEventReceiver`), `engine/AndroidAutoSettingsProvider`, `engine/WifiDirectReset` and
  `diagnostics/EventLog` live in the library module `carlink-core/` (packages
  `com.carlink.core.*`, R class `com.carlink.core.R`; the app keeps `com.carlink.app.*`), with their unit tests and the engines'
  manifest overrides (`carlink-core/src/main/AndroidManifest.xml`). A launcher will reuse it
  (`docs/INTEGRATION.md`). The app supplies what the backend needs through
  `CarLinkCore.install(app, CarLinkConfig(accessoryName, host, waitingView))` in
  `CarLinkApplication`; `entry/HomeLauncher` is CarLink's `CarLinkHost`. The library cannot see
  `ui/`, `switcher/` or the app's `R`: anything it needs from the app goes through
  `CarLinkConfig`/`CarLinkHost`, and backend code goes into `carlink-core/`, UI copy into `app/`.

## Vendor engines

`vendor/diplay` (CarPlay, main process; upstream main 292b2dd) and `vendor/diauto` (Android Auto,
`:aa`) are git subtrees. Change them only through small hooks marked `// CarLink`, and add a row
to `docs/VENDOR_CHANGES.md` for every edit (every edit needs a row, every row an edit; audit with
`git diff --stat 292b2dd7: HEAD:vendor/diplay`, `git diff --stat a2119f48 HEAD:vendor/diauto` and
`grep -rn CarLink vendor/`). Prefer a host hook over editing engine logic. Do not run
`git subtree pull` unless asked.

The hooks CarLink sets (where):

| Engine | Hook | Set in |
|---|---|---|
| DiAuto `HostIntegration` | `homeActivity`, `appName`, `serviceChannelName`, `mediaSessionTag`, `toastHandler` | `CarLinkApplication.brandEngines` |
| | `stickyService`, `restartOnTaskRemoved`, `claimHiddenBottomStrip`, `hostOwnsUsbSwitch`, `onEngineEvent`, `usbOnlySession` | `connection/aa/AaEngineHooks` (and `AaCommandHandler` per session) |
| | `chromeStyler`, `exitMenuHomeLabel` | `engine/EngineChrome` |
| DiPlay `CarPlayHostIntegration` | `homeActivity`, `appName`, `mediaSessionTag`, `toastHandler` | `CarLinkApplication.brandEngines` |
| | `accessoryName` (from `CarLinkConfig`), `resetDebugLogsOnBootstrap`, `vehicleIntegrations`, `onHostState`, `waitingView` (`CarLinkConfig.waitingView` = `ui/home/CarPlayWaitingContent`) | `connection/CarPlayAdapter.install` (via `CarLinkCore.install`), `CarPlayWaitingView` |
| | `hasSession`, `stopSession`, `retryNow`, `selectPhone`, `selectedPhoneAddress`, `authenticationError`, `homeIntent` (calls) | `CarPlayAdapter`, `CarPlayPreflight`, `CarPlayWaitingView` |
| | `palette`, `chromeStyler`, `menuItems` | `engine/EngineChrome` |
| DiPlay `WirelessStartupTimeline` | `milestoneListener` (wireless milestones as progress) | `CarPlayAdapter` |

## Build and test loop

Use `.claude/skills/carlink-dev/scripts/dev.sh` (it encodes paths, the CarPlay auth files and the
emulator-only default):

```sh
S=.claude/skills/carlink-dev/scripts/dev.sh
$S build        # debug APK; includes ~/.carlink-private CarPlay files when present
$S test         # resource-collision check + all five unit-test suites (app, carlink-core, engines)
$S install      # install on the emulator and launch
$S shot home    # /tmp/carlink-shots/home.png — then LOOK at it with the Read tool
$S size 1080x2400 420   # phone; "1920x1080 240" head unit; "reset" to restore
$S logs
./gradlew --no-daemon :app:recordScreenshots   # docs/screenshots gallery (half-size WebP), no device needed
$S build-release                        # the shrunk (R8) release APK, com.carlink.app
CARLINK_TARGET=tablet $S install-release   # install it on the tablet and launch it
```

**Release builds run R8** (shrink + optimize + resource shrinking, `-dontobfuscate`; phase 1 of
`design/RESEARCH_2026-10_R8_PLATFORM.md`). Debug builds do not, so a change that adds reflection,
JNI or a class loaded by name (app_process tools, Parcelables from other processes, Shizuku)
needs a narrow rule: app rules in `app/src/main/keepRules/carlink.keep`, engine rules in the
engine's consumer file (`vendor/diplay/shared/ambient-consumer-rules.pro`,
`vendor/diauto/app/proguard-project.txt`, rows in `docs/VENDOR_CHANGES.md`). No package-wide
member keeps for CarLink's code (the class-identity rules in `carlink.keep` keep no members).
`build-release` signs with the local debug key unless `ANDROID_KEYSTORE_PATH`/`_PASSWORD`,
`ANDROID_KEY_ALIAS`/`_PASSWORD` name the release key (none exists on this PC). The release
package `com.carlink.app` installs next to the debug `com.carlink.app.hudtest`; both answer USB
plugs and Bluetooth, so uninstall or stop the other one for a clean test (the user decides; the
debug app's data, e.g. pairings, goes with it). It is not debuggable: `run-as` fails, and
`report.py --package com.carlink.app` takes the engines' lines from logcat instead (run it right
after the test). R8 output: `app/build/outputs/mapping/release/` (`mapping.txt` to retrace inlined
frames, `configuration.txt`, `usage.txt`, `seeds.txt`); `./gradlew :app:analyzeReleaseR8Config`
for the keep-rule report. Any R8 change is verified on the tablet with the device matrix in the
research note, not only by a green build.

**Releases and the in-app updater.** Versions are `versionName` only (`versionCode` = major·10000
+ minor·100 + patch). Releases are built and signed locally with `scripts/release.sh X.Y.Z`
(stops at `dist/`; `--publish` tags and uploads; never in CI; `docs/RELEASING.md`), with the key
in `~/.carlink-private/release/`. The updater (`app/.../update/`, UI-free, and
`ui/update/UpdateDialog.kt`) reads GitHub's `releases/latest`, only in the release package signed
with the pinned key, never during a session, and installs only after the SHA-256, package/version
and signer checks pass. Do not publish or tag unless the user asks.

`SmoothVideoHostLifecycleTest` (DiPlay) is a known flaky timing test: rerun when it is the only
failure. Environment facts: the Gradle daemon needs JDK 25 (registered in
`~/.gradle/gradle.properties`); the SDK is the writable overlay `~/android-sdk` (if the shell
also exports `ANDROID_SDK_ROOT=/opt/android-sdk`, Gradle refuses to configure: run with
`ANDROID_SDK_ROOT=$HOME/android-sdk`); CarPlay authentication files live only in
`~/.carlink-private/offline-mfi/` and must never be committed (`.gitignore` blocks `*.pk8`,
`*.p7b`).

## Verify on the device

UI work is checked on the emulator (`emulator-5560`, AVD `carlink_headunit`). **Connection work
(USB, Bluetooth, Wi-Fi Direct, Same Wi-Fi, real CarPlay/Android Auto) cannot be tested on the
emulator and is not done until it is verified on the user's tablet** with the `carlink-device`
skill (a report showing it connect and stay up). The user allowed live testing on their tablet
(`192.168.10.103:5555`, Redmi Pad Pro 5G, Android 16) on 2026-10-09; follow the latest
instruction in the conversation about whether to use it now, and never change the tablet's system
settings. When a change could not be verified on the device, say so plainly.

Logs to read (all in the app's private files, `run-as com.carlink.app.hudtest`):

| File | Written by | Holds |
|---|---|---|
| `files/logs/carlink.log` (+ previous) | CarLink `diagnostics/EventLog` | `SessionManager` decisions (`CarLink-Session` in logcat), USB classification, "settings written in N ms", "engine process warmed in N ms" |
| `files/logs/diplay.log` (+ `previous*.log`) | DiPlay | CarPlay session log, `WIRED_TIMING`, `WIRELESS_TIMING` |
| `files/diagnostics/current.log` | DiAuto | Android Auto journal, `WIRELESS_TIMING` |

Timing lines (always on, INFO, also in logcat):

- `WIRED_TIMING <milestone> plug+<ms>ms session+<ms>ms` (DiPlay, USB CarPlay): `iphone-found`,
  `data-paths`, `ncm-attached`, `mfi-done`, `start-session`, `network-connection`,
  `bulk-out-ready`, `tcp-accepted`, `session-active`, `first-frame`.
- `WIRELESS_TIMING <phase> start+<ms>ms session+<ms>ms [detail]` (DiPlay, wireless CarPlay):
  `controller-start` … `rfcomm-dial`, `hotspot-ready`, `rfcomm-connected`, `authenticated`,
  `start-session`, `peer-associated`, `tcp-accepted`, `first-frame` (`first-tcp-timeout` on a miss).
- `WIRELESS_TIMING <phase> start+<ms>ms [detail]` (DiAuto, wireless Android Auto): `begin`,
  `wifi-init`, `group-create`/`group-created`, `credentials` (with `bssid=<hash>`: compare
  groups), `poke-start`/`poke-connected`, `phone-rfcomm`, `phone-joined`, `tcp-accepted`,
  `first-frame`.
- `AIRPLAY_TIMING <METHOD> <path> conn+<ms>ms wait=<ms>ms handle=<ms>ms status=<n>` (DiPlay,
  every AirPlay control request): `wait` is the iPhone's time since our previous answer.
- CarLink (`carlink.log`, lines end `#s<n>`, the session number): `… connected on <PATH> in <ms>
  ms`, `<ENGINE> stopped in <ms> ms`, `<ENGINE> retrying at <STAGE> (attempt n, REASON: detail)`,
  `the phone has not answered for N s`, `stall: <ENGINE> <phase> on <PATH> has taken <ms> ms
  (deadline …, usual …); <ACTION>`, `wireless paths [...] re-ordered by this phone's record`,
  `Bluetooth warm-up finished in <ms> ms`.

Measured on the tablet (2026-10-09, plug-in/Connect to picture): USB CarPlay ≈ 3.4 s, USB Android
Auto ≈ 3.8 s, wireless Android Auto ≈ 5 s, wireless CarPlay ≈ 16-20 s (mostly paging the iPhone
over Bluetooth). Quote new numbers from these lines, not from memory.

Work autonomously (the user granted full permission, internet access and use of this PC) and run
**at most two agents at a time**.

A UI change is not done until you have screenshotted it at head-unit size and at phone size (or
rendered it in the gallery) and looked at the images for clipped text, wrong colors, empty areas
and anything that looks unfinished. Report what you saw, honestly, including what you could not
verify.

## UI rules

- Visual direction: the "Instrument" dashboard language (`design/HOME_V2_RATIONALE.md`,
  `design/RESEARCH_HOME_V2.md`): full-bleed dark canvas, bold type, one hero status, translucent
  cards faked without real-time blur (head-unit GPUs are weak).
- English only. No hard-coded strings in code; prefix new strings `carlink_`.
- Two size profiles from `UiProfile`: CAR (76dp targets, 24sp minimum text) and HANDHELD
  (Material 3 sizes). Layouts adapt to window size classes: phone portrait/landscape, tablet,
  head unit, BYD split screen. The user's tablet (≈1458×911 dp) is a primary target.
- Beginners: zero configuration — Home shows one status and one action; connection is automatic.
  Advanced users: every engine setting under Show advanced, labelled with what it affects and
  whether it applies now or at the next connection.

## Commit and push

Small commits with an imperative subject and a body explaining why; end with
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Run `$S test` and `$S build` before
each commit; never push a commit that does not compile. The remote is the user's private repo
`F-e-n-y-x/CarLink` (`git push` from `main`).
