# DiPlay dissection: engineering reference for CarLink

> **Note (2026-10-09):** the CarLink v1 classes this document cites as they were when it was
> written (`EngineCoordinator`, `CarPlayEngine`, `engine/CarPlayPreflight`, `AndroidAutoEngine`,
> `AndroidAutoCommandReceiver`, `AndroidAutoStateReporter`, `AndroidAutoWifiFallback`,
> `EngineSettingsApplier`, `EngineMappings`/`WirelessPathSelector`) and the hooks
> `EXTRA_CARLINK_CONNECT` and `onPhoneWifiJoinFailed` have been removed; the v2 replacements are in
> `app/.../connection/` ([design/REBUILD_V2.md](../../design/REBUILD_V2.md)). Engine findings are
> unaffected.

DiPlay version vendored: **0.2.15** (`vendor/diplay/mobile/build.gradle.kts:21`, `CHANGELOG.md:1`).
Package `com.shilapi.xcertplay`. Paths below are relative to `vendor/diplay/` unless they start
with `app/` (CarLink). `CarPlayController.kt` means
`shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`,
`CarPlayHostActivity.kt` / `DiPlayActivity.kt` mean the files in
`common/src/main/java/com/shilapi/xcertplay/`.

How claims are marked:

- **[V]**: I checked it in code or a doc, and give file:line.
- **[I]**: my inference, not checked on a device.
- **[M] / [U] / [C]**: something said upstream by the maintainer (shihabal3amri), a user, or a contributor with merged PRs.

Issue numbers refer to `shihabal3amri/DiPlay`. Upstream has Discussions turned off, and the fork
F-e-n-y-x/DiPlay has Issues turned off.

---

## 0. Executive summary

1. **A CarPlay session lives inside an Activity. There is no headless way to start one.**
   - `CarPlayHostActivity` builds the `CarPlayController`, owns the projection surface and owns the retry loop [V].
   - `DiPlaySessionService` only provides the foreground notification and its "Disconnect" action [V].
   - So whatever a host does, the driver sees `CarPlayHostActivity` from "Connect" until teardown, including its **waiting screen**.
2. **That waiting screen is the "DiPlay screen" the driver sees.** CarLink opens `CarPlayHostActivity` at once (`app/.../engine/CarPlayEngine.kt:172-187`). The Activity:
   - shows its WAITING chrome;
   - asks for its own runtime permissions and VPN consent;
   - shows DiPlay's per-stage text (`CarPlayStatus.describe()`, `CarPlayHostActivity.kt:5099`).
3. **Wireless CarPlay works the same way on every Wi-Fi backend:**
   1. bring up a network;
   2. bind the AirPlay listener on it;
   3. advertise `_airplay._tcp` with JmDNS on that network interface;
   4. the receiver opens an **RFCOMM client** socket to the bonded iPhone's iAP2 UUID;
   5. iAP2 identification and MFi authentication;
   6. send `0x5703` (Wi-Fi SSID, passphrase, security, channel, optional AP BSSID);
   7. send `0x4301` StartSession (IP, port, device id, public key);
   8. wait **30 s** for the iPhone's first AirPlay TCP connection (`FIRST_TCP_TIMEOUT`).
4. **The user's symptom, "authenticated over Bluetooth, never any Bonjour or TCP", is the most common failure reported upstream.** It is always a network-reachability problem (address family, client isolation, multicast filtering, or the iPhone not on the same network/BSSID), never an authentication problem [M].
   - Existing Wi-Fi on a WPA2/WPA3-mixed router is **not hardware-validated** upstream (`docs/EXISTING_WIFI.md:66-67`).
   - Existing Wi-Fi always tells the iPhone the security is `WPA_WPA2` (`ExistingWifiManager.kt:152-153`).
5. **CarLink's main faults are in how it reads DiPlay's status:**
   - It treats every `CarPlayStatus.Failed` as final, but DiPlay retries five times on its own (`CarPlayHostActivity.kt:4559-4597`).
   - On Same Wi-Fi, CarLink tears down at the first Failed, records the network as permanently "refused", and moves to another path (`app/.../EngineCoordinator.kt:253-265`). It does this even when the failure was Bluetooth, not Wi-Fi.
   - CarLink defaults the BYD navigation output **on** (`app/.../UnifiedSettings.kt:24,56` → `BydOutputSettings.setEnabled`).
   - It leaves the iPhone-visible OEM label at "BYD" (`AirPlayPersistence.kt:113`).
   - It targets SDK 37 without `ACCESS_LOCAL_NETWORK`, which upstream #425 shows silently breaks IPv4 on Android 17.
   - It reads `CarPlayStatusBus.current == null` as "no session". But DiPlay publishes `null` at the start of every internal restart (`CarPlayController.kt:604`). So Home flashes Idle on each retry, stop and start requests can be ignored, and a restart can race the old session's teardown (§10, F13 and F14).

---

## 1. Architecture map

### 1.1 Modules

| Module | Role | Built in CarLink? |
|---|---|---|
| `shared` (`:carplay-shared` in CarLink) | Engine core: iAP2, MFi, USB (usbmux/lockdown/NCM), Wi-Fi backends, Bonjour, AirPlay/RTSP/crypto, media sink, `CarPlayController`, BYD output bridges (`hud/`) | Yes |
| `common` | Android shell around the engine: `CarPlayHostActivity` (projection plus its chrome and the session owner), `DiPlayActivity` (standalone app UI), settings persistence, session service, cluster/map features, accessibility services | Yes |
| `mobile` | Standalone app wrapper: manifest additions (`PhoneBluetoothReceiver`, launcher icon, `BootReceiver` enable) | **No** (replaced by `:app`) |
| `automotive`, `samples/*` | AAOS build and launcher/map-host samples | No |

### 1.2 Components in the merged manifest

Sources: `common/src/main/AndroidManifest.xml`, `shared/src/main/AndroidManifest.xml`,
`mobile/src/main/AndroidManifest.xml` [V]. The "CarLink" column describes
`app/src/main/AndroidManifest.xml:122-132` [V].

| Component | Kind | What it does | Core or standalone UI | CarLink |
|---|---|---|---|---|
| `CarPlayHostActivity` | Activity, singleTask, own task affinity `.carplay`, USB_DEVICE_ATTACHED filter | **Owns the session.** Runs the permission/VPN prerequisites, builds `CarPlayController`, shows the waiting screen, then the projection SurfaceView/TextureView. Runs the reconnect/backoff loop, the in-session menu, the side panel, cluster outputs | **Core (required)** | Kept; not exported; intent filter and USB meta-data removed |
| `DiPlayActivity` | Activity, LAUNCHER | Standalone app home, settings, setup guide, `connect()` pre-flight, "Choose iPhone" dialog, diagnostics, updates | Standalone UI | Kept; not exported; no filters. Never opened by CarLink's CarPlay path |
| `MainActivity` | Activity | Board I2C MFi self-check diagnostic (`MainActivity.kt:31-60`) | Dev tool | Kept (unused) |
| `CarPlayVideoActivity` | Activity | "Video in car" ExoPlayer player for iPhone video while parked | Optional feature | Kept |
| `ImageCropActivity` | Activity | Crops the custom car-button icon | Standalone UI | Kept |
| `AdbClusterActivity` | Activity, exported, embeddable | DiLink 4 dashboard-cluster map surface launched over local ADB | BYD-specific | Kept (see §12) |
| `DiPlaySessionService` | FGS `connectedDevice\|microphone\|location` | Notification "CarPlay connection running" plus Disconnect; `ACTION_STOP` → `CarPlayBackgroundSession.stop()`; task removed → stop (`DiPlaySessionService.kt:19-62`) | **Core (required)** | Kept |
| `CarPlayVpnService` | VpnService | Hosts the AirPlay listener. For **wired**, also creates a TUN with a link-local IPv6 route that bridges NCM (`network/CarPlayVpnService.kt:95-104`). Wireless binds it for the listener only (`CarPlayController.kt:2464-2473`) | **Core (required)** | Kept |
| `MapEmbedService` | Service, **exported**, action `com.shihab.diplay.action.EMBED_MAP` | Streams the dashboard map into third-party launchers | Optional | Kept. [I] An exported surface CarLink may not want |
| `WheelKeyService` | AccessibilityService | Steering-wheel keys for map zoom and call keys (BYD key codes) | Optional / BYD | Kept |
| `UsbAutoConfirmService` | AccessibilityService | Auto-ticks "Always" and taps OK on the system USB-permission dialog (`UsbAutoConfirmService.kt:1-40`) | Optional | Kept |
| `MyCarAppService` | androidx.car.app service | Placeholder POI car app | Unused | Removed |
| `NavigationWidget` | AppWidget receiver | Turn-by-turn widget | Optional | Kept |
| `BootReceiver` | BOOT_COMPLETED | If `auto_start_on_boot` is set, opens `DiPlayActivity` (`BootReceiver.kt:85-106`) | Standalone | **Removed**; `SystemEventReceiver` replaces it |
| `PhoneBluetoothReceiver` | ACL_CONNECTED (**mobile manifest only**) | If `connect_on_phone_bluetooth` is set and the ACL is from the selected iPhone, opens `DiPlayActivity` with `EXTRA_AUTO_CONNECT`, 15 s debounce (`PhoneBluetoothReceiver.kt:14-49`) | Standalone | Not present; CarLink's `SystemEventReceiver` replaces it |
| `DiagnosticReportProvider`, `UpdateApkProvider` | FileProviders | Report sharing; in-app APK update | Standalone | Kept |

### 1.3 What a session strictly needs, and why

1. **A visible, laid-out Activity window.**
   - `maybeStartCarPlay()` returns early until `activeDisplaySize` is known (`CarPlayHostActivity.kt:4541`).
   - The negotiated CarPlay resolution is derived from that window, and the media sink attaches to its surface (`CarPlayHostActivity.kt:4213-4218`).
   - [I] An Activity started behind another app is never laid out, so the controller never starts.
2. **Runtime permissions**, requested by the host Activity itself (`CarPlayHostActivity.kt:696-771`):
   - Wireless on API 33+: `BLUETOOTH_CONNECT` and `NEARBY_WIFI_DEVICES`.
   - Wireless on API 31-32: `BLUETOOTH_CONNECT` and location.
   - Existing Wi-Fi: only `BLUETOOTH_CONNECT`.
   - `RECORD_AUDIO`: asked once, and the session waits until it is answered (`microphonePermissionResolved`, `CarPlayHostActivity.kt:622,4547`).
   - `ACCESS_FINE_LOCATION`: only when location reporting is on.
3. **VPN consent, wired only.**
   - `requestStartupPrerequisites()` calls `requestVpnConsent()` only when `!wirelessEnabled` (`CarPlayHostActivity.kt:696-705`).
   - `transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady` (`:4542`).
   - The wired AirPlay path needs the TUN, so consent is required. The system dialog appears once per install.
4. **MFi identity.** `DiPlayBootstrap.ensure()` installs `assets/offline-mfi/{identity.pk8,certificate.p7b}` into `noBackupFilesDir` (`DiPlayBootstrap.kt:14-42`).
   - If it fails, the host Activity sends the driver to `CarPlayHostIntegration.homeIntent` and finishes (`CarPlayHostActivity.kt:574-577`).
   - No network and no UI is involved; there is "no remote fallback".
   - `RemoteMfiAuthenticationClient` and `MfiTarget` I2C/CH341 are alternative targets; default `MfiTarget.LOCAL` (`AirPlayPersistence.kt:260-264`).
