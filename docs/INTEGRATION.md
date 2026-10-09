# Reusing CarLink's connection backend

CarLink's backend (phone detection, the session layer, the two engine adapters, the engines
themselves and the unified settings) is meant to be reused by another app on the same car
stereo, such as a launcher. This page describes it as a component: what a host app calls, what
it must declare, which engine hooks it sets, and what stays in CarLink's UI. It also lists the
places where the backend still depends on CarLink's app code; those must be cut before the
backend can become a library module.

Paths are relative to `app/src/main/java/com/carlink/app/` unless they start with `vendor/`,
`app/` or `docs/`.

## What the backend is

| Package | Role |
|---|---|
| `connection/` | `SessionManager` (one session, one engine at a time), `Connection` (the wired-up singleton), `WirelessPolicy`, `DevicePreflight`, `CarPlayPreflight`, `DeviceEnvironment`, `PathMemory`, `PairingSession`, the CarPlay side (`CarPlayAdapter`, `CarPlaySessionEvents`, `CarPlaySettings`, `CarPlayWaitingView`) and `LinkStateMapping` |
| `connection/aa/` | The Android Auto side: `AndroidAutoAdapter`'s peer in the `:aa` process (`AaCommandReceiver`, `AaCommandHandler`, `AaEngineHooks`, `AaEngineAccess`, `AaSessionSettings`, `AaSessionTracker`, `AaStartPolicy`, `AaProtocol`) |
| `detect/` | `PhoneClassifier` (USB and Bluetooth evidence → iPhone / Android / unknown), `KnownPhones`, `PhonesRepository` (paired phones as a flow), `UsbPhoneNames` |
| `entry/` | `UsbRouterActivity` (the only USB-attach handler), `UsbWatcher` (USB attach/detach while the app runs), `SystemEventReceiver` (boot, Bluetooth ACL/SDP), `HomeLauncher` |
| `settings/` | `UnifiedSettings` + `UnifiedSettingsStore`, `CarLinkPreferences`, `VehicleProfile`, `WifiEnvironment` (+ `WifiEnvironmentReader`), `WirelessRefusals`, `AdvancedSettings` (engine-only options and `EngineAccess`) |
| `core/` | Shared value types: `Engine`, `Transport`, `PhoneKind`, `LinkState`, `LinkStage`, `LinkProblem` |
| `engine/` | `AndroidAutoSettingsProvider` + `AndroidAutoSettings` (DiAuto's preferences, read and written only in `:aa`), `WifiDirectReset`, and `EngineChrome` (UI, see below) |
| `vendor/diplay`, `vendor/diauto` | The CarPlay engine (main process) and the Android Auto engine (`:aa` process), changed only through the hooks in [VENDOR_CHANGES.md](VENDOR_CHANGES.md) |

## Public entry points

### `Connection.get(context): SessionManager`

The one `SessionManager` of the main process, wired to the real engines (`CarPlayAdapter`,
`AndroidAutoAdapter`), `DeviceEnvironment` and the settings writers. Everything that starts,
stops or watches a session goes through it. Main thread. Also on `Connection`:

- `prewarm(context)`: starts the `:aa` process ahead of time (no engine start), so a phone plugged
  in later does not wait for a cold process start.
- `onHomeShown(context)`: the host's main screen came to the front; starts the last-used phone
  wirelessly when the rules allow (see `SessionManager.onHomeShown`).
- `vehicleProfile(context)` / `setVehicleProfile(context, profile)`.
- `effectiveSettings(settings, wifi)`: the settings with the car's own hotspot filled in where
  the firmware lets it be read.

### `SessionManager`

State, both `StateFlow`s on the main thread:

- `state: StateFlow<SessionState>`: `Idle`; `Blocked(request, problems)` (pre-flight found
  `Problem`s, nothing was opened); `Active(request, path, attempt, phase)` with `phase` one of
  `Starting`, `Progress(step, stepCount, ProgressStage)`, `Retrying(attempt, FailureReason)`,
  `Connected`; `Failed(request, path, FailureReason, detail)`. `SessionState.toLinkState()`
  (`LinkStateMapping.kt`) turns it into the single `core.LinkState` CarLink's screens show.
- `pendingSwitch: StateFlow<SessionRequest?>`: a second phone was plugged in during a session;
  the host asks "Switch to <phone>?" and calls `acceptSwitch()` or `declineSwitch()`.

Triggers (each applies the rules in the class comment: one engine at a time, a new session only
after the previous stop completed, automatic starts never interrupt a running session and respect
the driver's pause):

| Call | From | Behaviour |
|---|---|---|
| `onUsbPhone(kind, phoneName, attachIntent)` | `UsbRouterActivity`, `UsbWatcher` | Starts at once when idle; during a session sets `pendingSwitch`; ignores the engines' own re-enumeration |
| `onUsbDetached()` | `UsbWatcher` | Ends a USB session whose phone stays away past a grace period, then calls `Environment.returnHome()` |
| `onBluetoothPhone(address, kind, phoneName)` | `SystemEventReceiver` → Home | Wireless start unless a session runs or automatic starts are off/paused |
| `onHomeShown(address, kind, phoneName)` | `Connection.onHomeShown` | Wireless start of the last phone from idle, at most once a minute |
| `connect(address, kind, phoneName, transport)` | the driver | Clears the pause and replaces any running session |
| `retry()` | the driver | Retries a `Blocked` or `Failed` request |
| `disconnect()` | the driver | Stops and pauses automatic starts |
| `stop()` | host | Stops whatever runs, no pause |
| `acceptSwitch()` / `declineSwitch()` | the driver | Answers `pendingSwitch` |

`SessionManager` itself is plain Kotlin: its constructor takes the adapters, a
`SessionManager.Environment`, the settings writer, a worker `Executor` and a `Handler`, so a host
can build its own instance (tests do, see `app/src/test/.../connection/SessionManagerTest.kt`).

### Detection and triggers

- USB: `UsbRouterActivity` (declared with the USB-attach filter `res/xml/carlink_usb_device_filter.xml`)
  and `UsbWatcher.get(context).start()` (call when the main process starts and whenever the
  host's main screen opens). Both classify with `PhoneClassifier.classifyUsb` and call
  `onUsbPhone`.
- Bluetooth: `SystemEventReceiver` classifies an ACL link (asking SDP once when the phone's
  services are unknown) and opens the host's main screen through `HomeLauncher`, which calls
  `onBluetoothPhone` in the foreground (Android blocks starting the engines from a background
  receiver).
- Home shown / screen wake: `Connection.onHomeShown(context)` from the host's `onResume`.

### `PairingSession`

First-time pairing in the receiver's own terms: `PairingSession(context).start()` while a pairing
screen is shown, `close()` when it leaves; `state: StateFlow<PairingSession.State>` (`Waiting(carName)`,
`Pairing(phoneName)`, `Paired(address, phoneName, kind)`, `BluetoothOff`);
`PairingSession.discoverableIntent()` asks the system to make the display visible for 300 s. The
phone always starts the pairing (a tablet cannot start the car-kit role). CarLink's own UI does
not use it yet: Home's "Pair a phone" opens the system Bluetooth settings.

### `DevicePreflight`

`DevicePreflight.check(request, path, facts, settings, wifi): List<Problem>` (pure) with
`DevicePreflight.facts(context, phoneAddress)` (reads the device) and
`DevicePreflight.requiredPermissions()` (the runtime permissions a session needs; ask for all of
them at once in onboarding). CarPlay's own checks (authentication files, VPN consent, iPhone
choice, hotspot or Same Wi-Fi details) are in `CarPlayPreflight.check`. `Connection` merges both;
each `Problem` maps to a one-tap fix in the host's UI.

### Settings

- `UnifiedSettingsStore(context).load()/save(settings)`: the settings shared by both engines
  (`connectionMethod`, car hotspot and Same Wi-Fi details, picture, interface size, day/night,
  driving side, sound routing, location, BYD directions). They are written into each engine right
  before every session (`CarPlaySettingsPolicy`, `AaSessionSettings`), so a change applies at the
  next connection.
- `CarLinkPreferences(context)`: automatic USB/Bluetooth start, start on boot, the driver's pause
  (expires after 10 minutes), the floating switcher, and UI preferences (theme, layout profile,
  interface scale; see Coupling).
- `AdvancedSettings` / `EngineAccess`: each engine's own options, read and written in the
  engine's storage (`AndroidAutoSettings` goes through the provider in `:aa`).
- `VehicleProfile`: `GENERIC` (default) or `BYD_DILINK` (detected, or chosen with
  `Connection.setVehicleProfile`). It gates every BYD-only engine feature.

## What a host app must declare

The engines' own manifests merge in; CarLink's `app/src/main/AndroidManifest.xml` then changes
them. A host needs the same entries:

- **Application**: a class extending DiAuto's `com.andrerinas.openheadunit.App` (the engine casts
  the application context to it) that overrides `initializesAndroidAutoEngine()` to return true
  only in the `:aa` process. See `CarLinkApplication`, which also installs the hooks below.
- **Receivers and provider**:
  - `entry.SystemEventReceiver`, exported, for `BOOT_COMPLETED`, `QUICKBOOT_POWERON`,
    `bluetooth.device.action.ACL_CONNECTED` and `UUID`;
  - `connection.aa.AaCommandReceiver`, not exported, `android:process=":aa"`;
  - `engine.AndroidAutoSettingsProvider`, authority `${applicationId}.androidauto.settings`, not
    exported, `android:process=":aa"`.
- **Activities**: `entry.UsbRouterActivity` (exported, `USB_DEVICE_ATTACHED` with
  `@xml/carlink_usb_device_filter`, its own task affinity, no history); the host's own main
  activity as `LAUNCHER`/`CAR_DOCK`.
- **The `:aa` process**: every DiAuto component (`AapProjectionActivity`, `UsbAttachedActivity`,
  `MainActivity`, `SettingsActivity`, `OnboardingActivity`, `AutomationActivity`, `AapService`,
  `GpsLocationService`, `DummyVpnService`, `ShizukuProvider`, the three key/media receivers) gets
  `android:process=":aa"`. `AapProjectionActivity`, `UsbAttachedActivity`, `MainActivity` and
  `AutomationActivity` are not exported and lose their intent filters; DiAuto's
  `CarKeyReceiver`, `RemoteControlReceiver` and `MediaButtonReceiver` ship disabled
  (`DeviceEnvironment` enables them per Android Auto session); DiAuto's `BootCompleteReceiver`,
  `AutoStartReceiver` and `WifiAutoStartReceiver` are removed.
- **DiPlay**: `DiPlayActivity` and `CarPlayHostActivity` not exported and without intent filters
  (no USB-attach meta-data on the host activity); `BootReceiver`, `MyCarAppService` and the
  `androidx.car.app.minCarApiLevel` meta-data removed.
- **Permissions** (beyond the engines' own): `RECEIVE_BOOT_COMPLETED`, `BLUETOOTH` (≤ 30),
  `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, `USE_FULL_SCREEN_INTENT`, `SYSTEM_ALERT_WINDOW`,
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_LOCAL_NETWORK` (target SDK 37; without it
  wireless CarPlay gets no AirPlay TCP). Runtime: `DevicePreflight.requiredPermissions()`, plus
  "display over other apps" (the engines open their projection from the background) and VPN
  consent for USB CarPlay.
- **Build**: the modules `:carplay-shared`, `:carplay-common`, `:androidauto`,
  `:androidauto-contract` from `vendor/` (see `settings.gradle.kts`), DiPlay's authentication
  files for CarPlay (README › Build), and `python3 scripts/resource_collisions.py --check` (both
  engines ship resources with clashing names).

## Engine hooks a host sets

Set once per process, before any session (CarLink does it in `CarLinkApplication.onCreate`):

| Hook | Process | CarLink's value | Where |
|---|---|---|---|
| DiAuto `HostIntegration.homeActivity`, `appName`, `serviceChannelName`, `mediaSessionTag`, `toastHandler` | both | `HomeActivity`, "CarLink", log-and-drop toasts | `CarLinkApplication.brandEngines` |
| DiPlay `CarPlayHostIntegration.homeActivity`, `appName`, `mediaSessionTag`, `toastHandler` | main | same | `CarLinkApplication.brandEngines` |
| DiAuto `stickyService`, `restartOnTaskRemoved`, `claimHiddenBottomStrip` (false), `hostOwnsUsbSwitch` (true), `onEngineEvent` | `:aa` | | `connection/aa/AaEngineHooks.install` |
| DiPlay `accessoryName`, `resetDebugLogsOnBootstrap`, `vehicleIntegrations`, `onHostState`, `waitingView` | main | | `connection/CarPlayAdapter.install` (+ `CarPlayWaitingView.install`) |
| DiPlay `CarPlayWaitingView.register(factory)` | main | Compose content of Home's connecting screen | `ui/home/CarPlayWaitingContent.register` |
| DiPlay `palette`, `chromeStyler`, `menuItems`; DiAuto `chromeStyler`, `exitMenuHomeLabel` | both | CarLink's look for the projections' own chrome | `engine/EngineChrome.install` (UI) |

DiAuto's settings for a session (mode, Wi-Fi path, poke target, auto-start flags off, car name,
picture) are written by `AaSessionSettings` before every start; DiPlay's by
`CarPlaySettingsPolicy`. A host should not write either engine's preferences itself.

## What stays in the app (UI-specific)

- `ui/*`: Home, Settings, Diagnostics, About, the CarPlay waiting content
  (`ui/home/CarPlayWaitingContent`), theme and `UiProfileDetector`.
- `switcher/*`: the floating switcher bubble and panel and the USB switch prompt over the
  projections (`SwitcherController`, `OverlayWindows`, `SwitcherRules`), and
  `ProjectionVisibility` (`:aa` → main "Android Auto is in front").
- `engine/EngineChrome`: CarLink's styling of the engines' own chrome.
- `diagnostics/*`: checks, the connection log and its file provider. `EventLog` (the session log
  mirrored to `files/logs/carlink.log`) is used by the backend too; see below.
- `CarLinkApplication`: the wiring; a host writes its own equivalent.

## Coupling to remove before extraction

Every place where `connection/`, `detect/`, `settings/` or `entry/` depend on `ui/*` or other
app-only code (checked 2026-10-09; `detect/` and `core/` have none). Not refactored yet.

| File:line | Depends on | Notes |
|---|---|---|
| `connection/DeviceEnvironment.kt:18`, `:53` | `ui.theme.UiProfileDetector.looksLikeCarDisplay` | Head unit vs tablet decides the wireless path order; move the detector (or a `headUnit` input) into the backend |
| `connection/DeviceEnvironment.kt:88` | `entry.HomeLauncher` → `ui.HomeActivity` | `returnHome()` after an unplug; make the target activity a parameter |
| `connection/DeviceEnvironment.kt:12`, `:90` | `diagnostics.EventLog` | Session log; replace with a logger interface or move `EventLog` into the backend |
| `connection/Connection.kt:47`, `:100` | `diagnostics.EventLog` | Prewarm and settings-write timings |
| `connection/Connection.kt:9`, `:114`, `:118` | `R.string.app_name` | Brand name written into DiPlay and DiAuto (car name shown to the phone); make it a parameter |
| `connection/CarPlayAdapter.kt:8`, `:141` | `R.string.app_name` | `CarPlayHostIntegration.accessoryName` |
| `connection/CarPlayWaitingView.kt:9`, `:46`, `:55`-`:58` | `R.string.carlink_carplay_waiting_*` | The placeholder waiting view's texts; move the strings with the backend or take them as parameters |
| `connection/Connection.kt:45` | `engine.AndroidAutoSettings` | Backend code in the `engine` package; move with the backend |
| `connection/aa/AaSessionSettings.kt:6`, `settings/AdvancedSettings.kt:6` | `engine.AndroidAutoSettings` | Same |
| `settings/CarLinkPreferences.kt:4`-`:5`, `:45`-`:51` | `ui.theme.ThemeMode`, `ui.theme.UiProfileMode` | UI preferences in the backend's preference class; split into backend and UI preferences |
| `entry/HomeLauncher.kt:12`, `:14`, `:30`-`:35`, `:51`-`:60` | `ui.HomeActivity` (+ its `EXTRA_AUTO_PHONE_*`), `R.string.notification_*`, `R.string.app_name`, `R.drawable.ic_stat_carlink` | Opens the host's main screen (or a full-screen notification) for boot and Bluetooth; the activity, texts and icon must come from the host |
| `entry/SystemEventReceiver.kt:33`, `:86` | `entry.HomeLauncher` | Boot and Bluetooth hand-off to the foreground goes through the host's main screen |
| `entry/UsbRouterActivity.kt:38`, `entry/UsbWatcher.kt:78` | `diagnostics.EventLog` | USB classification log lines |
| `ui/HomeActivity.kt` (`consumeAutoPhone`) | — (reverse dependency) | The Bluetooth trigger reaches `SessionManager.onBluetoothPhone` only through Home's `onNewIntent`; a host must do the same in its main activity |
| `CarLinkApplication.kt` | — | Installs the hooks above; the order (branding before `super.onCreate()`, `AaEngineHooks` only in `:aa`) must be kept by a host |

Comments that only mention `CarLinkApplication` (`connection/aa/AaEngineHooks.kt:25`,
`connection/CarPlayAdapter.kt:134`) are not code dependencies.
