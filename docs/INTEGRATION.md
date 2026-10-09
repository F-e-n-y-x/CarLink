# Reusing CarLink's connection backend

CarLink's backend (phone detection, the session layer, the two engine adapters, the engines
themselves, the unified settings and the session log) is the Android library module
`:carlink-core`. CarLink's APK (`:app`) is one host of it; a launcher on the same car stereo can
be another. This page describes the library: how to include it, the one `install` call, the host
interface, what a host observes and calls, what it must still provide itself, and a minimal
launcher.

Paths are relative to `carlink-core/src/main/java/com/carlink/core/` unless they start with
`app/`, `carlink-core/`, `vendor/` or `docs/`. The library's Kotlin packages are
`com.carlink.core.*` (`connection`, `connection.aa`, `detect`, `entry`, `settings`, `core`,
`engine`, `diagnostics`) and its resources use the same namespace (`com.carlink.core.R`). The
host keeps its own packages (CarLink's are `com.carlink.app.*`) and its own `applicationId`.

## What the library holds

| Package | Role |
|---|---|
| `connection/` | `CarLinkCore` (install), `CarLinkConfig`, `CarLinkHost`; `SessionManager` (one session, one engine at a time), `Connection` (the wired-up singleton), `WirelessPolicy`, `DevicePreflight`, `CarPlayPreflight`, `DeviceEnvironment`, `PathMemory`, `ConnectionProfiles`, `StallPlaybook`, `PairingSession`, `PicturePolicy`, `DeviceCapabilities`, the CarPlay side (`CarPlayAdapter`, `CarPlaySessionEvents`, `CarPlaySettings`, `CarPlayWaitingView`) and `LinkStateMapping` |
| `connection/aa/` | The Android Auto side: `AndroidAutoAdapter`'s peer in the `:aa` process (`AaCommandReceiver`, `AaCommandHandler`, `AaEngineHooks`, `AaEngineAccess`, `AaSessionSettings`, `AaSessionTracker`, `AaStartPolicy`, `AaProtocol`) |
| `detect/` | `PhoneClassifier` (USB and Bluetooth evidence → iPhone / Android / unknown), `KnownPhones`, `PhonesRepository` (paired phones as a flow), `UsbPhoneNames`, `HeadUnitDetector` (car display or phone/tablet) |
| `entry/` | `UsbRouterActivity` (the only USB-attach handler), `UsbWatcher` (USB attach/detach while the app runs), `SystemEventReceiver` (boot, Bluetooth ACL/SDP) |
| `settings/` | `UnifiedSettings` + `UnifiedSettingsStore`, `CarLinkPreferences` (automatic starts and the driver's pause), `VehicleProfile`, `WifiEnvironment` (+ `WifiEnvironmentReader`), `WirelessRefusals`, `AdvancedSettings` (engine-only options and `EngineAccess`) |
| `core/` | Shared value types: `Engine`, `Transport`, `PhoneKind`, `LinkState`, `LinkStage`, `LinkProblem`, `LinkNotice` |
| `engine/` | `AndroidAutoSettingsProvider` + `AndroidAutoSettings` (DiAuto's preferences, read and written only in `:aa`), `WifiDirectReset` |
| `diagnostics/` | `EventLog`: the backend's session log (ring buffer, addresses masked, mirrored to `files/logs/carlink.log`) |
| `carlink-core/src/main/AndroidManifest.xml` | The backend's components and permissions, and every override of the engines' manifests (below) |
| `vendor/diplay`, `vendor/diauto` | The CarPlay engine (main process) and the Android Auto engine (`:aa` process), exposed as `api` dependencies; changed only through the hooks in [VENDOR_CHANGES.md](VENDOR_CHANGES.md) |

The unit tests of all of this (237) are in `carlink-core/src/test/`.

## Including it

In the host's `settings.gradle.kts` (the same lines as CarLink's):

```kotlin
include(":carlink-core")
include(":carplay-shared");       project(":carplay-shared").projectDir = file("vendor/diplay/shared")
include(":carplay-common");       project(":carplay-common").projectDir = file("vendor/diplay/common")
include(":androidauto");          project(":androidauto").projectDir = file("vendor/diauto/app")
include(":androidauto-contract"); project(":androidauto-contract").projectDir = file("vendor/diauto/contract")
```

(or copy `carlink-core/` and `vendor/` into the launcher's repository with these paths). In the
host app module:

```kotlin
android {
    defaultConfig {
        minSdk = 25                                            // or higher
        missingDimensionStrategy("distribution", "github")     // DiAuto's flavor
    }
}
dependencies {
    implementation(project(":carlink-core"))   // brings both engines (api)
}
```

The build also needs the gradle property `carlink.applicationId` (DiAuto's BuildConfig reads it;
see `gradle.properties`), DiPlay's authentication files for CarPlay (README › Build), and
`python3 scripts/resource_collisions.py --check` (both engines ship resources with clashing names;
a host's own resources should use a prefix). The engines rely on reflection: ship without code
shrinking, as CarLink does.

## The one entry point: `CarLinkCore.install`

```kotlin
class LauncherApp : com.andrerinas.openheadunit.App() {      // must extend DiAuto's App
    private val engineProcess by lazy { CarLinkCore.isAndroidAutoProcess(this) }
    override fun initializesAndroidAutoEngine() = engineProcess

    override fun onCreate() {
        // Engine branding first: App.onCreate creates DiAuto's notification channels from it.
        HostIntegration.homeActivity = LauncherActivity::class.java
        HostIntegration.appName = "My Launcher"
        CarPlayHostIntegration.homeActivity = LauncherActivity::class.java
        CarPlayHostIntegration.appName = "My Launcher"
        super.onCreate()
        CarLinkCore.install(this, CarLinkConfig(
            accessoryName = "My Car",          // the car's name on the phone
            host = LauncherHost,               // see below
            waitingView = null,                // or (Context) -> View for CarPlay's waiting screen
        ))
    }
}
```

`install` must run in **every** process, after `super.onCreate()`. What it does:

| Process | Work (same order as before the extraction) |
|---|---|
| `:aa` | Names the car for Android Auto (`vehicle-display-name`, kept when the driver chose one); `AaEngineHooks.install` (no sticky or self-restarting engine, no BYD window claim off a BYD, engine events to the main process) |
| main | `EventLog.attachFile(files/logs)`; names the car for CarPlay (manufacturer/model, kept when chosen); `CarPlayAdapter.install` (accessory name, vehicle profile's BYD gate, debug-log setting, host-state fan-out, one Wi-Fi Direct identity) and the waiting view; `UsbWatcher.start()`; after 2.5 s, `Connection.prewarm` (warm `:aa`) and the decoder probe for Automatic picture |

`CarLinkCore.config` returns the installed `CarLinkConfig` (it throws when `install` was not
called).

### `CarLinkConfig`

| Field | Meaning |
|---|---|
| `accessoryName: String` | The car's name as the phone shows it: DiPlay's `accessoryName`, DiPlay's brand name, DiAuto's car name, and the iPhone's manufacturer/model for this car. CarLink passes `R.string.app_name` ("CarLink") |
| `host: CarLinkHost` | See below |
| `waitingView: ((Context) -> View)?` | What CarPlay's projection window shows until the first frame (DiPlay's `waitingView`, DIPLAY.md F2). The host window is a `ComponentActivity`, so a `ComposeView` works. CarLink passes `ui/home/CarPlayWaitingContent::create` (Home's own connecting screen). Null shows a neutral progress indicator: the library ships no user-facing copy |

### `CarLinkHost`

```kotlin
interface CarLinkHost {
    enum class OpenReason { BOOT, PHONE, UNPLUGGED }
    fun openHome(context: Context, reason: OpenReason, address: String? = null, kind: PhoneKind? = null, name: String? = null)
}
```

The backend calls it, from the main process, to bring the host's main screen to the front:

- `BOOT`: the device booted and `CarLinkPreferences.startOnBoot` is on (`SystemEventReceiver`).
  A launcher is already the home screen and can ignore it.
- `PHONE`: a known phone's Bluetooth connected while no session runs (`SystemEventReceiver`,
  debounced). Android does not let a background receiver start the engines, so the host's main
  screen, once in front, calls `Connection.get(context).onBluetoothPhone(address, kind, name)`.
  CarLink passes the three values as intent extras (`HomeActivity.consumeAutoPhone`).
- `UNPLUGGED`: a USB session's phone was unplugged (`DeviceEnvironment.returnHome`); the main
  screen replaces the dead projection.

Android 10+ blocks background activity starts unless the app may draw over other apps; CarLink's
`app/.../entry/HomeLauncher.kt` starts the activity directly when it can and otherwise posts a
full-screen notification (not for `UNPLUGGED`). A launcher can copy it.

## Driving and observing sessions

### `Connection.get(context): SessionManager`

The one `SessionManager` of the main process, wired to the real engines (`CarPlayAdapter`,
`AndroidAutoAdapter`), `DeviceEnvironment` and the settings writers. Everything that starts,
stops or watches a session goes through it. Main thread. Also on `Connection`:

- `prewarm(context)`: starts the `:aa` process ahead of time (no engine start); `install` calls it
  2.5 s after start-up.
- `onHomeShown(context)`: the host's main screen came to the front (call from `onResume`); starts
  the last-used phone wirelessly when the rules allow (see `SessionManager.onHomeShown`).
- `vehicleProfile(context)` / `setVehicleProfile(context, profile)`. Setting a different profile
  re-installs the CarPlay hooks, so it applies from the next connection without a restart.
- `effectiveSettings(settings, wifi)`: the settings with the car's own hotspot filled in where
  the firmware lets it be read.

### What a launcher observes

All `StateFlow`s, updated on the main thread:

- `state: StateFlow<SessionState>`: `Idle`; `Blocked(request, problems)` (pre-flight found
  `Problem`s, nothing was opened); `Active(request, path, attempt, phase, notice)` with `phase`
  one of `Starting`, `Progress(step, stepCount, ProgressStage)`, `Retrying(attempt,
  FailureReason)`, `Connected`; `Failed(request, path, FailureReason, detail)`.
  `SessionState.toLinkState()` (`LinkStateMapping.kt`) turns it into one `core.LinkState`
  (`stage` IDLE / WAITING / CONNECTING / CONNECTED / FAILED, `engine`, `transport`, `phoneName`,
  `step`/`stepCount`, `problem: LinkProblem?`, `notice: LinkNotice?`), which is what a status
  card shows.
- **Notices.** `Active.notice` (`Notice(kind, stage, message, action)`) says why a phase takes
  long: `PHONE_NOT_ANSWERING` (the phone has been silent for `AdaptiveDeadlines.HINT_MILLIS`, USB
  included), `SLOW` (a later phase is past that), or `RECOVERING` (the stall watchdog applied
  `action`). `toLinkState()` turns it into `LinkState.notice` (`LinkNotice(kind, recovery,
  reason)`: `LinkNoticeKind` NOT_ANSWERING / SLOW / RECOVERING / RETRYING, a `LinkRecovery` such
  as `FRESH_WIFI_DIRECT` or `TRY_CAR_HOTSPOT`, and a retry's `LinkProblem`), which the host words
  for the driver. `message` and `LinkState.detail` are log wording, not UI copy.
- `pendingSwitch: StateFlow<SessionRequest?>`: a second phone was plugged in during a session;
  the host asks "Switch to <phone>?" and calls `acceptSwitch()` or `declineSwitch()`.
- `health: StateFlow<List<PhoneHealth>>`: each known phone's connection health, most recently
  used first: attempts, successes and success rate, median time to picture, the best path
  ("USB" or a `WirelessPath` name), per-path records and the last problems (`ProblemRecord`).
- `PhonesRepository(context).phones` / `.availability` (`start()`/`stop()` with the screen):
  paired phones with their kind and connection state, and whether Bluetooth is usable.
- `EventLog.entries: StateFlow<List<EventLog.Entry>>`: the session log (also in
  `files/logs/carlink.log`; lines end `#s<n>`, the session number).

### What a launcher calls

Each applies the rules in `SessionManager`'s class comment: one engine at a time, a new session
only after the previous stop completed, automatic starts never interrupt a running session and
respect the driver's pause.

| Call | From | Behaviour |
|---|---|---|
| `onUsbPhone(kind, phoneName, attachIntent)` | `UsbRouterActivity`, `UsbWatcher` (library) | Starts at once when idle; during a session sets `pendingSwitch`; ignores the engines' own re-enumeration |
| `onUsbDetached()` | `UsbWatcher` (library) | Ends a USB session whose phone stays away past a grace period, then `CarLinkHost.openHome(UNPLUGGED)` |
| `onBluetoothPhone(address, kind, phoneName)` | the host's main screen after `openHome(PHONE)` | Wireless start unless a session runs or automatic starts are off/paused |
| `onHomeShown(address, kind, phoneName)` | `Connection.onHomeShown` | Wireless start of the last phone from idle, at most once a minute |
| `connect(address, kind, phoneName, transport)` | the driver | Clears the pause and replaces any running session |
| `retry()` | the driver | Retries a `Blocked` or `Failed` request |
| `disconnect()` | the driver | Stops and pauses automatic starts |
| `stop()` | host | Stops whatever runs, no pause |
| `acceptSwitch()` / `declineSwitch()` | the driver | Answers `pendingSwitch` |

`UsbWatcher.get(context).start()` is called by `install`; call it again when the host's main
screen opens, so a phone plugged in while the app was closed is picked up.

`SessionManager` itself is plain Kotlin: its constructor takes the adapters, a
`SessionManager.Environment`, the settings writer, a worker `Executor`, a `Handler` and a
`ConnectionProfiles` (default in memory; `ConnectionProfiles.Storage` for the host's own
storage), so a host can build its own instance (the tests do:
`carlink-core/src/test/.../connection/SessionManagerTest.kt`).

### The connection intelligence

`ConnectionProfiles` keeps, per phone (engine plus Bluetooth address, or plus the USB product
name) and per path, the last 20 attempt outcomes, the last 15 durations of each phase and of each
whole attempt, failure kinds with counts and the last 12 problems. It is used for:

- **Path order.** `rankPaths` re-orders the policy's wireless paths by this phone's record: a
  path whose last two attempts failed for path reasons moves down. Bluetooth or USB failures
  never count against a path. `WirelessPolicy` also uses `HeadUnitDetector`: on a head unit with
  known hotspot details, CarPlay tries the car hotspot first.
- **Adaptive deadlines** (`AdaptiveDeadlines`): each phase's deadline is 2.5 × this phone's
  median for it on that path, within per-phase bounds, or the phase's default before 3 samples.
- **Stall watchdog**: no phase waits silently longer than 10 s (or its deadline, if shorter); a
  phase past its deadline gets the next step of `StallPlaybook` (wake the phone again, re-send
  the USB accessory switch, retry, fresh Wi-Fi Direct group, next path, ask for a re-plug, fail).

Adapters take part through `EngineAdapter.recover(action)`; `Environment.warmUp(request, done)`
pages a wireless iPhone over Bluetooth while the previous engine stops (`BluetoothWarmup`).

### `PairingSession`

First-time pairing in the receiver's own terms: `PairingSession(context).start()` while a pairing
screen is shown, `close()` when it leaves; `state: StateFlow<PairingSession.State>`
(`Waiting(carName)`, `Pairing(phoneName)`, `Paired(address, phoneName, kind)`, `BluetoothOff`);
`PairingSession.discoverableIntent()` asks the system to make the display visible for 300 s. The
phone always starts the pairing (a tablet cannot start the car-kit role).

### `DevicePreflight`

`DevicePreflight.check(request, path, facts, settings, wifi): List<Problem>` (pure) with
`DevicePreflight.facts(context, phoneAddress)` and `DevicePreflight.requiredPermissions()` (the
runtime permissions a session needs; ask for all of them at once in onboarding). CarPlay's own
checks are in `CarPlayPreflight.check`. `Connection` merges both; each `Problem` maps to a
one-tap fix in the host's UI (`LinkProblem`).

### Settings

- `UnifiedSettingsStore(context).load()/save(settings)`: the settings shared by both engines,
  written into each engine right before every session (`CarPlaySettingsPolicy`,
  `AaSessionSettings`), so a change applies at the next connection.
- `CarLinkPreferences(context)`: automatic USB/Bluetooth start, start on boot, the driver's pause
  (expires after 10 minutes). A host's own screen preferences live in the host (CarLink's are
  `app/.../ui/UiPreferences.kt`, in the same "carlink" preferences file).
- `AdvancedSettings` / `EngineAccess`: each engine's own options, read and written in the
  engine's storage (`AndroidAutoSettings` goes through the provider in `:aa`).
- `VehicleProfile`: `GENERIC` (default) or `BYD_DILINK` (detected, or chosen with
  `Connection.setVehicleProfile`). It gates every BYD-only engine feature.

## Manifest

The library's manifest merges into the host's and declares:

- **Permissions**: `RECEIVE_BOOT_COMPLETED`, `BLUETOOTH` (≤ 30), `BLUETOOTH_CONNECT`,
  `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`, `ACCESS_LOCAL_NETWORK` (target SDK 37; without it
  wireless CarPlay gets no AirPlay TCP), plus the engines' own; features `usb.host` and
  `bluetooth` (not required).
- **Components**: `entry.UsbRouterActivity` (exported, `USB_DEVICE_ATTACHED` with
  `@xml/carlink_usb_device_filter`, task affinity `${applicationId}.usb`, no history, invisible
  theme); `entry.SystemEventReceiver` (exported; `BOOT_COMPLETED`, `QUICKBOOT_POWERON`,
  `ACL_CONNECTED`, `UUID`); `connection.aa.AaCommandReceiver` (not exported, `:aa`);
  `engine.AndroidAutoSettingsProvider` (authority `${applicationId}.androidauto.settings`, not
  exported, `:aa`).
- **The `:aa` process**: every DiAuto component (`AapProjectionActivity`, `UsbAttachedActivity`,
  `MainActivity`, `SettingsActivity`, `OnboardingActivity`, `AutomationActivity`, `AapService`,
  `GpsLocationService`, `DummyVpnService`, `ShizukuProvider`, the three key/media receivers, and
  in debug builds `HudStatusReceiver` and `StandaloneHudDemoActivity`) gets
  `android:process=":aa"`. `AapProjectionActivity`, `UsbAttachedActivity`, `MainActivity` and
  `AutomationActivity` are not exported and lose their intent filters; DiAuto's
  `CarKeyReceiver`, `RemoteControlReceiver` and `MediaButtonReceiver` ship disabled
  (`DeviceEnvironment.onEngaged` enables them per Android Auto session); DiAuto's
  `BootCompleteReceiver`, `AutoStartReceiver` and `WifiAutoStartReceiver` are removed.
- **DiPlay**: `DiPlayActivity` and `CarPlayHostActivity` not exported and without intent filters
  (no USB-attach meta-data on the host activity); `BootReceiver`, `MyCarAppService` and the
  `androidx.car.app.minCarApiLevel` meta-data removed.

Because `:carlink-core` depends on both engines, its `tools:` markers take priority over the
engines' manifests. When the library was extracted, CarLink's merged debug and release manifests
were compared before and after: with elements and attributes sorted they are identical.

When the library's packages became `com.carlink.core.*` (2026-10-10), the merged manifests changed
only in the four backend component names above (`com.carlink.app.<pkg>.X` became
`com.carlink.core.<pkg>.X`). An update over an older CarLink keeps working without aliases:

- **USB "always open with" and USB permission.** Android stores the default handler for a USB
  device per *package* (`UsbProfileGroupSettingsManager` writes `<preference package="…">`) and
  starts the first matching activity of that package; the grant is per uid. The renamed
  `UsbRouterActivity` inherits both. An `<activity-alias>` with the old name and the USB filter
  would be a second match in the same package, and the system chooser would list CarLink twice
  for a driver who has not ticked "always".
- **Boot and Bluetooth** reach `SystemEventReceiver` through its intent filters, resolved at each
  broadcast; nothing stores its class name.
- **`:aa`**: `AaCommandReceiver` is only addressed by CarLink's own explicit intents, built from
  the class at run time (the one PendingIntent, USB permission, lives for one request; an update
  restarts both processes anyway). No component of CarLink's is bound as a service. The settings
  provider's authority is `${applicationId}.androidauto.settings`, unchanged.
- **Strings kept**: the main ↔ `:aa` broadcast actions stay `com.carlink.app.action.AA2_*`
  (`connection/aa/AaProtocol`), and every `SharedPreferences` file name and key, log tag and
  stored enum name is a literal unrelated to the package.

The host declares its `Application` (extending DiAuto's `App`, with `tools:replace` for the
attributes DiAuto's manifest also sets: see `app/src/main/AndroidManifest.xml`), its own main
activity (`LAUNCHER`/`HOME`/`CAR_DOCK`), and what only its UI needs (CarLink:
`USE_FULL_SCREEN_INTENT` for HomeLauncher's notification, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
for Diagnostics, its file provider). Runtime: `DevicePreflight.requiredPermissions()`, "display
over other apps" (the engines open their projection from the background) and VPN consent for USB
CarPlay.

## Engine hooks

Set by the library (`CarLinkCore.install` and the adapters):

| Hook | Process | Where |
|---|---|---|
| DiAuto `stickyService`, `restartOnTaskRemoved`, `claimHiddenBottomStrip` (false), `hostOwnsUsbSwitch` (true), `onEngineEvent` | `:aa` | `connection/aa/AaEngineHooks.install` |
| DiAuto `usbOnlySession` | `:aa` | `connection/aa/AaCommandHandler`, per session |
| DiPlay `accessoryName`, `resetDebugLogsOnBootstrap`, `wifiP2pStableIdentity`, `vehicleIntegrations`, `onHostState`, `waitingView` | main | `connection/CarPlayAdapter.install` (+ `CarPlayWaitingView`) |
| DiPlay `WirelessStartupTimeline.milestoneListener` | main | `connection/CarPlayAdapter`, while a CarPlay session runs |

Set by the host (CarLink's values; a launcher brings its own):

| Hook | Process | CarLink | Where |
|---|---|---|---|
| DiAuto `HostIntegration.homeActivity`, `appName`, `serviceChannelName`, `mediaSessionTag`, `toastHandler` | both, before `super.onCreate()` | `HomeActivity`, "CarLink", log-and-drop toasts | `app/.../CarLinkApplication.brandEngines` |
| DiPlay `CarPlayHostIntegration.homeActivity`, `appName`, `mediaSessionTag`, `toastHandler` | main | same | `app/.../CarLinkApplication.brandEngines` |
| DiPlay `palette`, `chromeStyler`, `menuItems`; DiAuto `chromeStyler`, `exitMenuHomeLabel` (optional) | both | CarLink's look for the projections' own chrome | `app/.../engine/EngineChrome.install` |

DiAuto's settings for a session are written by `AaSessionSettings` before every start; DiPlay's by
`CarPlaySettingsPolicy`. A host should not write either engine's preferences itself.

## What stays in CarLink's app

- `ui/*`: Home, Settings, Diagnostics, About, setup, the CarPlay waiting content
  (`ui/home/CarPlayWaitingContent`), theme, `UiProfileDetector` (delegates to
  `HeadUnitDetector`) and `UiPreferences`.
- `switcher/*`: the floating switcher bubble and panel and the USB switch prompt over the
  projections, and `ProjectionVisibility` (`:aa` → main "Android Auto is in front").
- `entry/HomeLauncher`: CarLink's `CarLinkHost`.
- `engine/EngineChrome`: CarLink's styling of the engines' own chrome.
- `diagnostics/*` except `EventLog`: checks, the connection log view and its file provider.
- `CarLinkApplication`: branding, `CarLinkCore.install`, the switcher.
- Resources that are UI copy (strings, icons, themes); the library's only resources are the USB
  device filter and `UsbRouterActivity`'s invisible theme.

## Minimal launcher

```kotlin
object LauncherHost : CarLinkHost {
    override fun openHome(context: Context, reason: CarLinkHost.OpenReason, address: String?, kind: PhoneKind?, name: String?) {
        val intent = Intent(context, LauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (reason == CarLinkHost.OpenReason.PHONE && address != null && kind != null) {
            intent.putExtra("address", address).putExtra("kind", kind.name).putExtra("name", name)
        }
        runCatching { context.startActivity(intent) }   // needs "display over other apps" on Android 10+
    }
}

class LauncherActivity : ComponentActivity() {
    private val session by lazy { Connection.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handOff(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.state.collect { showStatus(it.toLinkState()) } }   // stage, phone, problem, notice
                launch { session.pendingSwitch.collect { request -> askToSwitch(request) } }
                launch { session.health.collect { showHealth(it) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handOff(intent) }

    override fun onResume() {
        super.onResume()
        UsbWatcher.get(this).start()      // a phone plugged in while the launcher was away
        Connection.onHomeShown(this)      // reconnect the last phone wirelessly when allowed
    }

    /** A Bluetooth phone reached us through LauncherHost.openHome(PHONE): start it in the foreground. */
    private fun handOff(intent: Intent) {
        val address = intent.getStringExtra("address") ?: return
        val kind = intent.getStringExtra("kind")?.let { PhoneKind.valueOf(it) } ?: return
        intent.removeExtra("address")
        session.onBluetoothPhone(address, kind, intent.getStringExtra("name"))
    }

    private fun askToSwitch(request: SessionRequest?) { /* "Switch to ${request?.phoneName}?" → session.acceptSwitch() / declineSwitch() */ }
    private fun showStatus(link: LinkState) { /* one status card; Connect → session.connect(...), Disconnect → session.disconnect() */ }
    private fun showHealth(phones: List<PhoneHealth>) { /* optional */ }
}
```

plus the Application class from "The one entry point" above, and in the manifest the
`Application` and `LauncherActivity` (`MAIN` + `HOME` + `DEFAULT`). Everything else (USB attach,
Bluetooth, boot, both engines, the `:aa` process) comes from the library's manifest.

## Coupling removed (done 2026-10-09)

Before the extraction the backend reached into CarLink's app code in these places. Each is gone;
`:carlink-core` cannot see `:app`, so the compiler now keeps it that way.

| Was | Now |
|---|---|
| `DeviceEnvironment` → `ui.theme.UiProfileDetector.looksLikeCarDisplay` (wireless path order) | `detect/HeadUnitDetector` in the library; `UiProfileDetector` delegates to it |
| `DeviceEnvironment.returnHome` → `entry.HomeLauncher` → `ui.HomeActivity` | `CarLinkHost.openHome(UNPLUGGED)`; `HomeLauncher` (app) implements `CarLinkHost` |
| `SystemEventReceiver` → `HomeLauncher` (boot, Bluetooth hand-off) | `CarLinkHost.openHome(BOOT / PHONE)` |
| `HomeLauncher` → `HomeActivity`, `R.string.notification_*`, `R.drawable.ic_stat_carlink` | `HomeLauncher` stays in the app |
| `Connection`, `CarPlayAdapter` → `R.string.app_name` | `CarLinkConfig.accessoryName` |
| `CarPlayWaitingView` → `R.string.carlink_carplay_waiting_*` | Host-provided `CarLinkConfig.waitingView`; the fallback is a progress indicator without text, and the four strings are gone |
| `Connection`, `DeviceEnvironment`, `UsbRouterActivity`, `UsbWatcher` → `diagnostics.EventLog` | `EventLog` moved into the library as its logger; the app's Diagnostics reads `EventLog.entries` |
| `Connection`, `AaSessionSettings`, `AdvancedSettings` → `engine.AndroidAutoSettings` | `engine/AndroidAutoSettingsProvider.kt` moved into the library (`EngineChrome` stays) |
| `CarLinkPreferences` → `ui.theme.ThemeMode`, `UiProfileMode`, `RenderingMode` | Split: `CarLinkPreferences` (library) keeps the connection preferences; `ui.UiPreferences` (app) the screen ones, same file |
| `HomeActivity.consumeAutoPhone` (reverse dependency) | Documented in `CarLinkHost.OpenReason.PHONE`; a host does the same in its main activity |
| `CarLinkApplication` wiring order | `CarLinkCore.install` does the backend part in the same order; the host keeps branding before `super.onCreate()` |