5. **USB permission, wired only.** The controller asks through `UsbManager.requestPermission` (status `RequestingIphonePermission`, `CarPlayController.kt:1728`) and polls for up to 120 s (`:2667`).
   - [I] When the app was launched by a USB_DEVICE_ATTACHED intent for that device, Android has already granted it. CarLink's `UsbRouterActivity` is that receiver, in the same package.
6. **Foreground service start** (`ContextCompat.startForegroundService`, `CarPlayHostActivity.kt:4264`). If this throws, the attempt shuts down with "could not start CarPlay" (`:4266-4270`).

---

## 2. Session lifecycle

### 2.1 Ownership

| Step | Owner | Evidence |
|---|---|---|
| Trigger (button, USB, BT, boot) | `DiPlayActivity` / `BootReceiver` / `PhoneBluetoothReceiver` / host USB filter. **In CarLink:** `EngineCoordinator` | `DiPlayActivity.kt:4408-4440`, `BootReceiver.kt`, `PhoneBluetoothReceiver.kt` |
| Pre-flight | `DiPlayActivity.connect()`. **In CarLink:** `CarPlayPreflight` | below |
| Prerequisites (permissions, VPN) | `CarPlayHostActivity` | `:696-771` |
| Controller creation and start | `CarPlayHostActivity.startCarPlay()` | `:4167-4271` |
| Protocol state machine | `CarPlayController` (one worker executor; status callbacks on main) | `CarPlayController.kt:146-175` |
| Keep alive in background | `CarPlayBackgroundSession` (process-wide hand-off) plus `DiPlaySessionService` FGS | `CarPlayHostActivity.kt:5184-5255`, `DiPlaySessionService.kt` |
| Retry / backoff | `CarPlayHostActivity.reconnectAfterLoss()` and `restartCarPlay()` | `:4559-4640` |
| Teardown | `CarPlayBackgroundSession.stop()` → stored stop action → `shutdown(terminateProcess=false)` + `finish()` | `:4257-4261`, `:4776-4805` |

`onStop()` keeps the session running: "The controller, USB/iAP2 link, and VPN attachment
intentionally outlive the UI" (`CarPlayHostActivity.kt:1279-1287`) [V]. A new host instance
adopts a running session through `adoptBackgroundSession()` (`:4527-4531`).

### 2.2 `CarPlayStatus` (all values, `CarPlayController.kt:105-136`) [V]

| Phase | Status | Emitted at (CarPlayController.kt) |
|---|---|---|
| MFi | `DiscoveringMfi`, `WaitingForMfi`, `RequestingMfiPermission`, `MfiReady` | 764, 1058, 876, 802/826/852/959 |
| Wireless | `StartingHotspot` | 1101 |
| | `HotspotReady(ssid, band, channel, bssid, address, backend)` | 1204 |
| | `WaitingForPairedIphone` | 1213 |
| | `AttachingNetwork` (bind the AirPlay listener in the VPN service) | 1229 |
| | `ConnectingBluetooth` (RFCOMM) | 1291 |
| | `RunningWireless` (iAP2 bootstrap running) | 1377 |
| | `WirelessActive` (AirPlay session up) | 1610 |
| | `WirelessActiveFallback` (session rendered but the iAP2 tunnel handoff fell back) | 1666 |
| USB | `DiscoveringIphone` | 1692 |
| | `WaitingForIphone` | 1700 |
| | `RequestingIphonePermission` | 1728 |
| | `SelectingConfiguration` | 1810/1849 |
| | `WaitingForReenumeration` | 1814 |
| | `OpeningDataPaths` | 1850 |
| | `Pairing` (lockdown / Trust) | 1893 |
| | `ConnectingControl` | 1904 |
| | `AttachingNetwork` (NCM) | 2418 |
| | `RunningControl` | 2024 |
| End | `ControlEnded` | 1433, 1496, 2042 |
| | `Failed(message, wifiResetRequired, startupFailure)` | 896, 915, 1498, 1504, 1774, 1793, 2044, 2439, 2455, 2547 |

`CarPlayStatusBus` (a CarLink addition) republishes each status, and `null` on close.

### 2.3 Timeouts and retries [V]

`CarPlayController.kt:2660-2672`:

| Constant | Value |
|---|---|
| `HOTSPOT_START_TIMEOUT` | 60 s |
| `WIFI_P2P_START_TIMEOUT` | 20 s |
| `PAIR_TIMEOUT` (Trust prompt) | 5 min |
| `VPN_CONNECT_TIMEOUT` | 10 s |
| `CONTROL_LOOP_TIMEOUT` | 5 min (24 h with location) |
| `PERMISSION_POLL_TIMEOUT` | 120 s (poll every 500 ms) |
| `DEVICE_AVAILABILITY_POLL` | 2 s |
| `WIRELESS_HANDOFF_TIMEOUT` | 45 s |
| `RFCOMM_CONNECT_TIMEOUT` | 15 s |
| `MAXIMUM_REENUMERATION_ATTEMPTS` | 2 |

`WirelessStartupPolicy` (`network/WirelessStartupPolicy.kt:5-14`):

| Constant | Value |
|---|---|
| `HOTSPOT_READY` | 15 s |
| `FIRST_TCP` | **30 s** |
| `MAX_STARTUP_RETRIES` | 5 |
| `STABLE_SESSION` | 60 s (resets the retry budget once a session has rendered that long) |

Failure categories: `HOTSPOT_NOT_READY`, `FIRST_TCP_TIMEOUT`, `HOTSPOT_CONFIGURATION`.

The host's recovery loop (`CarPlayHostActivity.kt:4064-4087, 4559-4640`):

- **`Failed` with `wifiResetRequired`:** show the "Reset CarPlay Wi-Fi" button; no automatic retry.
- **Any other `Failed`:** `reconnectAfterLoss()`:
  - Startup failures use `WirelessStartupRetryBudget`: 2, 4, 8, 16, 30 s, at most 5 tries (`WirelessStartupRetryBudget.kt:11-14`).
  - `HOTSPOT_CONFIGURATION` is never retried. It shows the message and stops.
  - Other losses back off from `RECONNECT_DELAY` 2 s, doubling to a 30 s cap; the iAP tunnel case waits 15 s (`:4575-4581`, `:5147-5149`).
  - When the budget is exhausted, it shows a "Retry" button and the text `wireless_startup_retries_exhausted`.
- **`restartCarPlay()`:** closes the controller (4 s wait) and builds a new one at the same window size.
- **Wireless network change (Existing Wi-Fi):** `ExistingWifiManager` invalidates and calls `restartWireless()` (`ExistingWifiManager.kt` `invalidate`; `CarPlayController.kt:2119-2122`).
- **USB attach while wireless:** switches to wired inside the host (`CarPlayHostActivity.kt:773-791`).

### 2.4 Triggers

- **Manual (standalone):** `DiPlayActivity.connect(wireless)` → `openProjection()` (`DiPlayActivity.kt:4408-4440`).
- **USB attach (standalone):** the system opens `CarPlayHostActivity` directly. The Apple vendor-id filter is `common/src/main/res/xml/usb_device_filter.xml` (`vendor-id="1452"`). `isIphoneUsbAttachment` forces `wireless_enabled=false` (`:571-573, 792-797`).
- **Bluetooth (standalone):** `PhoneBluetoothReceiver` → `DiPlayActivity` + `EXTRA_AUTO_CONNECT` → `connect(true)` (`DiPlayActivity.kt:346-352, 393-398`). Off by default.
- **Boot (standalone):** `BootReceiver` → `DiPlayActivity` (no connect). Off by default (`AirPlayPersistence.kt:422-424`).
  - `DefaultConnectionMode` and `auto_connect` in the `diplay` prefs decide whether DiPlayActivity connects by itself (`DiPlayBootstrap.kt:51-80`).
- **Background launch limits:** the standalone app needs the overlay permission to be launched from a receiver on Android 10+ (`CarHotspotSetup.kt:21-25`, `BOOT_LAUNCH("SYSTEM_ALERT_WINDOW")`).

### 2.5 `DiPlayActivity.connect()`: what a host must reproduce [V] (`DiPlayActivity.kt:4408-4440`)

1. `startupHotspotCancelled = true`.
2. Wireless and a pending car-hotspot setup → stop and open Connection setup.
3. `setupError` (from `DiPlayBootstrap.ensure`, `:327`) → toast and stop. *(CarLink: `CarPlayHostIntegration.authenticationError`.)*
4. Wireless and mode `MANUAL` and invalid SSID/passphrase → open setup. *(CarLink: `CAR_HOTSPOT_DETAILS_MISSING`.)*
5. Wireless and `carHotspotOff()` (`:2121`) → `carHotspotOffDialog()`, which offers to open the system tethering settings or auto-start (`WRITE_SETTINGS` / BYD ADB). *(CarLink: `CAR_HOTSPOT_OFF`.)*
6. Wireless and no `phone_address` → `choosePhone()` (`:4441-4472`):
   - Asks for `BLUETOOTH_CONNECT`.
   - Requires Bluetooth on.
   - Lists **bonded** devices.
   - Saves the address and name to `diplay` prefs `phone_address` / `phone_name`.
7. API 33+ → ask `POST_NOTIFICATIONS` **once** (`notification_asked`).
8. `AirPlayPersistence.saveWirelessEnabled(wireless)`.
9. If a session exists, `CarPlayBackgroundSession.stop{}` first.
10. `startActivity(CarPlayHostActivity, FLAG_ACTIVITY_REORDER_TO_FRONT)`. **No extras.** The host reads everything from prefs in `loadConnectionSettings()` (`CarPlayHostActivity.kt:679-694`).

Not part of `connect()`, though a reader might expect it:

- Existing Wi-Fi details are **not** checked there.
- Permissions, VPN and location are handled by the host.
- `EXTRA_CARLINK_CONNECT` / `consumeCarLinkConnectIntent()` (`DiPlayActivity.kt:384-391, 5210-5211`) is a CarLink hook that routes a request through `connect()`. **CarLink no longer uses it.** `CarPlayEngine` opens the host directly, so `docs/VENDOR_CHANGES.md` describes a path that is now dead code [V].

---

## 3. Wireless in depth

### 3.1 Sequence shared by every backend [V] (`CarPlayController.kt:1123-1420`)

