# Changes to the vendored engines

CarLink imports both engines with `git subtree`, keeping their history:

| Path | Upstream | Engine |
|---|---|---|
| `vendor/diplay` | https://github.com/shihabal3amri/DiPlay (fork: F-e-n-y-x/DiPlay) | CarPlay, main process |
| `vendor/diauto` | https://github.com/shihabal3amri/DiAuto (fork: F-e-n-y-x/DiAuto) | Android Auto, `:aa` process |

Update an engine with:

```sh
git subtree pull --prefix=vendor/diplay https://github.com/shihabal3amri/DiPlay main
git subtree pull --prefix=vendor/diauto https://github.com/shihabal3amri/DiAuto main
```

Every edit inside `vendor/` is listed below and marked `CarLink` in the code, so a conflicting
pull is easy to resolve. Keep this list current. After a pull, run
`python3 scripts/resource_collisions.py` and the checks in the README.

## DiPlay (`vendor/diplay`)

| File | Change | Why |
|---|---|---|
| `common/build.gradle.kts` | `project(":shared")` → `project(":carplay-shared")` | Module renamed in CarLink's settings. |
| `shared/src/main/jni/Android.mk`, `shared/.../network/LegacyHotspotRadio.kt` | Native library `local_hotspot_radio` → `diplay_hotspot_radio` | DiAuto ships a library with the same file name (same C source, different JNI package). |
| `shared/.../hud/BydStandaloneHudOutput.kt`, `shared/.../hud/BydStarterBridge.kt` | Added `com.carlink.app` / `com.carlink.app.hudtest` to the package allowlists | These are the authors' own safety gates; the firmware side is still verified by signature and fingerprint. |
| `shared/.../orchestration/CarPlayStatusBus.kt` (new) and two lines in `CarPlayController.kt` | Publish each status and the close | CarLink's Home and dispatcher show CarPlay progress outside `CarPlayHostActivity`. |
| `common/.../DiPlayActivity.kt` | `EXTRA_CARLINK_CONNECT` + `consumeCarLinkConnectIntent()` | CarLink starts CarPlay through DiPlay's own `connect()` pre-flight (setup errors, hotspot setup, iPhone choice, notification permission). |
| `common/.../CarPlayHostIntegration.kt` (new); `CarPlayHostActivity.kt` (home button, auth failure, resolution-fallback toast), `NavigationWidget.kt`, `DiPlaySessionService.kt` (notification title), `CarPlayMediaKeys.kt` (MediaSession tag) | `homeActivity`, `appName`, `mediaSessionTag`, `toastHandler`/`showToast` hooks, defaulting to DiPlay's own values | The projection returns to CarLink's Home; the driver sees "CarLink", not "DiPlay"; CarLink logs and drops the projection's informational toast and shows status itself. |
| `common/.../CarPlayHostIntegration.kt`; `DiPlayPalette.kt` (public, `of()` consults the hook); `CarPlayHostActivity.kt` (`styleChrome` calls after the waiting screen, menu, side panel, safe-area editor and picture panel are built or repainted; `menuItem(...)` tags on the in-session menu's sections; the menu's DiPlay-settings button says "Back to CarLink" when a host replaced `homeActivity`; a hidden Wi-Fi method is not re-validated on save) | `palette`, `chromeStyler` (with `Chrome`), `menuItems` (with `MenuItem`) hooks, defaulting to DiPlay's own palette, no styling and every section | The projection's chrome looks like CarLink (Graphite and Ion, Inter, glass, pills, CarLink's mark), styled by `app/.../engine/EngineChrome.kt`. The in-session menu hides what CarLink Settings owns or rewrites before every session (wireless, Wi-Fi method, location, driving side, resolution, frame rate, size, dock, HEVC, system bars, debug logs, gesture) and what only standalone DiPlay needs (boot auto-start, language, "Exit application", which kills CarLink's whole process); hidden sections are still built and keep their saved values. |