1. **MFi ready.** `mfiSession` is required (`:1158`).
2. **Network up.** `startWirelessHotspot(generation)` picks the manager by `wireless_hotspot_mode` (`:2110-2131`). The result is `WirelessHotspotInfo`: ssid, passphrase, security, channel, interface, host address(es), band, AP BSSID.
3. Pause Wi-Fi scans: BYD ADB, not for Same LAN (`:1163`, `:2167-2170`; §12).
4. **Host address.** IPv4 is preferred; a scoped link-local IPv6 is the fallback (`network/HotspotAddressPolicy.kt:8-19`, from 0.2.13, PR #283).
   - An unscoped IPv6 address is rejected (`CarPlayController.kt:1172-1178`).
   - *`docs/EXISTING_WIFI.md:31` still says "prefers a scoped link-local IPv6". That is out of date: Existing Wi-Fi uses the same `wirelessHostAddress()` policy (`network/WirelessHostAddress.kt:7-13`).*
5. Status `HotspotReady`, then `WaitingForPairedIphone`.
6. **Bluetooth target.** `selectWirelessBluetoothDevice()` (`:2172-2211`):
   - The **selected address must be bonded** ("The selected iPhone is no longer paired. Choose it again in DiPlay.").
   - With no selection it falls back to the single connected or bonded device named "iPhone", and errors if there is more than one.
7. `AttachingNetwork`. Bind `CarPlayVpnService` (10 s) and `attachWireless(bindAddress, additionalBindAddresses)`, so there is a listener per address family. The port is 7000 with fallback (`AirPlayPortSelector`; EADDRINUSE fix for #361) (`:1229-1264`).
8. **Bonjour.** `CarPlayBonjour(useInterfaceMdns=true)` (`:1266-1286`):
   - One JmDNS instance per address family, bound to that interface address.
   - Registers `_airplay._tcp` (`network/CarPlayBonjour.kt:283-298, 647`).
   - Listens for the iPhone's `_carplay-ctrl._tcp` (`:648`), then probes it with an HTTP connect.
   - Holds a `MulticastLock` (`:175`).
9. `ConnectingBluetooth`. `device.createRfcommSocketToServiceRecord(IAP2_IPHONE_UUID)` and connect, 15 s (`:1291-1330`). **The head unit is the RFCOMM client; the iPhone hosts iAP2.**
10. Open the iAP2 link (`Iap2Session.openWireless`) and CSM channel.
11. Build `Iap2WirelessCarPlayEndpoint{ssid, passphrase, channel, security, ipAddresses=[host], airPlayPort, deviceIdentifier, publicKey, sourceVersion, accessPointBssid}` (`:1361-1372`).
12. `RunningWireless`. `Iap2WirelessControlClient.run()`:
    - identification and MFi authentication;
    - waits for the iPhone's "wireless CarPlay available";
    - `tx=0x5703 accessory-wifi-configuration`, capped number of sends (`transport/Iap2WirelessControlClient.kt:141-159`);
    - `tx=0x4301 carplay-start-session` (`:170`);
    - `onStartSessionSent` starts the **FirstTcpWatchdog**.
13. **First TCP.** If no non-internal TCP accept arrives on this listener within 30 s: `Failed("No AirPlay TCP after CarPlay StartSession", startupFailure=FIRST_TCP_TIMEOUT)`, then the wireless stack is torn down (`orchestration/FirstTcpWatchdog.kt:7-60`; `CarPlayController.kt:1137-1150`).
14. On TCP: pair-verify, auth-setup, SETUP and RECORD, then `WirelessActive`. The iAP2 tunnel moves to Wi-Fi with a 45 s handoff watchdog. If the handoff fails after a rendered session, the result is `WirelessActiveFallback`.

Wireless always needs the iPhone to be **bonded** with the receiver, and the receiver's Bluetooth must allow a third-party RFCOMM client.

### 3.2 Backends

The default mode is `MANUAL`, meaning car hotspot (`AirPlayPersistence.kt:306-313`).

**Wi-Fi Direct (`WIFI_P2P`, `network/WifiP2pGroupManager.kt`)**

- **Network:** the receiver creates a P2P group. The iPhone is handed DIRECT-xx with its passphrase.
- **Band:** `wifi_p2p_preferred_channel`. `AUTO=0`, `AUTO_5_GHZ=-5`, `AUTO_2_4_GHZ=-2`, or an explicit channel (`network/WifiP2pChannels.kt:13-20`).
  - Auto stays on 2.4 GHz when the station is on 5 GHz. PR #309: two 5 GHz channels on one radio paused video for 0.6–2 s.
- **Requirements:** API 33+ needs NEARBY_WIFI_DEVICES and BT; API 31-32 needs location. Android 7.1–9 uses the legacy-group path (`docs/ANDROID9_WIFI_DIRECT.md`). The firmware must allow a 5 GHz GO for 5 GHz.
- **Proven:** BYD Han DiLink 4 (#189); Redmi K20 Pro (PR #283); Flip6 and Xiaomi on 0.2.12 (#418).
- **Known failures:**
  - RTP loss bursts and stutter on BYD/Denza drivers (#131, #216, #395).
  - EADDRNOTAVAIL on IPv6, or no TCP, on the MatePad (#299).
  - Since IPv4-first: no first TCP (#464, #467; fix in open PR #474).
  - Head-unit reboots on Wuling (#457, #471).
  - Recovery: `wifiResetRequired`, then "Reset CarPlay Wi-Fi" removes the group (`DiPlayActivity.kt:4474-4500`).
- **iPhone side:** the iPhone leaves its Wi-Fi and has **no Internet** (#189).

**Car hotspot (`MANUAL`, `network/ManualHotspotManager.kt`)**

- **Network:** the system SoftAP/tethering. The user types the SSID and passphrase. Security `OPEN`, `WPA2`, `WPA3_TRANSITION` or `WPA3`; band and channel are hints.
- **Starting it:** optional auto-start, either `CarHotspotTethering.enable()` with WRITE_SETTINGS plus reflection `startTethering`, or local-ADB fallback (`network/CarHotspotTethering.kt:28-60`).
  - If `CarHotspotStatus.isEnabled()==false`: "The car hotspot is off" (`CarPlayController.kt:2104-2108`).
- **Requirements:** the AP must be on. The user must know its credentials; they can't be read on most phones and tablets, although CarLink tries `HotspotConfigReader`. API 33+ needs NEARBY.
- **Recommendation:** "it's recommended to stick the built in hotspot … if you have managed to turn on 5 GHz" [M #131].
- **Proven:** BYD Qin Plus 2025 (PR #394); Gordon Peak (#242); Song Plus (#131); Seal DiLink 5 (#271).
- **Known failures:**
  - IPv6-only mDNS meant no discovery (#253, fixed by #394).
  - IPv6 endpoint was unreachable (#242, fixed in 0.2.13).
  - WPA3 hotspot announced as WPA2: the iPhone never joins (#512 [U]).
  - The iPhone leaves Wi-Fi but won't auto-join without the Apple vendor IE (`docs/WIRELESS_HOTSPOT_JOIN.md`, PR #251).
  - Hotspot state can't be seen inside a HarmonyOS container (#299).
- **iPhone side:** joins the AP and has no Internet unless the car AP has an uplink.

**Local-only hotspot (`LOCAL_ONLY_HOTSPOT`, `network/LocalOnlyHotspotManager.kt`)**

- **Network:** an Android LOHS with generated credentials (API 26+).
- **Status:** **disabled at load.** `loadWirelessHotspotMode` rewrites `LOCAL_ONLY_HOTSPOT` to `MANUAL` (`AirPlayPersistence.kt:311`). Only the code path exists (`CarPlayController.kt:2113-2118`).
- **Proven:** none current.
- **Known failures:** BYD firmware forcing 2.4 GHz or overriding the channel (code comments `:107-270`).

**Existing Wi-Fi / Same LAN (`EXISTING_WIFI`, `network/ExistingWifiManager.kt`)**

- **Network:** the receiver's current station network. The user types the router's SSID and password, because Android can't read saved passwords (`docs/EXISTING_WIFI.md:4-21`).
- **Requirements:**
  - **exactly one** non-VPN Wi-Fi network (`:66-73`);
  - a readable SSID must match the saved one (`:91-94`);
  - on API 31+, `currentSecurityType` must be OPEN/PSK/SAE/UNKNOWN (`:98-109`);
  - only BT_CONNECT is needed (`CarPlayHostActivity.kt:755-757`);
  - router: WPA2 or mixed, no client isolation, mDNS allowed (`docs/EXISTING_WIFI.md:9-11`).
- **Proven:**
  - BYD Han (`docs/EXISTING_WIFI.md:61-67`);
  - Geely ac8x (#253), BWIC (#270), Seal 06 (#455), Camry aftermarket (#445), Leapmotor T03 (#479);
  - **MatePad Mini tablet, Android 16 API, home router, IPv4 (#299).**
- **Known failures:**
  - "Multiple Wi-Fi networks" (Samsung dual Wi-Fi, #290).
  - **No first TCP when the "existing Wi-Fi" is the iPhone's own hotspot** (#254, #299-D).
  - Mixed WPA2/WPA3 untested.
  - Old iOS rejected at identification (#507, #508).
- **iPhone side:** keeps its Internet. This is the reason the mode exists (#189).

What Existing Wi-Fi sends in `0x5703` [V]:

- the **saved** SSID and passphrase;
- security `WPA_WPA2` if a passphrase is saved, else `NONE` (`ExistingWifiManager.kt:152-153`);
- the channel from the station frequency;
- the router's BSSID as an optional hint (`:97, 138`).

The receiver's AirPlay device id is its saved identity, not the router BSSID (`:131-134`). Losing
the network or changing address restarts the session (`EXISTING_WIFI.md:46`).

**The VPN service on wireless.** `CarPlayVpnService` is bound for the listener only; no TUN is
established and no consent is needed (`CarPlayHostActivity.kt:4542`) [V].

### 3.3 Detection and recovery summary

| Symptom (log) | Meaning | DiPlay reaction |
|---|---|---|
| `Failed("The car hotspot is off…")`, `HOTSPOT_CONFIGURATION` | AP off or details missing | Stop, show message, no retry |
| `HOTSPOT_NOT_READY` | AP/P2P interface never stable (15 s, `STABLE_SAMPLES=3`) | Startup budget retry |
| `Bluetooth connect failed …` / RFCOMM timeout 15 s | iPhone out of range, BT off, OEM stack blocks RFCOMM (#98, #142, #227, #330, #435) | Backoff retry |
| `bonjourAdded=0 … tcpAccepted=0 … waitingFor=WiFi_discovery_or_AirPlay_TCP` then `No AirPlay TCP after CarPlay StartSession` | Reachability (§3.4) | `FIRST_TCP_TIMEOUT`, budget retry ×5 |
| `Multiple Wi-Fi networks are active` / `Configured Wi-Fi does not match` / `Existing Wi-Fi requires an open or WPA2-Personal network` | Existing Wi-Fi pre-checks | Startup failure |
| Handoff watchdog / iAP tunnel closed | Session up but tunnel lost | 15 s reconnect |
| `wifiResetRequired` | Stale P2P group | Reset button (manual) |

### 3.4 Diagnosis: Redmi Pad Pro, Android 16, mixed router, "authenticated, no Bonjour, no TCP"

What is verified is that the failure happens **after** `0x4301` is sent. The 30 s watchdog
expired with no TCP from the iPhone.

[M] in #242 and #253: a completed StartSession "alone is not evidence"; the problem is reachability.
`docs/EXISTING_WIFI.md:71`: "An absence of Bonjour control-service events alone does not prove
that CarPlay discovery failed."

Candidate causes, most likely first. All are [I] unless marked.

1. **The iPhone is not on the same L2 segment as the tablet.**
   - It may be on a guest SSID, another band with band steering or mesh, its own Personal Hotspot (proven failure topology, #254 and #299-D), or never re-associated.
   - On a WPA2/WPA3 transition network the tablet may be associated with SAE (accepted at `ExistingWifiManager.kt:103`), while `0x5703` tells the iPhone "WPA_WPA2" with a BSSID/channel hint.
   - #512 [U] shows that a WPA3/WPA2 mismatch alone stops the iPhone joining a car AP.
   - Test: router set to **WPA2-only, one SSID, one band**.
2. **Client isolation or multicast filtering on the router.** AP isolation, IGMP snooping, "multicast to unicast", or mDNS filtering. These are the maintainer's stated preconditions (#84).
   - Test: from a laptop on the same Wi-Fi, `curl http://<tablet-ip>:7000/info`. No answer means isolation.
3. **Saved SSID or password wrong.**
   - The SSID check only works when the name is readable, which needs location. With an unreadable SSID a typo passes unchecked (`ExistingWifiManager.kt:91-94`).
   - A wrong password makes the iPhone refuse to (re)join.
4. **The address-family edge case.**
   - The endpoint is IPv4 (`HotspotAddressPolicy`); Bonjour is published on both families (`WirelessHostAddress.kt:15-29`).
   - #464 and #469 show some environments where only IPv6 works. Upstream PR #474 (family flip) covers hotspot backends only.
5. **Android 17 Local Network Protection.**
   - CarLink is `targetSdk = 37` (`app/build.gradle.kts:55`) and does **not** declare `ACCESS_LOCAL_NETWORK` (CarLink manifest lines 19-26 [V]).
   - #425: on Android 17 local IPv4 is then "silently dropped", while link-local IPv6 keeps working. Granting the permission fixed it (upstream PR #465, not in 0.2.15).
   - The Redmi Pad Pro is on Android 16, so [I] this is probably not the user's cause today. It will be on HyperOS 3 / Android 17.
6. **CarLink cut the attempt short.**
   - CarLink abandons Same Wi-Fi at the first `Failed`, even a Bluetooth one (§10, F1). So the user may only ever have had **one** 30 s attempt per path.
   - It also stores a permanent "refused" mark that keeps AUTOMATIC away from Same Wi-Fi afterwards.

7. **The SSID is not trimmed, and the name check is usually skipped.**
   - The Existing Wi-Fi name field is deliberately not trimmed, unlike the car-hotspot field (`DiPlayActivity.kt:2806`).
   - In this mode the host asks only for `BLUETOOTH_CONNECT`, never location (`CarPlayHostActivity.kt:755-757`). On a tablet the live SSID is therefore usually unreadable and the mismatch check never runs.
   - A trailing space, wrong case or a band-suffixed name ("-5G") goes straight into `0x5703` and `0x4301`.
8. **The tablet announces a fake local Bluetooth MAC** [I]. The local BT address comes from `BluetoothAdapter.address` or `Settings.Secure bluetooth_address`, rejecting `02:00:00:00:00:00` (`common/.../DiPlayBluetooth.kt:8-15`; `CarPlayController.kt:2369-2386`). Otherwise it falls back to an identity-derived address (`CarPlayHostActivity.kt:3718`). A stock tablet without `LOCAL_MAC_ADDRESS` probably uses the fallback, and that address goes into iAP2 identification and `/info` `bluetoothIDs`. Authentication passed, so it may not matter. Check `localBt=` in `wireless selected Bluetooth target …` (`CarPlayController.kt:1220-1222`).
9. **The channel field is a u8** (`network/WirelessHotspotManager.kt:64-71`). A 6 GHz connection gives an ambiguous channel number.

**Which stage failed** (from the iAP2 trace lines in `transport/Iap2WirelessControlClient.kt:51-187`):

- **Stage A:** no `iap2 rx=0x4300` and no `tx=0x4301`; the summary shows `waitingFor=WiFi_configuration_or_start_request`. The iPhone never accepted the network or never started a session. Look at causes 1, 3, 7 and the iPhone-side "Use CarPlay".
- **Stage B:** `tx=0x4301`, then `first TCP timeout` with `waitingFor=WiFi_discovery_or_AirPlay_TCP`. Reachability; look at causes 2, 4, 1.
- If `rx=0x4e0e … resending 0x5703` appears, the iPhone reported that it could not join.

Logs to collect (DiPlay debug log on, `debug_logs_enabled`; logcat tags `xcertplay-usb`, `xcertplay-bonjour`):

- `Existing Wi-Fi attached iface= host= channel= security=`
- `wireless endpoint addressCount= family= port=`
- `wireless Bonjour services started`
- the startup diagnostics snapshot (`bonjourAdded/Resolved`, `connectProbes`, `tcpAccepted`, `mdnsFamilies`)
- `iap2 tx=0x5703`, `tx=0x4301`
- `No AirPlay TCP after CarPlay StartSession`

CarLink side: `CarLink-Settings` (`path=… ssidReadable=… open=…`) and `CarLink-Engine` ("trying the next path").

Note: `DiPlayBootstrap.ensure()` turns debug logs **off** at every bootstrap (`DiPlayBootstrap.kt:40`). So turning the setting on before a session does not stick unless it is re-enabled after `ensure()` [V]. That matters for CarLink's Advanced toggle (`app/.../AdvancedSettings.kt:184`).

### 3.5 iPhone side

- **First time:**
  1. Pair the iPhone with the receiver in Bluetooth settings. The receiver must be visible and bonded.
  2. On the first iAP2 contact, the iPhone shows **"Use CarPlay with <name>?"**. The user must tap it while the iPhone is unlocked. [I] Until then the iPhone does not advertise wireless CarPlay, so the attempt stalls before `0x5703`.
  3. The iPhone joins the network in `0x5703` by itself (P2P / hotspot), or stays on it (Same LAN).
  4. The iPhone opens AirPlay TCP to the `0x4301` endpoint.
- **Name shown:** the iAP2 identification name is hard-coded `"DiPlay"` and the label `"DiPlay"` (`CarPlayHostActivity.kt:160-173`). Manufacturer and model come from prefs (CarLink renames them to "CarLink", `app/.../CarLinkApplication.kt:89-94`). The OEM label (car brand) defaults to **"BYD"** (`AirPlayPersistence.kt:113, 466-470`).
- **Reconnects:** need Bluetooth on, Wi-Fi on, and auto-join enabled for the car network (`COMPATIBILITY.md` "Known limitations"). iOS 13 and older fail identification (#507, #508).

---

## 4. Bluetooth

- **Pairing:** DiPlay never pairs. It needs a system bond made in Android Bluetooth settings (`choosePhone()` lists `adapter.bondedDevices`, `DiPlayActivity.kt:4452`). An unpaired or empty list leads to "Pair your iPhone" and opens `ACTION_BLUETOOTH_SETTINGS` [V].
- **Selection:**
  - The `diplay` prefs `phone_address` / `phone_name` (`DiPlayBootstrap.kt:65-71`) go into `CarPlayRuntimeConfig.wirelessBluetoothDeviceAddress` (`CarPlayHostActivity.kt:176`).
  - The fallback heuristics are in §3.1 step 6.
  - CarLink sets the address through `CarPlayHostIntegration.selectPhone` before each wireless start (`app/.../CarPlayEngine.kt:135-137`).
- **Transport:** an RFCOMM client to the iPhone's iAP2 service UUID (`IAP2_IPHONE_UUID`, `CarPlayController.kt:1298-1303`), 15 s connect, streams acquired before iAP2 (`transport/BluetoothRfcommDuplexStream.kt`).
  - Bond state is evidence, not a gate (`docs/CONNECTION_RELIABILITY.md`).
  - "Ordinary pairing or connect() success does not establish an iAP2 link".
- **Auto-reconnect:**
  - DiPlay core has **none**. When the iPhone comes back, nothing restarts a closed session.
  - In standalone, the optional `PhoneBluetoothReceiver` (ACL_CONNECTED for the selected address) opens DiPlayActivity, which calls `connect(true)`.
  - Inside a running session, the host's backoff loop keeps retrying (§2.3). So an iPhone that returns within the retry budget reconnects, and one that returns after the budget is spent does not [V/I].
  - CarLink replaces this with `SystemEventReceiver` (ACL_CONNECTED, then SDP-based classification if needed, then `HomeLauncher.open`, then `EngineCoordinator.onBluetoothPhone`; `app/.../entry/SystemEventReceiver.kt`). It needs the overlay permission on Android 10+, otherwise a full-screen notification is used (`app/.../entry/HomeLauncher.kt:33-40`).
- **Head unit vs tablet:**
  - **Head units:** OEM Bluetooth apps may hold the stack, so third-party RFCOMM fails (#227 com.syu.bt, #98 RK3326, #330/#354 Android 9, #435 ac8x).
  - **Tablets:** the stack is generic, so RFCOMM generally works. HarmonyOS was the exception (#142).
  - [I] A tablet is the audio gateway (phone role). CarPlay audio goes over AirPlay anyway; `car_bluetooth_audio` (default false) is an experimental route.
  - [I] On a tablet there is no HFP-client role, so the iPhone will not also see a "car" hands-free device. Calls go through CarPlay audio (#408: mic issues during calls).

---

## 5. USB

Flow [V] (`CarPlayController.kt:1688-1900`, `transport/IphoneUsbHost.kt`, `Iap2UsbMuxHost.kt`, `NcmUsbBridge.kt`, `Lockdown*.kt`):

1. `DiscoveringIphone`: `iphoneHost.discover()`, polled every 2 s (`WaitingForIphone`).
2. `RequestingIphonePermission`: `UsbManager.requestPermission`, polled for 120 s; denied means `Failed("iPhone USB permission was denied")` (`:1774`).
   - `UsbAutoConfirmService` can tap the dialog for the user.
   - Some ROMs never show the dialog (#442).
3. `SelectingConfiguration`: an iPhone control request switches it to its CarPlay USB configuration.
4. `WaitingForReenumeration`: the iPhone detaches and re-attaches as a new `UsbDevice` (at most 2 attempts). `onIphoneAttached` asks for permission again.
   - This is why CarLink ignores a USB re-attach during a running USB session (`app/.../EngineCoordinator.kt:110-118`).
5. `OpeningDataPaths`: claim the usbmux and NCM interfaces.
   - Claim failures are an open issue (#335, #340, #443: a native daemon holds NCM).
   - Large-request queue rejection: retry at 16 KiB (#135, #244).
6. `Pairing`: lockdown pair. The iPhone shows **"Trust This Computer?"** and the user must enter the passcode, within a 5 min timeout. The pair record is persisted (`AirPlayPersistence` `lockdown_*` keys), so later sessions don't prompt.
7. `ConnectingControl`: iAP2 over usbmux, MFi authentication, `0x4301`.
8. `AttachingNetwork`: the NCM link is bridged through the `CarPlayVpnService` TUN (link-local IPv6). **This needs VPN consent.**
9. `RunningControl`, then AirPlay over NCM.

**PTP/MTP competition.** The iPhone's photo/PTP prompt ("Allow this device to access photos and videos?") can loop every 2-5 s on some units (#318, open). Other apps that hold Apple devices (system MTP, launchers) can win the attach; CarLink takes it by being the only `USB_DEVICE_ATTACHED` handler (`UsbRouterActivity`).

**Failure modes:**

- the configuration request fails `transferred -1 of 1 bytes` (#455);
- NTB16 pad bug on 512-byte multiples (#475, fix PR #478 draft);
- USBMUX trailer on iOS 27 (#100, fixed);
- bad cable gives no Trust prompt (#290);
- old iOS 12/15 on Android 15 (#509);
- DNS outage while the TUN is up (#391).

**ROMs without a VPN consent dialog** (Pioneer, #443): `appops set <pkg> ACTIVATE_VPN allow` works around it (#357).

---

## 6. Settings

The prefs file is `xcertplay_airplay` (`AirPlayPersistence.kt:27`) unless noted. "Connect?" means
the setting affects whether a session can be established.

| Key | Default | Effect | Connect? |
|---|---|---|---|
| `wireless_enabled` | **true** (`:250-252`) | Wired or wireless transport. A USB attach forces false | **Yes** |
| `wireless_hotspot_mode` | **MANUAL** (car hotspot); LOCAL_ONLY is rewritten to MANUAL (`:306-313`) | Wi-Fi backend | **Yes** |
| `wifi_p2p_preferred_channel` | AUTO (0) (`:323-327`) | P2P band/channel | Yes (quality) |
| `manual_hotspot_ssid` / `_passphrase` / `_band` (AUTO) / `_channel` (0) / `_security` (derived) | empty / AUTO | Car-hotspot details sent in 0x5703 | **Yes** |
| `existing_wifi_ssid` / `existing_wifi_passphrase` | "" (`:335-345`) | Same-LAN details sent in 0x5703 | **Yes** |
| `mfi_target` | LOCAL (`:260-264`) | MFi source: local assets / I2C / CH341 / remote | **Yes** |
| `mfi_i2c_path`, `remote_mfi_server`, `remote_mfi_token` | `/dev/i2c-1`, empty | Non-local MFi | Only for those targets |
| `identity_*`, `pairing_id(s)`, `lockdown_*` | generated | AirPlay identity, HomeKit-style pairings, USB Trust record | **Yes** (clearing them forces re-pairing / Trust) |
| `location_reporting_enabled` | false (`:432-434`) | iAP2 location; asks for location permission; FGS location type | Indirect (location prompt blocks start until answered) |
| `debug_logs_enabled` | false; **forced off by `DiPlayBootstrap.ensure`** (`DiPlayBootstrap.kt:40`) | Verbose connection log | No (diagnostics) |
| `auto_start_on_boot` | false (`:422-424`) | Standalone `BootReceiver` | Standalone only |
| `manufacturer` / `model` / `oem_label` | "DiPlay" / "DiPlay" / **"BYD"** (`:111-113`) | What the iPhone shows for the car | No (branding) |
| `display_scale_tenths`, `display_fps` (30), `hevc_enabled` (false), `hevc_software_decoder`, `smooth_video` (false), `ui_scale_percent`, `display_width_physical_mm`, `display_physical_size_basis`, `adapt_pip_resolution`, `safe_area_*`, `hide_top_bar`, `hide_bottom_bar`, `right_hand_drive` (false) | as listed | Picture / geometry | No (a wrong decoder or HEVC choice can give a black screen, #13) |
| `audio_focus_*`, `media_audio_channel`, `navigation_audio_channel`, `navigation_stream_type`, `media_buffer_ms`, `main_buffered_audio`, `car_bluetooth_audio` (false), `call_echo_cancellation`, `call_voice_filter` | off / defaults | Audio routing | No |
| `carplay_night_mode` (+ start/end), `app_appearance`, `ambient_lux_threshold` | system | Day/night | No |
| `cluster_*`, `center_map_*`, `adb_cluster_activity_enabled`, `launcher_map_sharing` | off (`:632-712`) | Dashboard map (BYD) | No |
| `settings_gesture_fingers` | — | In-session menu gesture | No |

The `diplay` prefs (`DiPlayBootstrap.kt:65-85`):

| Key | Default | Connect? |
|---|---|---|
| `phone_address` / `phone_name` | none | **Yes for wireless** |
| `default_connection_mode` | LAST_USED | Standalone |
| `auto_connect` | false | Standalone |
| `connect_on_phone_bluetooth` | false | Standalone |
| `notification_asked` | false | No |

The `diplay_byd_outputs` prefs (`hud/BydOutputSettings.kt:11-25`):

- `navigation_enabled`: **default true** (`:29`)
- `battery_to_iphone`, `wheel_speed_to_iphone`, `video_while_parked`, `cluster_song`, `hud_song`, `carplay_calls`, `oem_cluster_hold`, …: false

None of these are needed to connect; see §12.

Other prefs:

- `CarHotspotSettings` `auto_enable` (false): auto-start the hotspot.
- `diplay_phone_wake*`: diagnostics.
- `WheelZoom*`, `DiLink51ClusterLayout`: BYD.

The host re-reads the connection settings on every resume (`loadConnectionSettings`, `CarPlayHostActivity.kt:673-694`). Writing prefs before opening the host is therefore enough. **Every connection setting is read from SharedPreferences; nothing is passed in the intent** [V].

---

## 7. Host-integration surface

Already present:

- **`CarPlayHostIntegration`** (`common/.../CarPlayHostIntegration.kt`):
  - `homeActivity`, `appName`, `mediaSessionTag`
  - `toastHandler`
  - `palette`, `chromeStyler(root, Chrome{WAITING, MENU, SIDE_PANEL, PICTURE_PANEL, SAFE_AREA_EDITOR})`
  - `menuItems`
  - `selectPhone` / `selectedPhoneAddress`
  - `authenticationError`
- **`CarPlayStatusBus`**: `current` plus listeners; `null` means no controller.
- `DiPlaySessionService.ACTION_STOP` to stop.
- `AirPlayPersistence.*` public setters for every setting.
- `DiPlayActivity.EXTRA_CARLINK_CONNECT`, which is unused (§2.5).

The cleanest contract with today's code:

1. **Start:**
   1. Write the prefs (§6).
   2. Select the phone.
   3. Check `authenticationError`.
   4. `startActivity(CarPlayHostActivity, NEW_TASK|REORDER_TO_FRONT)`. For USB, copy the attach intent's action and `EXTRA_DEVICE` so the host forces wired.
2. **Observe:** `CarPlayStatusBus`.
3. **Stop:** `startService(DiPlaySessionService, ACTION_STOP)`. It goes through `CarPlayBackgroundSession.stop()`, `shutdown(false)` and `finish()` (`CarPlayHostActivity.kt:4257-4261`). [I] `CarPlayBackgroundSession` is `internal`, so it can't be called directly from `:app`.

What can run with **no** DiPlay UI: nothing that produces a session. Only:

- `DiPlayBootstrap` / `authenticationError` (MFi install);
- prefs;
- `WifiDirectReset` / P2P cleanup;
- `CarHotspotStatus` / `CarHotspotTethering.enable` (needs WRITE_SETTINGS);
- `MfiSelfCheck`.

The controller depends on the Activity's window, surface, permission launchers and lifecycle.

What is missing to hide DiPlay's screens (vendor changes suggested, each small and in the style of the existing hooks) [I]:

1. **`CarPlayHostIntegration.waitingView: ((Context) -> View)?`** or a WAITING "content provider": the host supplies the whole waiting UI, not just a restyle. That removes the last DiPlay-looking screen.
2. **`CarPlayHostIntegration.onRetryState(retries, exhausted, startupFailure)`**, or publishing the retry budget on the status bus, so the host can tell "failed but DiPlay is retrying" from "DiPlay gave up".
3. **`CarPlayHostIntegration.prerequisitesHandledByHost`**: skip the host's own permission launchers when the host has already granted them, so system dialogs don't appear over the projection.
4. A **public `stop()`** wrapper around `CarPlayBackgroundSession.stop(completion)`, so teardown is synchronous and observable instead of a fire-and-forget service intent.
5. **Make the iAP2 `name`/`label` and the OEM label hookable** (`CarPlayHostActivity.kt:160-173`).

---

## 8. Standalone-app UX and what a host must replicate

| DiPlay feature | Where | Must CarLink replicate? |
|---|---|---|
| First-run Setup guide (DiLink generation, feature tiers) | `setup/SetupGuide.kt`, `DiPlayActivity.kt:341-343` | Partly. The "pair your iPhone / choose iPhone / Wi-Fi method" steps are essential; the DiLink tiers are not |
| Choose iPhone: BT permission → BT on → bonded list → "Pair another" opens system BT settings | `DiPlayActivity.kt:4441-4472` | **Yes.** Wireless can't start without a bonded, selected iPhone |
| Connection setup: Wi-Fi method; car-hotspot SSID/password/security/band; Existing Wi-Fi SSID/password; "open hotspot settings"; hotspot auto-start (WRITE_SETTINGS / ADB) | `DiPlayActivity.kt:1220-1580, 2542+`, `CarHotspotSetup.kt` | **Yes.** Credentials can't be read on most devices |
| Car-hotspot-off dialog | `DiPlayActivity.kt:2121, 2290` | Yes (CarLink: `CAR_HOTSPOT_OFF`) |
| Notification permission, asked once | `:4425-4430` | Yes (CarLink asks in its permission card) |
| Wireless help: "Pair your iPhone…keep Wi-Fi on…" plus **Reset CarPlay Wi-Fi** (remove P2P group) | `:4474-4500` | Yes (CarLink: `WifiDirectReset`, Home `wireless-recovery`) |
| Overlay permission (boot/BT launch), Usage access, Accessibility (USB auto-confirm, wheel keys) via `UsbPermissionSetup` | `UsbPermissionSetup.kt`, `DiPlayActivity.kt:436, 3138` | Overlay: **yes** for automatic starts on Android 10+. USB auto-confirm: recommended on head units. Usage/wheel: BYD only |
| Battery optimisation | Not requested by DiPlay (no `IGNORE_BATTERY` in common, [V] grep) | CarLink already checks it (`app/.../diagnostics/Checks.kt:132`) |
| Status text during a session: `CarPlayStatus.describe()` and the retry/"retries exhausted" text and Retry / Reset Wi-Fi buttons on the waiting screen | `CarPlayHostActivity.kt:4064-4087, 4566-4571, 5099` | Yes, in CarLink's own words (needs §7 hook 2) |
| Diagnostics: Save/View/Share report (redacted), phone-wake and startup snapshots, wireless diagnostics | `DiagnosticExportStore`, `DiagnosticRedactor`, `docs/WIRELESS_DIAGNOSTICS.md` | Strongly recommended. Upstream triage depends on it |
| Update checker | `update/*` | No |

---

## 9. Upstream issues: real-world lessons

Sources: all 303 issues with comments, recent PRs, the docs. Full per-issue notes:
`/tmp/claude-1000/-DATA-blue-Projects-CarLink/8adedd86-6109-4369-85ba-228070abe845/scratchpad/all.txt`.

**1. "Authenticated but no AirPlay TCP" is the dominant wireless failure, and it is a network problem.**
- Seen in #151, #226, #242, #253, #254, #299, #316, #318, #455, #464, #467, #469.
- [M] #242: "A completed StartSession alone is not evidence that CarPlay projection is working."
- [M] #299: "客户端加入 DIRECT 还不能证明 TCP 已到达应用" ("a client joining DIRECT does not prove TCP reached the app").

**2. Address family is fragile in both directions.**
- IPv6-only failed: #242, #253. Fixed by preferring IPv4 (PR #283, 0.2.13) and dual-stack mDNS (PR #394, 0.2.14).
- IPv4-first then broke some P2P units. [M] #464: "旧记录中有 7 次成功的 IPv6 无线会话，新记录使用 IPv4 后一直没有首个 AirPlay TCP" ("7 successful IPv6 wireless sessions in the old logs; since switching to IPv4, no first AirPlay TCP").
- Fix pending: PR #474 (alternate the family after each first-TCP timeout).
- Android 17 with targetSdk 37 needs `ACCESS_LOCAL_NETWORK` (#425, PR #465; "0.2.15 尚不包含", "not yet in 0.2.15").

**3. Recommended wireless mode is the car hotspot on 5 GHz.**
- [M] #131: "it's recommended to stick the built in hotspot. It's the main wireless connection and stable if you have managed to turn on 5 GHz with it."
- Wi-Fi Direct stutters on many BYD/Denza drivers (#131, #216, #395); "An app can't change that without root" [C].

**4. Existing Wi-Fi is the best mode on non-BYD and odd hardware, as long as the router is plain.**
- Works on ac8x, ac8257, mt6765, Leapmotor 7.1, Camry aftermarket, and the MatePad Mini tablet.
- Fails when the "router" is the iPhone's own hotspot (#254, #299).
- Fails with Samsung dual Wi-Fi (#290).
- Mixed WPA2/WPA3 is untested.
- Router preconditions [M] #84: no client isolation, multicast/mDNS, IPv6 between clients.

**5. iPhone join problems.**
- A WPA3 AP described as WPA2 means the iPhone never joins (#512 [U]).
- Without the Apple vendor IE in the beacon the iPhone does not auto-join (PR #251, `docs/WIRELESS_HOTSPOT_JOIN.md`).

**6. Bluetooth.**
- Pairing ≠ iAP2 reachability. [M] #435: "系统蓝牙音乐/通话能使用，并不能确认第三方 RFCOMM 通道也可用" ("working system Bluetooth music/calls does not prove a third-party RFCOMM channel is usable").
- OEM stacks block RFCOMM (#98, #142, #227). There is no Wi-Fi-only fallback; CarPlay requires the iAP2 bootstrap.

**7. USB.**
- Apple vendor id in the filter (#11).
- Suppressed permission dialogs (#442); missing VPN dialog (#443, #357 appops workaround).
- PTP prompt loop (#318); NTB padding (#475).
- Cable quality (#290); old iOS (#509).

**8. Tablets.**
- MediaTek C2 decoder rejects the tuned format (#13).
- iOS 27 sends no frames to some tablets (black screen, audio OK, #249, open).
- Large low-density screens need scaling (PR #378).
- HarmonyOS containers hide hotspot state (#299).

**9. Closed ≠ fixed.** Many issues are closed only "pending logs" (#227, #455 reopened).

Full list of proven combinations: §3.2 and the upstream report summary in §0.

---

## 10. Review of CarLink's integration

Files read:

- `app/.../engine/CarPlayPreflight.kt`, `CarPlayEngine.kt`, `EngineCoordinator.kt`, `WifiDirectReset.kt`
- `app/.../settings/EngineSettingsApplier.kt`, `EngineMappings.kt`
- `app/.../entry/SystemEventReceiver.kt`, `HomeLauncher.kt`
- `app/.../CarLinkApplication.kt`, `app/src/main/AndroidManifest.xml`

The findings are ranked.

**F1 (high, verified): any `Failed` is treated as terminal, and Same Wi-Fi is abandoned and blacklisted at the first one.**

- `CarPlayEngine.map()`: every `CarPlayStatus.Failed` becomes `LinkStage.FAILED` (`CarPlayEngine.kt:193-194`). But DiPlay keeps going after a non-configuration Failed:
  - `reconnectAfterLoss()` retries with a 2-30 s backoff, up to 5 startup retries (`CarPlayHostActivity.kt:4076-4085, 4559-4597`).
  - So Home shows "Failed" while the projection activity is still retrying in front of the driver.
- `EngineCoordinator.fallBackFromSameWifi()` (`:253-265`):
  - On the **first** Failed of a Same Wi-Fi attempt, it records `WirelessRefusals(...).record(CARPLAY, ssid, password)`.
  - It restarts on another path. That means `carPlay.start()` → `requestStop()` (ACTION_STOP) → a new host.
  - It does not check `startupFailure`. A Bluetooth/RFCOMM timeout, an "iPhone no longer paired", or a transient iAP2 error marks the router as unusable for CarPlay.
  - After that, `WirelessPathSelector` stops picking Same Wi-Fi in AUTOMATIC mode (`EngineMappings.kt:47-49`).
  - On a tablet the next path is `CAR_HOTSPOT` (unusable: the hotspot is off or its credentials unreadable) or `WIFI_DIRECT`. So the user gets about one 30 s Same Wi-Fi try, then a P2P attempt.
- **Fix:**
  - Fall back only on `startupFailure == FIRST_TCP_TIMEOUT` (or `HOTSPOT_CONFIGURATION` from the Existing Wi-Fi checks), and only after DiPlay has used a defined number of retries.
  - Never record a refusal for Bluetooth-stage failures.
  - Expire refusals, e.g. after a successful session or 24 h.
  - Map a Failed that DiPlay is retrying to CONNECTING ("Retrying…") rather than FAILED. This needs §7 hook 2, or the inference: Failed with `startupFailure != HOTSPOT_CONFIGURATION` and `!wifiResetRequired` means "retrying".

**F2 (high, verified): the driver sees DiPlay's own waiting screen, and that can't be avoided without a vendor hook.**

- `CarPlayEngine.openHost()` launches `CarPlayHostActivity` immediately (`:172-187`). That Activity shows its WAITING chrome, DiPlay's per-status strings (`describe()`), its Retry / "Reset CarPlay Wi-Fi" / "Back to DiPlay" buttons, and system permission and VPN dialogs (`CarPlayHostActivity.kt:696-771`).
- `EngineChrome` only restyles it (`app/.../EngineChrome.kt:153-203`).
- That is what "when an iPhone is chosen the driver sees DiPlay's waiting screen" refers to.
- The Activity must be in front: §1.3 item 1. [I] It can't be started invisibly behind Home.
- **Fix:** add `CarPlayHostIntegration.waitingView` (§7 hook 1) so CarLink renders its own waiting, failure and recovery UI inside the host window. Until then, restyle fully and map every `describe()` string through CarLink resources (`carlink_engine_overrides.xml`).

**F3 (high, verified): permissions and consent are left to the projection Activity.**

- `CarPlayPreflight`'s own comment says "The CarPlay host itself still asks for runtime permissions and VPN consent" (`CarPlayPreflight.kt:15-18`). So the first wired session pops the **VPN consent** system dialog over the waiting screen, and on API 33+ the microphone, Nearby and BT dialogs.
- CarLink's permission card asks for most of these (`ui/CarLinkViewModel.kt:229-243`): BT_CONNECT, BT_SCAN, NEARBY, POST_NOTIFICATIONS, FINE_LOCATION, RECORD_AUDIO. It does **not** ask for VPN consent, and the pre-flight never checks permissions.
- **Fix:**
  - The pre-flight should check that `CarPlayVpnService.prepare(context) == null` before wired.
  - Run `VpnService.prepare` from CarLink's onboarding.
  - Check the wireless permission set `requiredWirelessPermissions()` would request (§1.3).
  - Report `LinkProblem.PERMISSION` instead of launching the host.

**F4 (high for future devices, verified config / inference effect): `targetSdk = 37` without `ACCESS_LOCAL_NETWORK`.**

- `app/build.gradle.kts:55`; the manifest declares no such permission.
- Upstream #425: on Android 17 local IPv4 is silently dropped, which is exactly "authenticated, no AirPlay TCP" on every wireless mode.
- **Fix:** declare and request `android.permission.ACCESS_LOCAL_NETWORK` (Nearby group) when SDK ≥ 37, or merge upstream PR #465.

**F5 (medium, verified): BYD defaults are on for every head unit.**

- `UnifiedSettings.bydDirections = true` (`app/.../UnifiedSettings.kt:24, 56`) is written to `BydOutputSettings.setEnabled` (`EngineSettingsApplier.kt:76`).
- The OEM label stays "BYD" (`AirPlayPersistence.kt:113`; CarLink renames only manufacturer and model, `CarLinkApplication.kt:89-94`).
- The iAP2 accessory name stays "DiPlay" (`CarPlayHostActivity.kt:161, 173`).
- See §12. **Fix:** a vehicle profile; default `bydDirections=false` unless a BYD is detected; set `oem_label` to "CarLink".

**F6 (medium, verified): `debug_logs_enabled` cannot be turned on.** `DiPlayBootstrap.ensure()` saves it as false on every bootstrap (`DiPlayBootstrap.kt:40`), and CarLink's pre-flight runs `ensure` before every session. CarLink's Advanced toggle (`AdvancedSettings.kt:184`) is overwritten. **Fix:** re-apply the toggle after `authenticationError()`, or remove that line in vendor (and list it in VENDOR_CHANGES).

**F7 (medium, verified): the Existing Wi-Fi pre-flight cannot catch the common mistakes.**

- `CarPlayPreflight` only validates the format of the saved SSID and password (`:38-41`).
- When the joined SSID is unreadable (no location permission, or location off) a typo goes unnoticed. `EngineSettingsApplier` only logs that case (`:50-56`).
- `ExistingWifiManager` also skips the check when the SSID is unreadable (`:91-94`).
- **Fix:**
  - Require fine location plus location-on for Same Wi-Fi, so CarLink and DiPlay can confirm the SSID.
  - Offer "use the network I'm on" to fill the SSID automatically.
  - Warn on WPA3/SAE (`currentSecurityType == SAE`) that mixed mode is unvalidated, and suggest WPA2.
  - Detect multiple Wi-Fi networks before start (Samsung/Xiaomi dual Wi-Fi gives "Multiple Wi-Fi networks are active").

**F8 (medium, verified): the car-hotspot path on tablets.**

- `WirelessPathSelector` treats the hotspot as usable when credentials are saved and `carHotspotOn != false` (`EngineMappings.kt:51-52`).
- On a tablet, DiPlay's `MANUAL` needs the **tablet's** tethering AP to be on and its typed credentials; `CarHotspotTethering.enable` needs WRITE_SETTINGS and reflection, and fails on many OEM ROMs.
- `CarPlayPreflight` only checks `CarHotspotSettings.enabled && permitted` (`:32-35`). `CarHotspotSettings` is BYD-ADB oriented (`CarHotspotSettings.kt:16-17`).
- **Fix:** on tablets prefer Same Wi-Fi, then Wi-Fi Direct; offer the tablet hotspot only as an explicit choice.

**F9 (medium, verified): stop is fire-and-forget.**

- `CarPlayEngine.stop()` sends `ACTION_STOP` only when `statusBus.current != null` (`:165-170`). A host that has been launched but has not yet reported (still in its permission dialogs) is not stopped; the 60 s `hostStartTimeout` then reports FAILED while the Activity stays open.
- [I] `startService` from the background is allowed while DiPlay's foreground service runs, but not before it starts.
- **Fix:** a vendor `stop()` hook (§7 item 4), or also finish the host via an intent extra.

**F10 (low, verified): `CarPlayStatus.ControlEnded` maps to Idle.** CarLink disengages (`EngineCoordinator.kt:243`). DiPlay does not retry on ControlEnded, so this is consistent. Note that a wired `TIMED_OUT` also ends as ControlEnded (`CarPlayController.kt:2042`). There is no "the iPhone left" message to show.

**F11 (low, verified): stale documentation.** `docs/VENDOR_CHANGES.md` says CarLink starts CarPlay "through DiPlay's own `connect()` pre-flight" (`EXTRA_CARLINK_CONNECT`), but `CarPlayEngine` bypasses DiPlayActivity. Either remove the hook or update the doc.

**F12 (low, verified): no auto-reconnect inside an engaged session after DiPlay gives up.**

- When DiPlay exhausts its startup budget it shows its Retry button and stops reporting.
- CarLink then sits in FAILED with the host still open. `SystemEventReceiver.act()` ignores BT links while a session "isActive", and FAILED is presumably not active ([I] `LinkState.isActive`).
- **Fix:** on exhausted retries, close the host and return to Home with a CarLink failure card plus an automatic retry when the iPhone's ACL next connects.

**F13 (high, verified): `CarPlayStatusBus.current == null` does not mean "no session", but CarLink treats it that way.**

- `CarPlayController.close()` publishes `null` at the *start* of teardown (`CarPlayController.kt:604`), and DiPlay calls `close()` on every internal restart (`restartCarPlay`, `CarPlayHostActivity.kt:4604-4630`).
- `current` is also null while the host waits for permissions, VPN consent or its window size, before any controller exists.
- CarLink uses `current` for several decisions:
  - `stop()` and `requestStop()` do nothing when it is null (`CarPlayEngine.kt:158, 165-166`);
  - `start()` opens immediately when it is null (`:142-143`);
  - `map(null)` gives Idle, so `EngineCoordinator` disengages (`EngineCoordinator.kt:243`).
- Consequences:
  - Each DiPlay-internal retry makes Home flash Idle and drops `sessionRequest`, which also defeats the F1 fallback. The next status is then "adopted" as a foreign session (`EngineCoordinator.kt:227-232`).
  - A Disconnect pressed while the host shows a permission dialog is ignored, and the session is adopted later.
  - A new `start()` in that window only `REORDER_TO_FRONT`s the existing singleTask host. Its `onNewIntent` handles only USB attach (`CarPlayHostActivity.kt:773-791`), so the old phone and mode keep running.
- **Fix (vendor hook):** expose `CarPlayHostIntegration.hasSession()` (wrapping `CarPlayBackgroundSession.hasSession()` plus "host alive") and `stopSession(completion)`. Also publish a distinct "closed for good" from `shutdown()`. CarLink should then key on those, not on `current`.

**F14 (high, verified by reading both paths; the race itself is [I]): a restart can kill the new session's foreground service.**

- CarLink opens the replacement host as soon as `null` is published (`CarPlayEngine.kt:108-117`), which is the start of the old `close()`.
- The old host's `shutdown()` finishes on a teardown thread and only then calls `stopService(DiPlaySessionService)` (`CarPlayHostActivity.kt:4791-4803`).
- If the new host has already called `startForegroundService` (`:4264`), that late stop removes the new session's foreground service.
- DiPlay itself waits for the completion callback: `CarPlayBackgroundSession.stop { open() }` (`DiPlayActivity.kt:4434-4436`).
- Affected flows: Same Wi-Fi fallback, phone switch, and USB taking over wireless.
- **Fix:** start the next session only on the stop completion (needs the F13 hook).

**F15 (medium, verified): host-side blocking states never reach CarLink.** None of these produce a `CarPlayStatus`:

- VPN consent denied (`CarPlayHostActivity.kt:189-198`);
- wireless permissions denied (`:200-213`);
- the foreground service failing to start (`:4266-4270`; only the `null` from `shutdown` is seen, so CarLink shows Idle);
- retries exhausted (`:4566-4571`).

CarLink shows "Waiting" until its 60 s timeout, then a generic ENGINE failure, which also triggers F1. **Fix:** pre-flight these in CarLink (F3) and add a vendor host-state callback.

**F16 (medium, verified): pre-flight gaps compared with `choosePhone()`/`connect()`.**

- **Bluetooth adapter off is not checked.** The controller then throws "Bluetooth is not enabled" (`CarPlayController.kt:1217`), which is a Failed, so F1 blacklists Same Wi-Fi. Add `LinkProblem.BLUETOOTH_OFF`.
- **The selected iPhone is not checked to still be bonded.** The controller fails with "The selected iPhone is no longer paired" (`:2174-2177`). Check `bondedDevices` in the pre-flight.
- **"Reset CarPlay Wi-Fi" does not reconnect.** CarLink's `HomeActivity.consumeRecovery` stops the attempt and removes the group, but does not reconnect (`app/.../ui/HomeActivity.kt:55-66`). DiPlay reconnects after the reset (`DiPlayActivity.kt:4482-4520`).

**F17 (medium, verified): settings-mapping details.**

- **Stale Same Wi-Fi details.** Existing Wi-Fi details are written only when valid (`EngineSettingsApplier.kt:83-85`). Clearing them in CarLink leaves DiPlay's old values, and `CarPlayPreflight` validates DiPlay's (stale) copy (`CarPlayPreflight.kt:38-41`). Always write or clear them for a SAME_WIFI session, and pre-flight against CarLink's own values.
- **Location reporting.** `sendCarLocation` (CarLink) defaults to true, against DiPlay's false. The host then asks for location before every start (`CarPlayHostActivity.kt:697-699`). When it is denied, DiPlay saves false (`:226-233`) and CarLink rewrites true on the next session. Map it to `sendCarLocation && fine-location granted`.
- **Car-hotspot band is forced to AUTO** on every write (`EngineSettingsApplier.kt:81`).
- **Verified correct:** key names, types and enum strings match DiPlay. Night-mode keys, scale/fps/width ranges, `WirelessHotspotMode` names and Wi-Fi Direct band constants are all consistent. Ordering is correct: settings are written on the worker before `start()`, and the host re-reads them on resume.

**F18 (low, verified): DiPlay text and behaviour still reaching the driver.**

- Raw engine messages on the waiting screen, for example "No AirPlay TCP after CarPlay StartSession" (`setConnectionStage(status.describe())`, `CarPlayHostActivity.kt:4069-4071`).
- English-only notification text "CarPlay connection running" / "Disconnect" (`DiPlaySessionService.kt:39-41`).
- A raw `Toast` that bypasses `showToast` (`CarPlayHostActivity.kt:2725`).
- CarLink's `toastHandler` drops **every** projection toast, including real dead-end errors (`app/.../CarLinkApplication.kt:64-67`).

**What CarLink does right [V]:**

- Pre-checks MFi, the iPhone selection and hotspot details.
- Writes prefs before opening the host.
- Selects the phone.
- Passes the USB attach intent so the host forces wired.
- Ignores USB re-enumeration attaches.
- Runs one engine at a time.
- Debounces BT wakes.
- Uses overlay or a full-screen notification for background starts.
- Removes conflicting receivers and launcher entries.

---

## 11. Recommendations for a "just works" host

### 11.1 Defaults

| | Head unit | Spare tablet |
|---|---|---|
| USB (iPhone) | Auto-start on attach (already done); USB auto-confirm accessibility offered in onboarding; VPN consent obtained during onboarding | Same; most tablets need a USB-C OTG-capable port and a data cable |
| Wireless default | **Car hotspot on 5 GHz when the unit has one and its credentials are known or readable** ([M] #131), else Same Wi-Fi if the unit is on a plain router, else Wi-Fi Direct `AUTO_5_GHZ` | **Same Wi-Fi first** (the tablet is usually on home Wi-Fi; MatePad #299 proven), then Wi-Fi Direct `AUTO`. Car hotspot only on explicit choice |
| BYD features | Only with the BYD profile (§12) | Off |
| Auto start | BT ACL of a known iPhone → start (overlay permission required on Android 10+) | Same; plus a "Connect" tile |

### 11.2 What to do automatically

1. **Onboarding:**
   1. Grant in one flow: BT_CONNECT/SCAN, NEARBY_WIFI, POST_NOTIFICATIONS, FINE_LOCATION (needed to read the SSID), RECORD_AUDIO, ACCESS_LOCAL_NETWORK (SDK ≥ 37), VPN consent (`VpnService.prepare`), overlay, battery exemption.
   2. Check `authenticationError()`.
2. **iPhone selection:** list bonded devices (DiPlay's `choosePhone` semantics). Guide pairing with "Open Bluetooth settings" and tell the user to accept **"Use CarPlay"** on the iPhone. Remember per phone which path last worked.
3. **Same Wi-Fi:**
   - Fill the SSID from the joined network.
   - Ask only for the password.
   - Read `currentSecurityType` and warn on SAE, OWE or open-with-password.
   - Refuse to start with two Wi-Fi networks active.
   - Run a **LAN self-test** before the first attempt: bind :7000 and ask the user to … [I] or, better, rely on DiPlay's FIRST_TCP result plus the iPhone-side check "is your iPhone on <SSID>?".
4. **Path fallback (fixes F1):**
   - Same Wi-Fi → (after `FIRST_TCP_TIMEOUT` × 2) car hotspot / Wi-Fi Direct.
   - Bluetooth-stage failures → retry the same path. Never blacklist the network for them.
   - Refusals expire.
5. **Recovery:**
   - On `wifiResetRequired`, run `WifiDirectReset` automatically, once.
   - On exhausted retries, close the host, show CarLink's failure card, and re-arm the start on the next ACL_CONNECTED from that iPhone.
6. **Diagnostics:** keep a CarLink-side ring log of `CarPlayStatus` transitions with timestamps and the `startupFailure` reason. Force DiPlay debug logs on after `ensure()` (F6). Offer a one-tap export of DiPlay's diagnostic report.

### 11.3 Presenting waiting, failure and recovery

**Today, without vendor changes:**

- CarLink Home shows the staged progress from `CarPlayStatusBus` (it already maps statuses to 6 steps).
- The host waiting screen is fully restyled by `chromeStyler`.
- DiPlay strings are rewritten in `carlink_engine_overrides.xml`.

**Better, with the §7 hooks:**

- The host shows a CarLink-provided waiting view.
- The retry state is published.
- Only the projection surface remains DiPlay's.

Map statuses to driver language:

| Statuses | Driver text |
|---|---|
| Waiting* | "Unlock your iPhone and keep Bluetooth on" |
| ConnectingBluetooth / RunningWireless | "Connecting to <iPhone>" |
| HotspotReady + FIRST_TCP retry | "Waiting for <iPhone> to join <network>". After 2 retries, show the specific checks: same network, router isolation, WPA2 |
| Pairing | "Tap Trust on your iPhone" |
| RequestingIphonePermission | "Allow USB access" |

### 11.4 What cannot be made automatic, and why

- **Bluetooth pairing and the iPhone's "Use CarPlay" / "Trust" prompts.** Apple requires user consent on the phone, and Android pairing needs user confirmation.
- **The Wi-Fi password for Same Wi-Fi and for most hotspots.** Android does not expose saved passwords to apps (`docs/EXISTING_WIFI.md:16-17`). Some head units allow reading the system hotspot config (CarLink already tries `HotspotConfigReader`).
- **Router configuration** (client isolation, mDNS/multicast, WPA2 vs WPA3, band steering). This is outside the device.
- **VPN consent, overlay, accessibility, battery exemption, WRITE_SETTINGS.** These are one-time system consents by design; they can be grouped in onboarding but not skipped. Exception: on rooted or ADB-enabled head units, `appops` can grant them (#357).
- **OEM Bluetooth stacks that block third-party RFCOMM** (#227, #98). Wireless CarPlay requires the iAP2 bootstrap, so such units are wired-only.
- **Tablet iOS 27 black screen** (#249, open upstream) and decoder quirks (#13). These are engine-level; they can only be worked around (resolution, HEVC off).

---

## 12. BYD-specific vs generic

Gating summary: almost all BYD features are **default-off** behind `diplay_byd_outputs` /
`xcertplay_airplay` keys, or gated by exact firmware fingerprints, package presence or signatures.
They are mostly inert on non-BYD hardware. The exceptions, which run on every unit, are marked ⚠.

| # | Feature / assumption | Where | Gate (verified) | On a non-BYD head unit or tablet | Generic behaviour |
|---|---|---|---|---|---|
| 1 | ⚠ Navigation output bridges started on **every** controller | `CarPlayController.kt:172-173, 293-294, 314, 601-603, 744` → `hud/BydNavigationOutputs.kt:68-79` | **None** at start | `BydHudBridge.initialize` tries `bindService(com.ts.car.someip.service/…SomeIpServerService)` (`hud/BydHudBridge.kt:162-176`) and starts a repeating sender thread (`:76-81`). `BydClusterBridge` probes for Amap adapters (`hud/BydClusterBridge.kt:38-53`). `BydClusterMapPause` starts a periodic ticker (`hud/BydClusterMapPause.kt:44-51`). Every route frame is parsed (`:81-98`). **Misbehaves (helper audit, from code):** `bindService` returns false, so the binding never completes. Every 300 ms tick (`BydHudProtocol.kt:7`, `BydHudBridge.kt:106-109`) binds again and logs `bindService=false`, plus an ActivityManager "not found" warning, for the life of the process. That is about 200 log lines a minute and a 300 ms wakeup. `endNow` does not stop it | Skip `start()` unless the BYD profile is on; add a gate in `CarPlayController` init. Upstream fix: return early when `com.ts.car.someip.service` isn't installed |
| 2 | ⚠ `navigation_enabled` default **true** | `hud/BydOutputSettings.kt:29`; CarLink writes `bydDirections` default **true** (`app/.../UnifiedSettings.kt:24, 56`) | Pref | Enables HUD/cluster sends (no target, so no-ops) and `prepareProjectionDisplay` on app open (`BydNavigationOutputs.kt:17`) | Default false; true only with the BYD profile |
| 3 | Standalone HUD output | `hud/BydStandaloneHudOutput.kt:66-81` | API ≥ 28, package allowlist (incl. `com.carlink.app`), **exact BYD fingerprint**, signed system app version | Inert | Keep |
| 4 | `BydStarterBridge`, factory navigation, DiLink 3 cluster output and recovery journal | `hud/BydStarterBridge.kt`, `BydFactoryNavigationOutput.kt` (`.hudtest` only), `BydDiLink3ClusterOutput.kt:47` | Package allowlist / `.hudtest` suffix / `BydOutputSettings.enabled` | Inert except the journal restore on app open (`BydNavigationOutputs.onAppOpened`) | BYD profile only |
| 5 | ⚠ Wi-Fi scan pause over local ADB | `network/WifiScanPause.kt:14-36`, called for every non-Same-LAN wireless session (`CarPlayController.kt:1163, 2167-2170`) | `WifiScanPause.eligible(backend)` plus **local ADB at 127.0.0.1:5555 already authorized** (`adb/LocalAdb.kt:24-28`) | Usually unreachable, so a no-op. [I] On a generic unit with network ADB on, it would disable the Wi-Fi connectivity manager (scans) for the session; restored after. Could surprise | Off unless the BYD profile is on (or an explicit "pause Wi-Fi scans" setting) |
| 6 | Car-hotspot auto-start over local ADB / reflection `startTethering` | `network/CarHotspotTethering.kt:20-60`, `CarHotspotSettings.kt:10-17` | `auto_enable` (default false) plus WRITE_SETTINGS; UI only visible when `bydAvailable` | Reflection often fails on OEM ROMs, giving `UNSUPPORTED` | Generic: open the system tethering settings; auto-start only where it is proven |
| 7 | `CarHotspotStatus` heuristics ("BYD keeps wlan1 up…") | `network/CarHotspotStatus.kt:20` | — | Can return null/unknown on tablets and containers (#299) | Treat null as "ask the user" |
| 8 | Local-only hotspot BYD radio workarounds; LOHS disabled | `network/LocalOnlyHotspotManager.kt:107-270`; `AirPlayPersistence.kt:311` | Mode rewritten to MANUAL | Not reachable | Keep disabled |
| 9 | Android 9 Wi-Fi Direct legacy path, BYD band quirks | `network/P2pStartupRecovery.kt:30-50`, `docs/ANDROID9_WIFI_DIRECT.md` | API ≤ 28 | Generic Android 9 units use it (reboots on Wuling #457) | Keep; prefer Same Wi-Fi or hotspot on Android 9 |
| 10 | Dashboard / cluster map: DiLink 5.1 layout, DiLink 4 display, ADB cluster activity, centre-map overlay, map embed | `DiLink51ClusterLayout.kt:36` (exact fingerprint), `DiLink4ClusterDisplay.kt:12-13` (display name and 1920×720), `AdbClusterRouter.enabled`, `AirPlayPersistence.kt:632-712` (`cluster_map_enabled`, `center_map_overlay`, `adb_cluster_activity_enabled` default false) | Fingerprint / display name / prefs | Inert; `CarPlayHostActivity` probes displays (`:855-1145`) | Keep off; BYD profile only. [I] Generic second-display support would be a separate feature |
| 11 | Vehicle data to the iPhone: battery, charging connectors, wheel speed, parked state via ADB | `CarPlayHostActivity.kt:169-171, 1660, 3729`; `hud/BydBattery.kt`, `BydWheelSpeed.kt`, `BydParkedState.kt` | `battery_to_iphone`, `wheel_speed_to_iphone`, `video_while_parked`: default false | Inert | BYD profile only |
| 12 | Calls on dashboard, cluster song, HUD song, OEM cluster hold | `hud/BydCarPlayCall.kt:132-193`, `BydClusterSong.kt:120-131` | `carplay_calls`, `cluster_song`, `hud_song`, `oem_cluster_hold`: false | `attach()` only stores a context; frames are parsed but not sent | BYD profile only |
| 13 | Steering-wheel keys: `WheelKeyService` (accessibility), BYD key codes 304/312/327/328/331/353 | `WheelKeyService.kt:394-422`; `airplay/CarPlayMediaButton.kt:19-37`; `CarPlayHostActivity.kt:578` (`restoreIfNeeded`) | `WheelZoomSettings.anyEnabled` / call-key settings; restore only over authorized ADB | Codes never arrive; restore no-ops. Standard `KEYCODE_MEDIA_*` still work via `CarPlayMediaKeys` | Keep the generic media keys; BYD codes are harmless |
| 14 | `HomeScreenMonitor` (Usage access) for centre-map auto-hide | `CarPlayHostActivity.kt:1302-1303` | `center_map_auto_hide` (true) **and** `center_map_overlay` (false) **and** Usage access | Inert | BYD/cluster only |
| 15 | ⚠ OEM label "BYD" shown on the iPhone | `AirPlayPersistence.kt:113, 466-470` | Pref default | The iPhone shows a BYD brand label/icon on any car | Set to "CarLink" or the user's car brand (vehicle profile) |
| 16 | ⚠ `DiPlaySessionService.onTaskRemoved` ends BYD guidance | `DiPlaySessionService.kt:57-61` | None | Harmless no-op | — |
| 17 | `<queries>` for `com.byd.*`, `com.ts.car.someip.service`, `com.byd.amapservice/automap` | `shared/src/main/AndroidManifest.xml` queries; `common` queries `com.byd.carsettings` | — | Visibility only | Fine |
| 18 | Package allowlists incl. `com.carlink.app` | `hud/BydStandaloneHudOutput.kt:68-71`, `BydStarterBridge.kt` (CarLink edit) | — | Inert without the fingerprint | Keep |
| 19 | Setup guide DiLink generation, `BydVehicleSettingsBackend`, `LocalizedOptions`, DiPlayActivity "BYD vehicle advanced" | `setup/DiLinkGeneration.kt`, `common/.../BydVehicleSettingsBackend.kt` | Standalone UI only | Never shown in CarLink | — |
| 20 | Experimental ADB boot-start repair | `hud/BydBootStartRepair.kt` (0.2.15) | Explicit action | Not called by CarLink | — |
| 21 | Defaults tuned on BYD: physical width 300 mm, resolution 100 %, 30 fps, `VoiceFilter` comment, audio stream IDs | `AirPlayPersistence.kt:524-527`, `media/VoiceFilter.kt:8`, `media/AndroidMediaSink.kt:1559` | Prefs | Reasonable generic defaults; physical width matters for tablets (set it from `DisplayMetrics`) | Compute the physical size from xdpi/ydpi on tablets |

Further items from the BYD audit (verified from code by the helper; I spot-checked the HUD bind path):

- **Steering-wheel key codes.** `airplay/CarPlayMediaButton.kt:20-34`: BYD voice keys 304 and 312 open Siri with no gate. On Android 14+ these codes are `KEYCODE_DEMO_APP_4` and `KEYCODE_RECENT_APPS`, so with CarPlay in front the Recent Apps key opens Siri. Gate them behind the BYD profile.
- **`BydClusterSong.note()` ignores the `cluster_song` setting** (`hud/BydClusterSong.kt:208-217`). It is called from `WheelKeyService` and tries an adb write. Low impact, because the wheel keys are off by default.
- **`AdbClusterActivity` is exported with no permission** (common manifest :81-92). It is BYD-only; disable it in the generic profile.
- **`WifiScanPause` runs only on SDK == 29 (Q)** and only with an already-approved adb (`network/WifiScanPause.kt:55-56`). In practice this is generic, not BYD-only.
- **CarLink's BYD detection is weak.** `isBydHeadUnit()` (`app/.../ui/settings/SettingsScreen.kt:166-167`) and `UiProfile.kt:43` only check `"byd"` in MANUFACTURER/BRAND. Older BYD Qualcomm builds report QUALCOMM/qti (`CarHotspotSetup.kt:15`). On those, CarLink hides the BYD switch while the outputs stay on (default true). Use `BydOutputSettings.available(context)` (`hud/BydOutputSettings.kt:153-164`) instead.
- **CarLink never calls `BydNavigationOutputs.onAppOpened()`** (`BydNavigationOutputs.kt:7-18`). DiPlay calls it from `DiPlayActivity.kt:318`. On a BYD this means a force-stopped OEM cluster hold or DiLink 3 cluster mode is never restored. In the BYD profile, call it at app start.
- **The Bonjour service name is hard-coded:** `deviceName = "DiPlay"` (`CarPlayHostActivity.kt:3716`), alongside the iAP2 name and label. This is branding, not BYD, and it has no hook.

### How a "vehicle profile" setting can switch these cleanly

Suggested `UnifiedSettings.vehicleProfile = GENERIC | BYD_DILINK3 | BYD_DILINK4 | BYD_DILINK5 | AUTO`.
`AUTO` detects BYD with any of:

- `Build.FINGERPRINT` starting with `BYD-AUTO/`;
- `Build.MANUFACTURER`/`BRAND` = BYD;
- `com.byd.carsettings` installed (it is already in `<queries>`).

`EngineSettingsApplier` writes these from the profile:

| Setting | GENERIC | BYD |
|---|---|---|
| `BydOutputSettings.setEnabled` | false | user choice |
| battery / wheel speed / calls / cluster song / HUD song / OEM cluster hold | false | user choice |
| `AirPlayPersistence.saveClusterMapEnabled`, `saveCenterMapOverlay`, `saveAdbClusterActivityEnabled` | false | user choice |
| `CarHotspotSettings.auto_enable` | false | ADB auto-start allowed |
| `saveOemLabel` | "CarLink" (or the user's brand) | "BYD" |
| `CarPlayHostIntegration.menuItems` | without cluster-related items | — |
| `WheelKeyService`, `UsbAutoConfirmService`, `AdbClusterActivity`, `MapEmbedService` | `PackageManager.setComponentEnabledSetting(...DISABLED)` (same mechanism `EngineCoordinator` uses for DiAuto receivers) | enabled |

**Missing gates, which need a small vendor change listed in VENDOR_CHANGES:**

- (a) `BydNavigationOutputs.start()` in the `CarPlayController` init, and `onAppOpened`. Add `CarPlayHostIntegration.vehicleIntegrations: Boolean` (default true) and check it there and in `DiPlaySessionService.onTaskRemoved`.
- (b) `WifiScanPause.eligible` should also check that flag.
- (c) The hard-coded iAP2 `name="DiPlay"` / `label="DiPlay"` (`CarPlayHostActivity.kt:161, 173`). Make them hookable for branding.

---

## Appendix: key file index

- Session owner: `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt` (onCreate 562; prerequisites 696-771; startCarPlay 4167-4271; status reporter 4064-4087; reconnect 4559-4640; shutdown 4776; describe 5099; `CarPlayBackgroundSession` 5184)
- Engine: `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt` (status 105-136; wireless 1088-1520; USB 1688-2050; backends 2090-2131; BT select 2172-2211; constants 2660-2672)
- Existing Wi-Fi: `shared/.../network/ExistingWifiManager.kt`; address policy `HotspotAddressPolicy.kt`, `WirelessHostAddress.kt`; Bonjour `CarPlayBonjour.kt`; watchdog `orchestration/FirstTcpWatchdog.kt`; policy `network/WirelessStartupPolicy.kt`
- Standalone pre-flight: `common/.../DiPlayActivity.kt:4408-4472`
- Settings: `common/.../AirPlayPersistence.kt`, `DiPlayBootstrap.kt` (`DiPlayPreferences`), `shared/.../hud/BydOutputSettings.kt`
- CarLink: `app/src/main/java/com/carlink/app/engine/{CarPlayEngine,CarPlayPreflight,EngineCoordinator}.kt`, `settings/{EngineSettingsApplier,EngineMappings}.kt`, `entry/{SystemEventReceiver,HomeLauncher}.kt`