Not built: `:mobile` (replaced by CarLink's `:app`), `:automotive`, `samples/*`.

## DiAuto (`vendor/diauto`)

| File | Change | Why |
|---|---|---|
| `app/build.gradle.kts` | Application → Android library on AGP 9 built-in Kotlin. No kapt (Glide/lifecycle processors were unused). `BuildConfig.APPLICATION_ID/VERSION_*` provided as fields. Playstore flavor disabled. Conscrypt 2.6.1 only (minSdk 25). ABIs arm64-v8a, armeabi-v7a, x86_64. | One APK, one toolchain (DiPlay's AGP 9.3 / Kotlin 2.2). |
| `contract/build.gradle.kts` | AGP 9 built-in Kotlin, minSdk 25 | Same. |
| `app/src/main/java/.../App.kt` | `open class`, `initializesAndroidAutoEngine()` guard | `CarLinkApplication` extends `App` (the engine casts the application context to it) and skips engine startup outside `:aa`. |
| `app/src/main/java/.../HostIntegration.kt` (new); `aap/AapService.kt` (5 sites), `connection/WifiDirectManager.kt` (1 site) | `Intent(…, MainActivity)` → `HostIntegration.homeActivity` | Notification taps, boot launch and Wi-Fi Direct return open CarLink's Home. |
| `app/src/main/java/.../hud/BydStandaloneHudOutput.kt`, `hud/BydStarterBridge.kt` | Same allowlist additions as DiPlay | Same. |
| `HostIntegration.kt`; `App.kt` (service channel name), `aap/AapService.kt` (service notification title, MediaSession tag), `utils/ToastUtils.kt`, `connection/LocalHotspotCredentialsProvider.kt` (its one raw toast now goes through `ToastUtils`, forced as before) | `appName`, `serviceChannelName`, `mediaSessionTag`, `toastHandler` hooks, defaulting to DiAuto's own values | Notifications and the media session say CarLink. Every engine toast reaches CarLink first: CarLink logs it (`CarLink-EngineToast`) and drops it unless the engine forces it past its own "toasts off" setting. |
| `aap/BluetoothWakePolicy.kt` (`gatewayLinkBlocksPoke`), `utils/BluetoothHelper.kt` (`handsFreeLinkState(context, pokedAddress)` + `headsetPeers`), `connection/NativeAaHandshakeManager.kt` (passes the poked phone; poke failure log) | A hands-free link in the *gateway* role (`BluetoothProfile.HEADSET`) stands a wake poke down only when its peer is the poked phone; unreadable peers keep upstream's "skip". Poke failures are logged at INFO with the exception type. Tests in `BluetoothWakePolicyTest`. | A tablet or phone used as the car display is the audio gateway of its own earbuds. Upstream counted that link as "the head unit already holds a hands-free link" and skipped every poke, so wireless Android Auto only started once the phone's Bluetooth connected by itself. The client role (`HEADSET_CLIENT`, the head unit as the phone's hands-free) stays profile-wide because its per-device state is system-only. |
| `HostIntegration.kt`; `aap/AapProjectionActivity.kt` (`styleChrome` calls for the loading/reconnecting overlay, the renderer banner and the exit menu; `ExitOption.title`; the exit menu's Quick Settings entry becomes `exitMenuHomeLabel`, which opens `homeActivity`) | `chromeStyler` (with `Chrome`), `exitMenuHomeLabel` hooks, defaulting to no styling and Quick Settings | The Android Auto chrome looks like CarLink; the exit menu no longer opens DiAuto's own settings screen inside the projection, but CarLink ("Open CarLink"). |
| `HostIntegration.kt` (`onPhoneWifiJoinFailed`); `aap/AapService.kt` (`refreshHotspotCredentialsAfterJoinFailure` calls it) | Host hook called each time the phone reports it could not join the access point it was handed (Native AA over a hotspot), defaulting to none | DiAuto re-offers the same network forever, so a wrong or missing Wi-Fi password left Home on "Connecting". CarLink (`engine/AndroidAutoWifiFallback.kt`) moves the attempt to Wi-Fi Direct after two refusals and remembers the refused shared network. |

## Engine wording changed only through CarLink's resources

App resources override library resources of the same name, so these need no engine edit:
`app/src/main/res/values/carlink_engine_overrides.xml` rewords the engine strings the driver can
see during a session (CarPlay picture messages, dashboard-map placeholder, wheel-key accessibility
service, Android Auto service and boot-loop notifications), and copies of `ic_stat_carlink`
replace DiAuto's `ic_stat_aa` (`drawable-anydpi-v24`) and DiPlay's `ic_diplay_notification`.
`carlink_engine_theme.xml` recolors DiAuto's `da_*` colors (its dialog theme, used by the
projection's exit menu) to Graphite and Ion, `drawable/bg.xml` replaces DiAuto's blue window
background, and `layout/dialog_exit_item.xml` gives the exit menu Inter and car-sized rows (same ids;
re-check after a DiAuto pull). The CarPlay menu title, the side-panel button and the Android Auto
exit-menu labels are reworded there too. Each name is in `scripts/resource_collisions_allowlist.txt`. Strings only DiPlay's or DiAuto's own
screens use are left alone. CarLink also sets DiAuto's `vehicle-display-name` (the car name the
phone shows) to "CarLink" when the driver never chose one. Likewise it sets DiPlay's CarPlay manufacturer and model (what
the iPhone shows for the car) to "CarLink" while they are still DiPlay's default "DiPlay".

## Behaviour changed only through CarLink's manifest (`app/src/main/AndroidManifest.xml`)

- CarLink's `HomeActivity` is the only launcher. `UsbRouterActivity` is the only USB-attach handler.
  `SystemEventReceiver` replaces DiPlay's `BootReceiver` and DiAuto's boot, Bluetooth and Wi-Fi
  auto-start receivers.
- DiAuto's `MainActivity` no longer handles `geo:`/navigation links: opening it starts `AapService`,
  which must not happen while CarPlay runs.
- Every DiAuto component runs in `:aa`, so `System.exit` on disconnect, `AppComponent`
  singletons, car mode and Conscrypt stay out of the CarPlay process.
- DiAuto's `CarKeyReceiver`, `RemoteControlReceiver` and `MediaButtonReceiver` ship disabled.
  `EngineCoordinator` enables them only while Android Auto is engaged: `CarKeyReceiver` aborts
  every ordered key broadcast and would otherwise take the steering-wheel keys from CarPlay.
