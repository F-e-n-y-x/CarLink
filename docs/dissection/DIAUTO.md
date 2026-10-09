# DiAuto dissection: the Android Auto engine under CarLink

> **Note (2026-10-09):** the CarLink v1 classes this document cites as they were when it was
> written (`EngineCoordinator`, `CarPlayEngine`, `engine/CarPlayPreflight`, `AndroidAutoEngine`,
> `AndroidAutoCommandReceiver`, `AndroidAutoStateReporter`, `AndroidAutoWifiFallback`,
> `EngineSettingsApplier`, `EngineMappings`/`WirelessPathSelector`) and the hooks
> `EXTRA_CARLINK_CONNECT` and `onPhoneWifiJoinFailed` have been removed; the v2 replacements are in
> `carlink-core/.../connection/` ([design/REBUILD_V2.md](../../design/REBUILD_V2.md)). Engine findings are
> unaffected.

This is the engineering reference for DiAuto (DiAuto 0.3.11, vendored at `vendor/diauto`), the Android Auto receiver that CarLink runs in its `:aa` process. It covers how the engine actually works, what it needs, how it fails, and how CarLink drives it.

**How to read the evidence**

- Paths are relative to `vendor/diauto/app/src/main/java/com/andrerinas/openheadunit/` unless they start with `app/`, `vendor/` or `docs/`. CarLink paths start with `app/src/main/java/com/carlink/app/`, shortened to `carlink/…`.
- Line numbers refer to the tree as of this writing (engine 0.3.11 plus the CarLink edits listed in `docs/VENDOR_CHANGES.md`).
- **Verified** means read in code, or stated with a measurement in DiAuto's own docs.
- **Inference** means reasoned from code, platform behaviour or field reports, but not proven on the user's hardware.
- "HU" means the head unit, which here is the Android device running CarLink: a car stereo or a tablet.

---

## 0. Executive summary

1. **The core engine is `AapService` plus `CommManager`.** Everything else is UI or entry points. A session needs exactly one Activity: `AapProjectionActivity`. The engine starts reading the AAP stream only after that activity's surface exists (`CommManager.startReading()` is called only from `aap/AapProjectionActivity.kt:846,1583`).
2. **Native wireless Android Auto (mode 3) is the default and the only wireless mode DiAuto still offers.** The HU listens on Bluetooth RFCOMM UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66`. The phone connects to it and is handed `{SSID, PSK, BSSID}` plus `ip:5288`. It then joins that network and dials TCP 5288 on the HU.
3. **The phone must be prompted over Bluetooth.** DiAuto "pokes" the phone by opening an RFCOMM socket to the phone's HFP-AG and then HSP-AG records, and holds it for 15 s. This creates the ACL link that makes Gearhead (the phone's Android Auto app) look for the HU.
4. **A tablet cannot be a Bluetooth car kit.** It has no HFP-HF and no A2DP-sink role, so the phone never reconnects to it on its own. Everything therefore depends on the poke or on the user connecting Bluetooth by hand.
5. **Gearhead joins with a `WifiNetworkSpecifier` that must match both SSID and BSSID exactly.** This is measured and quoted in `aap/NativeCredentialsPolicy.kt:66-69`.
   - A wrong, masked or missing BSSID means the phone never joins.
   - An app-requested specifier network is local-only. That is why the phone keeps its internet on mobile data during wireless Android Auto, even when it is "on the same router" (inference from platform behaviour, consistent with the field result).
6. **On every device seen so far, Wi-Fi Direct (P2P group owned by the HU) is the transport that "just works".**
   - It needs no password typing.
   - Its BSSID is recovered automatically from the IPv6 EUI-64 link-local address.
   - It hit 40–50 fps on the user's tablet.
   - The car-hotspot and "Same Wi-Fi" routes need correct credentials and the exact BSSID.
7. **CarLink's integration works but has sharp edges:**
   - The global `static-bssid` override.
   - Same Wi-Fi pushes the phone onto mobile data.
   - The engine is restarted on every wireless start.
   - Engine defaults CarLink never overrides: `auto-start-on-boot=true` brings Home to the front on every tablet wake, and `auto-connect-last-session=true` switches any allowed USB phone into accessory mode during a wireless start.
   - `START_STICKY` and `onTaskRemoved` resurrect a "phantom" engine that CarLink does not observe.
   - Actionable engine toasts (Wi-Fi off, P2P in use) are silently dropped.
   - The system Location toggle is not checked.
   - Upstream Open Headunit has since fixed two things DiAuto still does. DiAuto drops the Bluetooth channel after the handoff, which causes AA 17.8 re-dial stutter. It also makes a new Wi-Fi Direct name for every group, giving 9 s connects instead of 1 s (§9.6, §10.11).

   Details are in §10 and the fixes in §11.

---

## 1. Architecture map

### 1.1 Components (from `app/src/main/AndroidManifest.xml`, plus the `github` and `debug` flavor manifests)

| Component | Kind | What it does | Engine core or standalone UI? | In CarLink |
|---|---|---|---|---|
| `aap.AapService` | Foreground service (`connectedDevice\|mediaPlayback`, `stopWithTask=false`) | Owns the session. Runs the USB receiver and the wireless server on TCP 5288, plus Native AA (Bluetooth handshake, Wi-Fi Direct and hotspot credential providers), NSD discovery, Nearby, MediaSession, car mode, night mode, wake detection, the boot-loop guard and the notification. Starts the projection activity. | **Core** | `:aa`. Driven by `carlink/engine/AndroidAutoCommandReceiver.kt`. |
| `aap.AapProjectionActivity` | Activity, `singleTask`, exported | The video surface, touch and keys, the loading/reconnecting overlay, the exit menu and PiP. **Required for any session** (see §1.3). | **Core** (UI that cannot be avoided) | `:aa`. Chrome restyled through `HostIntegration.chromeStyler`. |
| `location.GpsLocationService` | Service | Feeds HU GPS to the phone (`gps-navigation`). Started in `onConnected` (`aap/AapService.kt:1024`). | Core (optional feature) | `:aa` |
| `aap.DummyVpnService` (github flavor) | VpnService | Legacy Self Mode on AA < 17.4. `VpnControl.startVpn` is **never called** in current code; only `stopVpn` is (`aap/AapService.kt:2701`). | Legacy, unused | `:aa`. No VPN prompt ever shows. |
| `app.UsbAttachedActivity` | Translucent activity, `USB_DEVICE_ATTACHED` filter matching all devices | Switches a normal-mode Android device to AOA. Handles re-enumerated accessories (`app/UsbAttachedActivity.kt:72-167`). | Entry point | Filter removed; `UsbRouterActivity` replaces it. |
| `main.MainActivity` | Launcher, maps/navigation/geo handler, `headunit://selfmode` | DiAuto's home. Starts `AapService` on create (`main/MainActivity.kt:161-162`). Clears the boot-loop guard. Shows the onboarding wizard. Runs the "auto-connect" pill/overlay. Recovers the BYD HUD journal (`:122`). | Standalone UI | Not exported, no filters, never opened by CarLink |
| `main.SettingsActivity`, `main.OnboardingActivity` | Activities | Settings and the setup wizard (welcome, safety, permissions, ready) | Standalone UI | Present but unused |
| `main.AutomationActivity` | Translucent, **exported** | `ACTION_CONNECT`/`DISCONNECT`/`START_SELF_MODE`/`STOP_SERVICE`/`SET_NIGHT_MODE`, plus `headunit://connect\|disconnect\|nightmode\|exit` (`main/AutomationActivity.kt:43-133`) | Automation entry | **Still exported in CarLink** (merged, not neutered). See §10.9. |
| `app.BootCompleteReceiver` | Receiver (boot, QUICKBOOT, MTK IPO, FYT/GLSX/Microntek ACC) | Starts `AapService` (boot, screen-on, USB or Wi-Fi variant). Counts boot-loop strikes (`app/BootCompleteReceiver.kt:30-71`). | Entry | Removed; `SystemEventReceiver` replaces it |
| `app.AutoStartReceiver` | Receiver `ACL_CONNECTED` | If the device is in `auto-start-bt-macs`, sends `ACTION_BT_AUTO_START` and tries to start `MainActivity` or a full-screen notification (`app/AutoStartReceiver.kt:21-82`) | Entry | Removed |
| `app.WifiAutoStartReceiver` | Receiver `STATE_CHANGE`, **also registered dynamically by AapService** | Starts the service when joined to `auto-start-wifi-ssid` (`app/WifiAutoStartReceiver.kt:40-112`) | Entry | Manifest copy removed. **The dynamic copy still runs** (`aap/AapService.kt:1414-1419`). It is inert while `auto-start-on-wifi=false`. |
| `app.RemoteControlReceiver`, `connection.CarKeyReceiver`, `androidx…MediaButtonReceiver` | Receivers (priority 1000, proprietary key broadcasts) | Steering-wheel keys, OEM key intents (Microntek, FYT, NWD and others) | Core (input) | Shipped disabled. Enabled only while Android Auto is engaged (`carlink/engine/EngineCoordinator.kt:272-305`). |
| `FileProvider`, `rikka.shizuku.ShizukuProvider` | Providers | Log export; Shizuku root/ADB helper for `SUExecutor` | Support | `:aa` |

CarLink additions in `:aa`:

- `AndroidAutoCommandReceiver`: commands.
- `AndroidAutoSettingsProvider`: the single writer of DiAuto's `settings` prefs.
- `AndroidAutoStateReporter`: mirrors `CommManager.connectionState` to the main process.
- `AndroidAutoWifiFallback`: the join-failure hook.

### 1.2 Engine core vs UI

- **Core:** `App`/`AppComponent` (one `Settings`, `VideoDecoder`, `AudioDecoder`, `CommManager` per process; `AppComponent.kt:14-30`), `AapService`, `CommManager`, `aap/*` (protocol, transport, policies), `connection/*`, `decoder/*`, `AapProjectionActivity`, `view/*`.
- **Standalone UI:** `main/*` (MainActivity, HomeFragment, ConnectionSetupFragment, SettingsFragment, OnboardingActivity, diagnostics fragments), `UsbAttachedActivity`, `AutomationActivity`.
- **The engine casts the application context to `App`** (`App.provide()` in about 100 places). That is why `CarLinkApplication` extends `App`, and why engine startup is scoped to `:aa` through `initializesAndroidAutoEngine()` (`App.kt:38,109`; `carlink/CarLinkApplication.kt:22-28`).

### 1.3 UI that a session strictly requires, and why

| Requirement | Why | Evidence |
|---|---|---|
| **`AapProjectionActivity`** | `CommManager` stops at `HandshakeComplete`. The inbound loop (`startReading`) runs only after the activity has set the decoder surface. Without the activity there is no audio either: the message loop never starts. | `connection/CommManager.kt:483-535`; `aap/AapService.kt:844-846` launches it on `HandshakeComplete`; `aap/AapProjectionActivity.kt:843-846,1574-1583` |
| **Overlay permission (`SYSTEM_ALERT_WINDOW`)**, effectively required on Android 10+ | `AapService` starts the projection from the background. On API ≥ 29 the order is: DIRECT is blocked, so it tries the OVERLAY trampoline (a 0×0 overlay window, then `startActivity`), then a full-screen-intent NOTIFICATION. Without the overlay, the projection appears only as a heads-up/full-screen notification (and on API 34+ only if the full-screen-intent special access is granted). | `aap/ActivityLaunchPolicy.kt:17-21`; `aap/AapService.kt:1030-1080, 2822-2849` |
| VPN | **Not required.** Only the removed Self Mode used it; `startVpn` is never called. | §1.1 |
| USB permission | Required for wired sessions. It is asked twice: once for the phone in normal mode (AOA switch) and again for the re-enumerated accessory `18D1:2D00/2D01`, unless the package is the default handler for the attach intent. | `aap/AapService.kt:2208-2219, 2134-2145`; `app/UsbAttachedActivity.kt:99-107` |
| Notification permission (API 33+) | The foreground service runs without it, but the boot, auto-start and projection full-screen notifications need it. | `aap/AapService.kt:1058-1080` |
| Bluetooth `BLUETOOTH_CONNECT` (API 31+) | Without it, `NativeAaHandshakeManager.start()` and the poke refuse to run, so there is no wireless at all. | `connection/NativeAaHandshakeManager.kt:288-294, 671-677` |
| Location permission **and the system Location switch** | For Wi-Fi Direct, Android masks the P2P MAC when Location is off. The handshake then **aborts** before sending credentials. | `connection/WifiDirectManager.kt:451-460`; `connection/NativeAaHandshakeManager.kt:1195-1212` |
| `NEARBY_WIFI_DEVICES` (API 33+) | Needed for P2P group creation and the local-only hotspot | `connection/LocalHotspotCredentialsProvider.kt:75` |

---

## 2. Session lifecycle

### 2.1 Connection states (`connection/CommManager.kt:86-117`)

`Disconnected(isClean, isUserExit)` → `Connecting` (USB open or TCP accept/connect) → `Connected` → `StartingTransport` (SSL/version handshake) → `HandshakeComplete` (projection launched) → `TransportStarted` (reading). On any failure the state goes to `Error(msg)` and then `Disconnected` at once.

- `isConnected` covers `Connected` through `TransportStarted`. It **excludes `Connecting`** (`:190-196`). `isBusy` includes `Connecting`.
- `Error` is usually never observed by collectors, because `startHandshake()` emits `Disconnected` with no suspension point after it (`aap/AapService.kt:854-866`; `connection/CommManager.kt:155-158`).
- `isClean=true` only when the phone sent ByeBye. `isUserExit=true` for `disconnect()` with defaults: UI Exit, `ACTION_DISCONNECT`, `ACTION_STOP_SERVICE`.

### 2.2 AapService intents (`aap/AapService.kt:1867-2035`, constants at `:3379-3399`)

| Action | Effect |
|---|---|
| none / `MAIN` | `checkAlreadyConnectedUsb()` (non-forced) |
| `ACTION_CHECK_USB` | Takes a boot wakelock, then `checkAlreadyConnectedUsb(force=true)` |
| `ACTION_START_WIRELESS` | Clears the boot-loop guard, then `initWifiMode()` (no-op if the mode is already active) |
| `ACTION_START_WIRELESS_SCAN` | Clears the user-exit cooldown and the boot-loop guard. Runs `initWifiMode(force=true)`, then starts the mode-2 helper discovery or a mode-1 NSD scan |
| `ACTION_NATIVE_AA_POKE` + `extra_mac` | Clears the user-exit flags. Re-arms mode 3 if it is not active or its listeners are closed, then calls `manualPoke(mac)` (`:1951-1981`) |
| `ACTION_BT_AUTO_START` | If mode 3, no session, listeners closed and no attempt in flight: clears the exit flags and runs `initWifiMode(force=true)` (`:1982-2007`). Otherwise does nothing. |
| `ACTION_DISCONNECT` | `commManager.disconnect()`, a user exit |
| `ACTION_STOP_SERVICE` | Broadcasts `ACTION_FINISH_ACTIVITIES`. Sends ByeBye if connected, then `stopSelf` (`:1882-1892`) |
| `ACTION_START_SELF_MODE` | Legacy loopback (§3.8) |
| `ACTION_NEARBY_CONNECT` | Mode-2 Nearby |
| `ACTION_CONNECT_SOCKET` | No-op marker |
| `EXTRA_BOOT_START` | Boot wakelock, then launches `homeActivity` (`:1905-1914`) |

The service returns `START_STICKY` (`:2034`). `onTaskRemoved` restarts it with a null intent (`:1773-1783`). Both matter for CarLink (§10.6).

### 2.3 `onCreate` (`aap/AapService.kt:712-804`), which runs on every service start

1. `startForeground`. If that throws (background FGS start), it calls `stopSelf`.
2. `UiModeManager.enableCarMode(0)` (`:807-817`).
3. Night mode, the state observer, receivers: the USB receiver; a runtime MEDIA_BUTTON receiver; a dynamic Wi-Fi auto-start receiver; and a wake-detect receiver for SCREEN_ON/OFF, POWER, SHUTDOWN, `WIFI_STATE_CHANGED` and OEM ACC/boot intents (`:1385-1461`).
4. `WifiAutoStartReceiver.checkAndStart`.
5. MediaSession set active.
6. Creates `NativeAaHandshakeManager`, `WifiDirectManager`, `SoftApCredentialsProvider`, `LocalHotspotCredentialsProvider` and `NearbyManager`.
7. Boot-loop guard, else `initWifiModeWithOptionalWait()` → `initWifiMode()`.
8. `checkAlreadyConnectedUsb()` (non-forced, but it proceeds because `auto-connect-last-session` defaults to true).
9. `registerNetworkMonitor()`.

### 2.4 `initWifiMode(force)` (`aap/AapService.kt:1616-1722`)

- Returns early if wireless is paused by the boot-loop guard (`:1621-1624`).
- Returns early if mode, strategy and native transport are unchanged and `force` is false.
- Otherwise it stops everything: wireless server, Nearby, handshake manager, both soft-AP providers, and Wi-Fi Direct when the new mode does not use it.
- For modes 1, 2 and 3 it then calls `startWirelessServer()`, which binds TCP **5288** with `SO_REUSEADDR` and retries 3×700 ms (`:3208-3296`). It registers NSD `_aawireless._tcp` only for mode 1 and mode-2 strategies 0, 3 and 4 (`:2418`).
- Mode 1: continuous NSD and subnet discovery for the phone's "Head unit server" on 5277.
- Mode 2: one of the helper strategies.
- Mode 3:
  - **External-Bluetooth check.** If `/dev/rf_serial`, `/dev/zj_bt_serial` or `rw.zlink.bt.type=extra` is present, the whole route is skipped unless a secondary Bluetooth service is named by hand (`:1695-1698`; `aap/ExternalBtPolicy.kt`).
  - Picks a transport: LOCAL_HOTSPOT → `localHotspotCredentialsProvider.start()`; HOTSPOT → `softApCredentialsProvider.start()`; WIFI_DIRECT → `wifiDirectManager.startNativeAaQuietHost()`.
  - Then `nativeAaHandshakeManager.start()`.

### 2.5 Triggers, end to end

| Trigger | Standalone DiAuto path | CarLink path |
|---|---|---|
| USB attach (normal-mode phone) | `UsbAttachedActivity` → AOA switch → re-enumerates as `18D1:2D00/01` → `AapService.onUsbAttach` (accessory) → `checkAlreadyConnectedUsb(force)` → permission → `connectUsbWithRetry` (4 tries, 1.5 s apart; `:2339-2355`). Service-side fallback 2 s after a normal attach (`:2041-2068`). | `UsbRouterActivity` → `EngineCoordinator.onUsbPhone` → `COMMAND_START_USB` → adds the device to `allow-devices` → `ACTION_CHECK_USB`, or switches it directly when last-session is off (`carlink/engine/AndroidAutoCommandReceiver.kt:54-75`) |
| USB detach | `onUsbDetach` → `disconnect(sendByeBye=false, isUserExit=false)` (`:2070-2077`). Accessory detach → re-check after 1.5 s (`:2079-2092`). USB reconnect is retried 3 s after an unexpected disconnect (`:1351-1367`). | Plus `EngineCoordinator.usbGoneCheck`: 6 s grace, then stop (`EngineCoordinator.kt:81-88,129-134`) |
| Bluetooth ACL from a phone in `auto-start-bt-macs` | `AutoStartReceiver` → `ACTION_BT_AUTO_START` (re-arms mode 3) + MainActivity launch or notification | `SystemEventReceiver` classifies the phone → `HomeLauncher.open` → Home → `connectPhone` → `COMMAND_START_WIRELESS` |
| Manual (DiAuto Home "Connect phone") | `HomeFragment.selectPhone()` → sets `wifiConnectionMode=3` → `ACTION_NATIVE_AA_POKE(mac)` (`main/HomeFragment.kt:85-111`) | Home → `COMMAND_START_WIRELESS(address)` → **stop service, remove P2P group, clear `p2p_ownership`**, add the MAC to the poke targets, `ACTION_NATIVE_AA_POKE` (`AndroidAutoCommandReceiver.kt:91-116`) |
| Boot / ACC | `BootCompleteReceiver` (counts boot-loop strikes; `app/BootCompleteReceiver.kt:35-47`) → `AapService(EXTRA_BOOT_START)` → `launchMainActivityOnBoot` (direct, else overlay trampoline, else full-screen intent; `:2781-2879`) | `SystemEventReceiver` → `HomeLauncher.open(BOOT)` when `startOnBoot` is set |
| Hibernate wake (screen off > 60 s, or an OEM ACC intent while the service is alive) | `wakeDetectReceiver` → `onHibernateWake` → launches home if `auto-start-on-boot` (**default true**); checks USB if `auto-start-on-usb` (`:532-634`) | **Still active in `:aa` while AapService runs** (see §10.6) |
| Wi-Fi joined to `auto-start-wifi-ssid` | `WifiAutoStartReceiver` | Inert (`auto-start-on-wifi=false`) |
| NSD / helper modes | §3.6–3.7 | Not used |

### 2.6 Teardown, cooldown, reconnect

`onDisconnected` (`aap/AapService.kt:1158-1294`) runs these steps:

1. Stops BYD outputs, GPS, night mode, focus and car-key receivers; deactivates (does not release) the MediaSession.
2. **Mode 3 + user exit:** `nativeAaHandshakeManager.stop()`. **Mode 3 + unexpected disconnect:** stop, then `initWifiMode(force=true)` after 1.5 s (`:1194-1207`). That re-init recreates the P2P group and re-arms the poke.
3. **User exit:**
   - Wi-Fi Direct: wait for the teardown, then remove the P2P group, so the phone really leaves (`:1217-1220`).
   - Car hotspot: `UserExitHotspotPolicy` gives `DISABLE` only when `auto-enable-hotspot` is on and teardown is not proven unsafe; otherwise `WARN_LEFT_UP` (`aap/UserExitHotspotPolicy.kt:44-66`).
4. **User-exit cooldown, 5 s:** the TCP 5288 server rejects new sockets (`:1287-1291, 3272-3277`).
5. `scheduleReconnectIfNeeded` (`:1305-1379`):
   - Server mode: re-arm discovery 2 s later.
   - USB: re-check after 3 s, unless the user exited.
   - Mode 1 after an unclean drop: one-shot scan.
6. Process exit happens only when `kill-on-disconnect=true`. That path finishes **all of the package's app tasks** and calls `System.exit(0)` (`connection/CommManager.kt:550-567, 826-843`; `aap/AapService.kt:1861-1864`).

`maybeTearDownBeforeLinkGoes` closes the session cleanly on `SHUTDOWN`, and on `WIFI_STATE_DISABLING` for sessions that ride station Wi-Fi (`aap/AapService.kt:2510-2556`; `aap/LinkLossTeardownPolicy.kt:87-110`).

### 2.7 Timeouts and retries (all verified in code)

| What | Value | Where |
|---|---|---|
| Poke hold / retry cadence | 15 s hold, loop every 15 s; manual poke holds 20 s, **once** | `NativeAaHandshakeManager.kt:759,785,824` |
| Initial poke delay | 2 s | `:693` |
| Wait for credentials inside a handshake | 60 s, refreshing every 10 s | `:51,1161-1177` |
| Bluetooth settle before the first write | 300 ms | `:1145` |
| Version response (Type 5) | 3 s | `aap/WppHandshakeSession.kt:128` |
| Phone's Type 2 after Type 1 | 15 s | `:132` |
| Delay before Type 3 | 1 s | `NativeAaHandshakeManager.kt:998` |
| Settle after Type 3 (association, DHCP, TCP) | 45 s, +15 s per `WifiConnectStatus(0)`, max 90 s | `aap/NativeHandoffPolicy.kt:22,28`; `WppHandshakeSession.kt:135` |
| Handshake backoff | after 5 silent handshakes, refuse until a manual poke or restart | `NativeHandoffPolicy.kt:98,114` |
| "Phone answers pokes but never calls back" warning | every 3 pokes, if never accepted | `:75,91-94` |
| P2P join watchdog | 60 s with no client → recreate the group (max 4; from the 2nd attempt, "standard"/2.4 GHz) | `connection/WifiDirectManager.kt:50-52,1507-1523` |
| P2P create retries | 4 at 5 GHz, then standard | `:38-40,1272-1285` |
| Soft-AP resolve budget | 30 s polling at 1 s; auto-enable after 5 s if allowed | `connection/SoftApCredentialsProvider.kt:61-67` |
| Wi-Fi AAP read timeout | 15 s without a header → "WiFi read timeout" → disconnect | `aap/AapReadSingleMessage.kt:26,38-40` |
| TCP connect / soTimeout | 5 s / 10 s | `connection/SocketAccessoryConnection.kt:184,198` |
| User-exit cooldown | 5 s | `AapService.kt:3423` |
| Mode-3 re-init after an unexpected drop | 1.5 s | `:1203` |
| Boot-loop guard | 3 boot starts that each die within 30 s → wireless paused | `aap/BootLoopPolicy.kt:24,33` |

---

## 3. Wireless, in depth

### 3.1 `wifiConnectionMode` values (`utils/Settings.kt:478-495`, default **3**)

| Value | Name | Status in 0.3.x |
|---|---|---|
| 0 | Manual | Removed from the UI. `initWifiMode` does nothing. |
| 1 | Auto ("Headunit Server") | Legacy. The HU scans the LAN for the phone's developer "Head unit server" on 5277 and connects as client (`aap/DiscoveryModePolicy.kt`; `connection/NetworkDiscovery.kt`). |
| 2 | Helper ("Wireless Helper"/WiFi Launcher) | Legacy. Needs a companion phone app. Strategy (`helper-connection-strategy`, default 2): 0 NSD, 1 Wi-Fi Direct, 2 Google Nearby, 3 phone hotspot (host), 4 HU hotspot (passive) (`utils/Settings.kt:1433-1436`). |
| 3 | **Native AA** | **Default and recommended.** No phone app. Uses Android Auto's built-in wireless projection protocol (WPP). |

`docs/REVIEW.md:10-12`: "Removed self-mode, VPN permission requests, Nearby/helper discovery and manual IP selection code from the home screen. Legacy transport implementations remain internally available."

### 3.2 Native AA protocol, step by step

**Head-unit side, before anything:**

1. AP/P2P up → credentials `{ssid, psk, ip, bssid}` are published to `onNativeCredentials` (`aap/AapService.kt:2434-2450`).
2. TCP 5288 bound (`WirelessServer`).
3. RFCOMM listeners open on the AA UUID `4de17a00-52cb-11e6-bdf4-0800200c9a66` ("AA BT Listener") and on HFP `0000111e` ("Hands-Free Unit", a minimal AT responder: `+BRSF`, `+CIND`, `OK`). They open on the primary radio and on any secondary Bluetooth radios (`connection/NativeAaHandshakeManager.kt:44-45, 316-398, 489-533`).

**Wake (poke).** When credentials arrive and no session or user exit is active, `triggerPoke()` runs (`:664-788`):

- Targets are each bonded MAC in `auto-start-bt-macs`. If the list is empty, **every bonded device** is poked (`:708-732`).
- For each target it calls `createRfcommSocketToServiceRecord(HFP_AG 0000111f)` and then `HSP_AG 00001112`, connects, and **holds for 15 s** (`:597-652`; `aap/BluetoothWakePolicy.kt:18-35`).
- It skips a phone that is not bonded (that would cause a pairing prompt), and skips when the HU already holds a hands-free link to that phone (`BluetoothWakePolicy.kt:97,123`; CarLink patch in §4).

**Phone connects back** to the AA UUID. `handleHandshake` (`:876-1291`) then:

- Saves the phone's MAC into `auto-start-bt-macs` (`:941-948`).
- Drives `WppHandshakeSession` (`aap/WppHandshakeSession.kt:97-333`):

```
HU -> 4 WifiVersionRequest     (only if native-wifi-version-exchange; default off)
HU <- 5 WifiVersionResponse
HU -> 1 WifiStartRequest {ip_address, port=5288, status=0}
HU <- 2 WifiInfoRequest
HU -> 3 WifiInfoResponse {ssid, key, bssid, WPA2_PERSONAL, access_point_type STATIC(P2P)|DYNAMIC(AP)}
HU <- 7 WifiStartResponse (ack; status!=0 = rejected)
HU <- 6 WifiConnectStatus (0 = joined; non-zero = failed, e.g. -3, -11)
      8/9 ping/pong at any time (echoed)
```

Before Type 1 it checks two things:

- **BSSID usability.** For Wi-Fi Direct and the local hotspot, a masked or empty BSSID **aborts**. For a car hotspot it sends an empty BSSID and logs that most phones refuse it (`connection/NativeAaHandshakeManager.kt:1195-1223`; `aap/NativeCredentialsPolicy.kt:81-85`).
- **Port 5288 bound.** If it is not, it tries to start the server for up to 4 s (`:1231-1243`).

**Phone joins and dials.** The phone joins the network and opens TCP to `ip:5288`. `WirelessServer.accept` → `commManager.connect(socket)` (`aap/AapService.kt:3262-3283`). The handshake sees `isConnected` → `TcpSessionUp` → `CompleteSuccess` → **closes the AA listeners**, to avoid a reconnect storm (`:1025-1035, 460-467`).

**AAP over TCP** then follows the same path as USB: version → SSL → service discovery → `HandshakeComplete` → projection activity → `startReading`.

### 3.3 Native transports (`native-ap-transport`, `utils/Settings.kt:1446-1460`)

`NativeTransport.fromSetting`: 0 = WIFI_DIRECT, 1 = HOTSPOT, 2 = LOCAL_HOTSPOT (`aap/NativeCredentialsPolicy.kt:4-23`).

- The default stored value is **1 (car hotspot)** in 0.3.11. `docs/CONNECTION_SETUP.md:11-12`: "Built-in car hotspot is the default; Wi-Fi Direct and USB remain available."
- The enum's comment still says "WIFI_DIRECT… The default".
- **Value 2 is unreachable**: the getter rewrites a stored 2 to 1, and the setter writes 1 for 2 (`Settings.kt:1452-1460`; `docs/LOCAL_HOTSPOT_TEST.md:1`).

#### 3.3.1 Wi-Fi Direct (P2P quiet host), `connection/WifiDirectManager.kt`

**Steps:**

1. `startNativeAaQuietHost()` (`:1129-1197`) requires Wi-Fi on. On API < 29 it enables Wi-Fi itself; on API ≥ 29 it shows a toast "Native AA requires Wi-Fi. Please turn it on." and **returns** (`:1161-1173`).
2. Removes its own old group and creates a group named `DIRECT-XX-<name>` with a 12-character passphrase (`:1334-1346`). On API 29+ it uses `WifiP2pConfig.Builder`. It **prefers the station's current non-DFS 5 GHz channel** on the first attempt (verified on a car to remove stalls: `docs/REVIEW.md:128-146`), else 5 GHz band. It retries 4×, then falls back to the "standard" `createGroup` (`:1220-1297`).
3. Below API 29 there is an optional hidden `setWifiP2pChannels` for 5 GHz (`p2p-legacy-5ghz`, default off; `utils/Settings.kt:391-403`).
4. `onGroupInfoAvailable` resolves the interface and BSSID (`:437-790`).
5. The group is a "quiet" host: no `discoverPeers`, because discovery takes the group owner off-channel and breaks DHCP (`aap/NativeHandoffPolicy.kt:117-126`).

**Interface.** `group.interface` is often null on Android 11+, so it is found by IP 192.168.49.1 (`:504-512`).

**BSSID resolution chain**, in order:

1. `static-bssid` (if not `"0"`).
2. Interface MAC.
3. **IPv6 EUI-64 link-local → MAC** (`connection/P2pInterfaceBssid.kt`; `docs/WIRELESS_BSSID_FIX.md`).
4. Last known value.
5. `localDeviceAddress`.
6. `group.owner.deviceAddress`.
7. Reflection and shell.

The wait loop prefers the group's own interface over fallbacks for 3 s (`docs/PR2_REVIEW.md:17`).

**Requirements:**

- Wi-Fi switched on (the radio, not necessarily associated).
- Location permission and the system Location switch on; otherwise the MAC is masked.
- `NEARBY_WIFI_DEVICES` (API 33+).
- No other app owning P2P. If one does, the engine stands down: "Wi-Fi Direct is in use by another app" (`:1661-1675`).

**Proven:**

- On a BYD DiLink 5.1, P2P at 5200/5745 MHz gave 47–58 displayed fps after channel alignment (`README.md:50-52`; `docs/REVIEW.md:128-146`).
- On HiBy R4, the EUI-64 decoding was proven against a Samsung S25 Ultra scan (`docs/WIRELESS_BSSID_FIX.md:84-89`).
- On the user's Redmi Pad Pro with the S25 Ultra: 40–50 fps.

**Failure modes and how the engine handles them:**

| Failure | Detection / recovery |
|---|---|
| Masked BSSID (Location off, or a locked-down device) | Abort before Type 3, plus a log naming Location and Static BSSID (`NativeAaHandshakeManager.kt:1198-1211`) |
| Group lands on 2.4 GHz when 5 GHz was requested | Recreate, up to 2 times (`MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES`) |
| DFS or odd channel | Toast "which most phones cannot join" (`WifiDirectManager.kt:659`) |
| Phone never joins within 60 s | Join watchdog recreates the group, max 4 times, forcing standard after 2 |
| Credentials of a dead group delivered | `credentialsEpoch` fence (`:127-137`) |
| P2P churn during the phone's DHCP | Settle window: the poke and the watchdog stand down while `isHandoffSettling()` (`NativeHandoffPolicy.kt:3-16`) |
| Boot loops on some MT8163 units when a phone joins P2P | `BootLoopPolicy`; notice suggesting the hotspot transport (`aap/AapService.kt:2933-2966`) |

#### 3.3.2 Head-unit (car) hotspot, `connection/SoftApCredentialsProvider.kt`

**Steps:**

1. Pick the AP interface:
   - `hotspot-interface` if named. It must be up and hold a private IPv4; the engine waits for it.
   - Otherwise a heuristic: `ap*`, `swlan*`, `softap*`, `wlan*` preferred. Excluded: `p2p-`, `tun`, `dummy`, `apcli`, `sta`, `seth_lte`, `ccmni`, `rmnet`, `ethN`, `briotgw`, and the station's own IPv4 (`aap/SoftApNetworkPolicy.kt:34-123`).
2. SSID and PSK: `hotspot-ssid`/`hotspot-password` if set, else the reflected `getSoftApConfiguration()`. That read fails on most phones and tablets, and the engine then shows a forced toast (`SoftApCredentialsProvider.kt:216-225, 397-404`).
3. Refuse to publish if the framework says no AP is running, unless the interface was named by hand (`aap/NativeCredentialsPolicy.kt:108-109`).
4. BSSID chain: `static-bssid`, then `/sys/class/net/<if>/address`, then `getHardwareAddress()`, then the IPv6 EUI-64 address (`SoftApCredentialsProvider.kt:424-431`).
5. Watch `WIFI_AP_STATE_CHANGED`. Enabled or disabled triggers a re-resolve, and the engine re-enables an AP it turned on itself (`:122-145`).
6. Auto-enable after 5 s if `auto-enable-hotspot` is on; needs `WRITE_SETTINGS` and compatible firmware (`:264-276`).

**Requirements:**

- The user turns the hotspot on (5 GHz strongly advised).
- Credentials typed in, unless the firmware exposes them.
- A readable real BSSID.

**Proven:** working on BYD DiLink 5.1 after 0.3.11 (`docs/HOTSPOT_RECOVERY.md:24-30`). The failure that led to 0.3.11: eth0 was published instead of ap0, and the phone answered "WifiConnectStatus -11" in a loop (`:3-7`).

**Failure modes and how the engine handles them:**

| Failure | Detection / recovery |
|---|---|
| Phone refuses (`Type 6/7 status≠0`) | `Fail(wifiJoinFailed)` → `refreshHotspotCredentialsAfterJoinFailure()` (re-resolve only; `aap/AapService.kt:2558-2566`) → `ResumePoke`. CarLink's hook counts the refusals (§10). |
| No AP after 30 s | Logged once. Polling continues. |
| Config unreadable | Forced toast asking for manual name and password. |
| 2.4 GHz at 1080p/60 → sessions with no video | `VideoStarvationPolicy`: after 3 sessions in a row that reach the handshake but render no frame, log advice to switch to 5 GHz or lower resolution (`aap/VideoStarvationPolicy.kt:6-13,28,38-51`; `connection/CommManager.kt:915-930`). Advice only; nothing is changed automatically. |

#### 3.3.3 Local-only hotspot, `connection/LocalHotspotCredentialsProvider.kt` (unreachable)

- An app-owned `LocalOnlyHotspot` with a requested 5 GHz channel. On API 33 and API ≥ 36 it uses a custom `SoftApConfiguration` (`startLocalOnlyHotspotWithConfiguration` on 36+), with random SSID and passphrase (`:101-130`).
- It rejects 2.4 GHz results and reads the frequency through a native WEXT ioctl on older Android (`docs/LOCAL_HOTSPOT_TEST.md:17-23`).
- **Proven** once on DiLink 5.1 / Android 13 with a OnePlus 15 at 5745 MHz (`docs/LOCAL_HOTSPOT_TEST.md:13-15`).
- **Removed from the UI**, and value 2 migrates to 1.
- **Relevance to CarLink (inference):** this is the only transport that needs no password typing *and* no P2P. On an Android 16 tablet the public `startLocalOnlyHotspotWithConfiguration` exists. It deserves an experiment (§11).

#### 3.3.4 "Same Wi-Fi" (shared router), a CarLink construct on top of HOTSPOT

DiAuto has no shared-router mode. CarLink reuses the HOTSPOT transport with every value written by hand (`carlink/settings/EngineMappings.kt:182-190`):

- `hotspot-interface` = the car's station interface (e.g. `wlan0`)
- `hotspot-ssid` / `hotspot-password` = the router's
- `static-bssid` = the router BSSID the car is associated to (`WifiManager.connectionInfo.bssid`)
- `auto-enable-hotspot` = false

Because the interface is named by hand, the publish guard is bypassed (`NativeCredentialsPolicy.kt:108-109`). The phone is told: join `SSID`/`BSSID` with this password, then dial `<car's station IP>:5288`.

**Field result: it works once the password is right.** It has these properties:

- **Phone on mobile data.** The phone joins through Gearhead's `WifiNetworkSpecifier`, a local-only, app-requested connection without INTERNET capability. The phone's own saved-network association to the same router is dropped or not used for the default route, so its internet goes over mobile data. This is an inference from Android's network-request API semantics, matching the field observation and the code comment in `aap/NativeCredentialsPolicy.kt:66-69`.
- **BSSID must match exactly** (full-mask specifier). The car's station BSSID is the AP and band the *car* is on:
  - On a dual-band router with distinct BSSIDs, the phone is forced onto that band's BSSID.
  - On a mesh, the car's node.
  - When CarLink sent the *other* band's BSSID, the session stalled with "AapRead: WiFi read timeout (15000ms)" (`aap/AapReadSingleMessage.kt:38-40`).

    That message means the TCP/AAP session existed and then received nothing for 15 s. The phone therefore did join and dial, and the link then starved. Two possible causes, neither verified: a 2.4 GHz throughput ceiling (the same shape as `VideoStarvationPolicy`), or the phone roaming off a specifier pinned to a BSSID it was not on.
- **Routers with client isolation** (guest networks, many public/hotel Wi-Fi) block phone → car TCP. This route needs no multicast, since the IP is handed over explicitly.
- **The password must be typed**, because Android does not reveal saved Wi-Fi passwords.
- **Engine policy mismatch:** the engine believes it rides its own AP. `LinkLossTeardownPolicy` therefore won't close the session cleanly when station Wi-Fi is being disabled (`aap/LinkLossTeardownPolicy.kt:101-109`), and `UserExitHotspotPolicy` will log `WARN_LEFT_UP`.

### 3.4 Default and recommended mode

| | Value | Source |
|---|---|---|
| DiAuto default | mode 3, `native-ap-transport=1` (car hotspot) | `Settings.kt:493,1454` |
| DiAuto's guided setup | "Built-in car hotspot" first, "Wi-Fi Direct" as "Alternative setup · Requires the car's Wi-Fi switch on. A 2.4 GHz link may stutter." | `res/values/strings.xml` `setup_car_hotspot_hint`, `setup_wifi_direct_hint` |
| Recommendation for a non-BYD HU or tablet (this document) | **Native AA over Wi-Fi Direct by default.** Car hotspot only when its credentials are readable or entered and it is verified 5 GHz. Same Wi-Fi only as an opt-in. | §11 |

### 3.5 What is proven where (summary)

| Route | Proven on | Notes |
|---|---|---|
| Wi-Fi Direct | BYD DiLink 5.1 (A13) + OnePlus 15; Redmi Pad Pro (A16) + S25 Ultra (40–50 fps, field) | Needs BSSID recovery (automatic via EUI-64) |
| Car hotspot | BYD DiLink 5.1 (owner confirmation, 0.3.11) | Needs exact credentials and BSSID; 5 GHz |
| Local-only hotspot | DiLink 5.1 / A13 + OnePlus 15, one session | Disabled in the UI |
| Same Wi-Fi | Redmi Pad Pro + S25 Ultra (field, CarLink) | Phone on mobile data; exact BSSID required |
| Self Mode, Helper, Headunit Server, Nearby | Upstream Open Headunit era | Not exposed |

### 3.6 Helper modes (mode 2), legacy

- Strategy 0 (NSD): register `_aawireless._tcp` on 5288, scan the subnet for port 5289 (WiFi Launcher) and 5277; hold the probe to "wake" the helper (`connection/NetworkDiscovery.kt:274-301`).
- Strategy 1: a visible P2P group, re-advertised every 10 s.
- Strategy 2: Google Nearby `P2P_POINT_TO_POINT`, service `com.andrerinas.openhu` (`connection/NearbyManager.kt:49-50`).
- Strategies 3 and 4: wait on 5288.

All of these need a phone-side helper app (Wireless Helper / WiFi Launcher). They are not relevant for "just works".

### 3.7 Headunit Server / NSD (mode 1), legacy

The phone must have Android Auto developer settings enabled and "Start head unit server" running. The HU scans gateways, then the /24, for 5277.

`aap/UnresponsivePeerPolicy.kt:113-129` documents that this server wedges permanently once a peer vanishes without closing: "only restarting the server on the phone does." Not viable for "no fiddling".

### 3.8 Self Mode, legacy

The HU is its own phone. It connects to `127.0.0.1:5277` on AA ≥ 17.4, with a toast asking the user to start the Headunit Server. On older AA it launches `WirelessStartupActivity` with fake network extras (`aap/AapService.kt:2983-3136`).

### 3.9 Phone side

**What the code and docs establish (verified):**

- The phone needs Android Auto with wireless support, Bluetooth on and **Wi-Fi on**. Being joined to a network is not required.
- The phone must be **paired with the HU**. The poke refuses unbonded devices (`BluetoothWakePolicy.kt:114-123`).
- Gearhead uses a full SSID+BSSID `WifiNetworkSpecifier`. Missing BSSID → `WIFI_INVALID_BSSID` / type 6 `status=-3` (`NativeCredentialsPolicy.kt:66-69`).
- If the phone answers pokes but never opens the AA channel, its Android Auto is bound to another device advertising the AA record, such as the car's OEM module (`NativeHandoffPolicy.kt:77-94`).
- Android Auto 17.4 changed things:
  - Self Mode needs the head unit server (`AapService.kt:2983-2992`).
  - The optional Type 4 version exchange exists for "a reporter whose unit broke on Android Auto 17.4" (`Settings.kt:1462-1474`).
- "A car internet plan is not required; mobile data depends on your phone's network settings" (`strings.xml` `setup_pair_instructions`).

**Why the phone uses mobile data (inference):**

- During any Native wireless session the phone's Wi-Fi radio is joined to a local-only network: the HU's P2P group, the HU's AP, or a specifier pinned to the router.
- Android routes default traffic over cellular, unless the HU's AP shares its upstream and Gearhead's request lets the network become default. The specifier API does not let it.
- Samsung "Switch to mobile data" / Adaptive Wi-Fi and similar features only reinforce this.

**What upstream adds (§9.4):**

- Gearhead needs internet momentarily during setup (open-headunit #698).
- Phone features that kill no-internet Wi-Fi must be off: "Switch between networks", Samsung "Switch to mobile data", Xiaomi "Network acceleration", "Avoid poor connections" (#203; wiki Troubleshooting).
- Denying Android Auto mobile data breaks native wireless (discussion #565).
- A phone that once saved the HU as a wireless car auto-starts when Bluetooth connects (#860). That is useful for a "just works" host.

---

## 4. Bluetooth

### 4.1 Roles

| Role | Car stereo (typical Android HU) | Tablet / phone used as display |
|---|---|---|
| HFP Hands-Free (HF, `HEADSET_CLIENT`) | Yes (system Bluetooth app, or an external module) | **No.** A tablet is an Audio Gateway, not HF. |
| A2DP Sink | Usually yes | **No** (A2DP source only) |
| Phone reconnects automatically after pairing | Yes, the phone auto-connects HFP/A2DP to a known car kit | **No profile for the phone to connect**, so no ACL comes up unless something initiates it |
| SDP record `4de17a00…` (AA) | Published while DiAuto listens | Same |

That is why "wireless only started after the user connected Bluetooth by hand" on the tablet: nothing brings the ACL up except the poke.

### 4.2 The poke (`connection/NativeAaHandshakeManager.kt:591-788`; `aap/BluetoothWakePolicy.kt`)

- RFCOMM `connect()` to the phone's **HFP-AG (`0000111f`)**, else **HSP-AG (`00001112`)**, held for 15 s. Order and UUIDs are matched to WirelessAndroidAutoDongle and openautolink (`BluetoothWakePolicy.kt:17-35`).
- Rules:
  - `mayPoke` only for a confirmed bond.
  - `shouldPoke` false when the HU already holds a hands-free link. Upstream counted *any* `HEADSET` (gateway) link. **CarLink patch:** a gateway-role link stands the poke down only if its peer is the poked phone (`BluetoothWakePolicy.kt:44-59`; `utils/BluetoothHelper.kt:118-174`), so a tablet's own earbuds no longer block every poke.
  - Skipped while a handshake or handoff is settling, or while a session is up (`NativeHandoffPolicy.kt:69-70`).
- Measured side effect on a real HU (`BluetoothWakePolicy.kt:83-96`): the poke takes the phone's single hands-free slot, and the HU's own HFP client drops for minutes. Hence the skip when an HF link exists.
- The HU also listens on HFP `0000111e` with a stub AT responder, because some phones need it. That responder cannot carry calls (`:469-483`).
- **The poke cannot replace pairing.** Pairing is a user action on both devices.
- **Inference:** the phone must have the HU's SDP record with the AA UUID. Pairing while the engine is listening makes that likely, but it is not proven.

### 4.3 Pairing requirements

- The phone must be bonded with the HU. The engine never initiates pairing.
- DiAuto Home lists `adapter.bondedDevices`. With none, it shows "Pair" guidance and a link to Bluetooth settings (`main/HomeFragment.kt:85-133`).
- Dual-radio HUs: listeners also open on secondary radios found through `ServiceManager`, or on `manual-secondary-bt-service-name` (`NativeAaHandshakeManager.kt:364-398`).
- External-module HUs (`/dev/rf_serial`, `rw.zlink.bt.type=extra`): the native wireless path is disabled (`aap/ExternalBtPolicy.kt`).

### 4.4 Auto-start lists

- `auto-start-bt-macs`, a string set mirrored to device-protected storage (`utils/Settings.kt:754-766, 1290-1313`). It is:
  1. the allowlist for `AutoStartReceiver` (removed in CarLink);
  2. the poke target list (`NativeAaHandshakeManager.kt:708-732`).
- Filled automatically by every phone that opens the AA channel (`:941-948`). CarLink also adds the chosen phone (`AndroidAutoCommandReceiver.kt:125-134`).
- Pruned only on a confirmed NOT_BONDED or a malformed MAC, never when the adapter is unreadable (`BluetoothWakePolicy.kt:138-139`).
- Each 15 s cycle pokes **every** listed bonded MAC in turn, with a 15 s hold on each one that answers. **CarLink inference:** with two or more phones listed, an absent phone costs about 2×5 s page timeouts per cycle, and a present-but-not-wanted phone gets woken too.

### 4.5 What fails on tablets (verified against code; field-confirmed where noted)

1. **No automatic ACL** (field result 1). CarLink's `SystemEventReceiver` waits for an ACL that never comes on a tablet. Only an explicit start (Home tap → poke) or a manual Bluetooth connect works.
2. **Upstream skipped every poke** when the tablet had earbuds connected. CarLink has fixed this (VENDOR_CHANGES).
3. `headsetPeers` returns null on the main thread, which falls back to "block" (`BluetoothHelper.kt:154`). The poke runs on IO, so this is fine.
4. **Calls and media:** a tablet cannot be the phone's hands-free or A2DP sink. Calls and audio must go through Android Auto's own channels, so `music-via-bluetooth` must be **false** on a tablet. If it is true, the media sink is omitted (`docs/REVIEW.md:79-84`) and there is no music at all.

---

## 5. USB

- **Detection.** `UsbDeviceCompat.isAndroidDevice` excludes Apple (05AC) and accepts accessory-mode devices (`18D1:2D00/2D01`) or any device with an AOA, MTP, ADB, RNDIS, IAD or PTP interface (`connection/UsbDeviceCompat.kt:51-111`).
- **AOA switch.** `UsbAccessoryMode.switch`: `GET_PROTOCOL(51)` ≥ 1, then `SEND_STRING(52)` ×6 ("Android", "Android Auto", "Android Auto", "2.0.1", URI, "HU-AAAAAA001"), then `START(53)`, then sleep 500 ms (`connection/UsbAccessoryMode.kt:47-130`). With `use-libusb=true` it uses the native `UsbNative.accModeSwitch()`, and `LibusbAccessoryConnection` replaces the Java bulk transport.
- **Permissions:**
  - `requestPermission` with the broadcast `…ACTION_USB_DEVICE_PERMISSION` (`connection/UsbReceiver.kt:61-74`).
  - After the AOA switch the phone re-enumerates as a *new* device, so permission is checked again (`aap/AapService.kt:2208-2219`).
  - If the app was launched by `USB_DEVICE_ATTACHED` for that device, Android grants it implicitly. CarLink's `UsbRouterActivity` (filter: all devices) is the handler, and the grant covers the whole package, so `:aa` too.
- **Allowlists:**
  - `allow-devices` holds unique names "Manufacturer Product (VID: xxxx PID: yyyy)" (`Settings.kt:31-40`).
  - `UsbAttachedActivity` switches only Google-VID devices, allowed devices, or anything when `auto-start-on-usb` is on (`app/UsbAttachedActivity.kt:135-143`).
  - `checkAlreadyConnectedUsb` order (`aap/AapService.kt:2194-2303`):
    1. any accessory-mode device;
    2. last-session (`allow-devices`) devices;
    3. `auto-start-on-usb` with a single device;
    4. `auto-connect-single-usb` (restricted to allowed devices if any are allowed);
    5. `force` with a single Google-VID device.
- **Re-enumeration and stale accessories:**
  - Retry the connection up to 4 times, 1.5 s apart.
  - After more than 1 handshake failure on an accessory-mode device, re-send AOA to force re-enumeration (`:2153-2183`).
  - Accessory detach (e.g. a phone at 100% battery flapping) → re-check after 1.5 s.
  - After a USB user-exit, no auto-reconnect until the next attach (`:1351-1361`).
- **Not from the code:** the phone must be unlocked and must accept "Android Auto" on first use. Some phones need the "USB data" mode. These are DiAuto's own instructions (`setup_usb_instructions`).

---

## 6. Settings (`utils/Settings.kt`): every persisted key

"Connect?" marks whether the setting affects connecting: **Y** = yes, **(Y)** = only in a legacy or special path, **–** = no. "CL" = CarLink writes it.

### 6.1 Connection and wireless

| Key | Default | Effect | Connect? | CL |
|---|---|---|---|---|
| `wifi-connection-mode` | **3** (migrates from `wifi-launcher-mode`, `native-aa-wireless`) | 0 manual, 1 headunit server, 2 helper, 3 native | **Y** | no (relies on the default) |
| `native-ap-transport` | **1** (2 → 1) | Native transport: 0 P2P, 1 car hotspot (2 unreachable) | **Y** | yes, per session |
| `static-bssid` | `"0"` (auto) | **Global** BSSID override for P2P *and* hotspot credentials (`WifiDirectManager.kt:513-519`; `SoftApCredentialsProvider.kt:424-425`) | **Y** | yes (router BSSID or `"0"`) |
| `hotspot-interface` | `""` | Names the AP interface; waits for it | **Y** | yes (`wlan0` for Same Wi-Fi, else `""`) |
| `hotspot-ssid`, `hotspot-password` | `""` | Manual AP credentials; override the system config | **Y** | yes |
| `auto-enable-hotspot` | false | Engine may turn on the HU hotspot (needs `WRITE_SETTINGS`); also controls teardown on user exit | Y | Same Wi-Fi only (false) |
| `hotspot-teardown-proven-unsafe` | false | Learned: never take the AP down again | – | no |
| `native-wifi-version-exchange` | false | Send Type 4 first (AA 17.4 workaround candidate) | Y | no |
| `manual-secondary-bt-service-name` | `""` | Force a second Bluetooth radio; overrides the external-Bluetooth refusal | Y | no |
| `bluetooth-manager-service-name` | `bluetooth_manager` | Primary radio service name | (Y) | no |
| `auto-start-bt-macs` (+ legacy `auto-start-bt-mac`), `auto-start-bt-name` | empty | Bluetooth auto-start allowlist **and poke targets** | **Y** | yes (adds) |
| `helper-connection-strategy` | 2 | Mode-2 strategy | (Y) | no |
| `wait-for-wifi-before-wifi-direct`, `wait-for-wifi-timeout` | false, 10 | Mode-2 P2P only | (Y) | no |
| `network-addresses` | empty | Manual IPs (legacy) | (Y) | no |
| `last-nearby-device-name` | `""` | Nearby | (Y) | no |
| `debug-force-p2p-band-24` | false | Force a 2.4 GHz P2P group (rig lever) | Y | no |
| `p2p-legacy-5ghz`, `p2p-legacy-5ghz-upper` | false | Pre-Q 5 GHz channel restriction | Y | no |
| `use-native-ssl` | false | Native OpenSSL for AAP | Y | no |
| `bt-address` | `""` | Legacy | – | no |

### 6.2 Start and auto-connect

| Key | Default | Effect | Connect? | CL |
|---|---|---|---|---|
| `auto-connect-last-session` | **true** | USB: reconnect `allow-devices` on service start and USB re-check | **Y** | no |
| `auto-connect-single-usb` | false | USB: switch the only candidate | Y | no |
| `auto-connect-priority-order` | last-session, self-mode, single-usb | Order for the legacy Home auto-connect | (Y) | no |
| `last-connection-type/ip/usb-device` | `""` | USB reconnect decision (`AapService.kt:1351`) | Y | no |
| `allow-devices` | empty | USB allowlist | **Y** | yes (adds) |
| `auto-start-on-boot` | **true** (also mirrored in device-protected prefs) | Boot launch; **hibernate-wake launch of `homeActivity`** | **Y** (side effect) | no |
| `auto-start-on-screen-on` | false | Launch on every SCREEN_ON | Y | no |
| `auto-start-on-usb` | false | UI launch and switching any USB device | Y | no |
| `auto-start-on-wifi`, `auto-start-wifi-ssid` | false, `""` | Start on joining an SSID | Y | no |
| `auto-start-self-mode` | false | Legacy | (Y) | no |
| `listen-for-usb-devices` | true | Enables `UsbAttachedActivity` | (Y) | no (component removed) |
| `reopen-on-reconnection` | true | Launch home on USB attach when `auto-start-on-usb` | Y | no |
| `kill-on-disconnect` | false | Exit process and **finish all app tasks** | **Y** (dangerous) | no |
| `use-libusb` | false | Native AOA/bulk | Y | no |
| `wireless-paused-by-boot-loop`, `boot-loop-strikes` | false, 0 (device-protected) | Boot-loop guard | **Y** | no |

### 6.3 Video and display (affect session quality, not whether it connects)

| Key | Default | Effect |
|---|---|---|
| `resolutionId` | 0 Auto | Video codec resolution (CL writes it) |
| `fps-limit` | 60 | (CL writes it) |
| `video-codec` | Auto | H.264/H.265 |
| `dpi-pixel-density` | 0 | DPI reported to the phone (CL writes it) |
| `pixel-aspect-ratio-e4` | 10000 | |
| `view-mode` | resolved: DiLink5.1 → Surface, else Texture (`view/ProjectionRendererPolicy.kt`) | Renderer |
| `stretch_to_fill`, `forced_scale`, `use_measured_touch_surface` | true, false, true | |
| `inset-*`, `margin-*` | 0 | |
| `cached-surface-*` | 0 | |
| `fullscreen-mode` | IMMERSIVE | |
| `screen-orientation` | SYSTEM | |
| `force-software-decoding`, `software-video-decoder` | false, FFMPEG | |
| `debug-video-*`, `debug-force-memory-profile` | off | Debug-build only |
| `show-fps-counter` | false | |
| `hud_mirroring` | false | Windshield flip (generic) |
| `ui-scale-*` | 100 | |
| `pending-renderer-confirm` | false | |

Only a bad resolution/codec/renderer choice can make a session look dead: no picture, or the "sessions without video" pattern on a weak link.

### 6.4 Audio, input, sensors, vehicle

| Key | Default | Notes |
|---|---|---|
| `enable-audio-sink` | true | |
| `music-via-bluetooth` | **`Build.MODEL == "DiLink5.1"`** | Omits the AA media sink. CL writes it from the unified setting, default false. |
| `playback-focus-mode` | AUTO | |
| `static-audio-focus` | false | |
| `media-key-routing` | ALWAYS | |
| `separate-audio-streams` | false | |
| `audio-latency-multiplier` | 8 | |
| `audio-queue-capacity` | 50 | |
| `use-aac-audio` | false | |
| `mic-*` | 16 kHz, source 0, effects off | |
| `attach_hw_dsp_equalizer` | false | |
| `*-volume-offset` | 0 | |
| `key-codes` | — | Key remaps |
| `enable-rotary` | false | |
| `gps-navigation` | true | CL writes it |
| `fake_speed` | true | |
| `right-hand-drive` | false | CL writes it |
| `night-mode` and thresholds/times | AUTO | CL writes these |
| `use-fixed-sun-location`, `fixed-sun-*` | — | |
| `geofence-locations`, `location-outside-night` | — | |
| `last-loc-*` | — | |
| `vehicle-display-name` | "DiAuto" | CL sets "CarLink" |
| `vehicle-make/model/year/id`, `head-unit-make/model` | Google / Desktop Head Unit / 2025 / headlessunit-001 | Sent in service discovery |
| `byd-navigation-enabled` | false | CL writes it from `bydDirections`, **default true** |
| `show-navigation-notifications` | false | |
| `sync-media-session-aa-metadata` | false | |

### 6.5 UI and app

| Key | Default |
|---|---|
| `has-accepted-disclaimer`, `has-completed-setup-wizard`, `onboarding-version` | — |
| `primary-connection` / `connection-modes` | NATIVE_AA / {WIFI} |
| App theme keys, `monochrome-icons`, `aa-monochrome-enabled`, `aa-desaturation-level` | — |
| `loading-screen-*` | — |
| `show-toast-messages` | true |
| Log keys | `log-level` INFO, capture off |
| Language | — |
| `gesture_hint_shown`, `rename_notice_shown_v2`, `hide-map-internet-notice` | — |

None of these affect connecting.

---

## 7. Host-integration surface

### 7.1 Starting, stopping and observing without MainActivity or SettingsActivity

Everything a session needs runs headless in `AapService`, except the projection activity.

| Need | Clean way | Notes |
|---|---|---|
| Configure | Write DiAuto's `settings` prefs **in the `:aa` process** (CarLink: `AndroidAutoSettingsProvider`). Also mirror the device-protected keys through `Settings.sync*ToDeviceStorage`. | SharedPreferences cache per process |
| Arm wireless | `ACTION_START_WIRELESS` (clears the boot-loop pause; no-op if already armed), or `ACTION_BT_AUTO_START` (re-arm only when stopped) | |
| Wake one phone now | `ACTION_NATIVE_AA_POKE` + `EXTRA_MAC` | Re-arms mode 3 if needed and clears the exit flags |
| USB | `ACTION_CHECK_USB`, or `UsbAccessoryMode.connectAndSwitch` + `ACTION_CHECK_USB` | |
| End the session but stay armed | `ACTION_DISCONNECT` (user exit: 5 s cooldown, listeners stopped in mode 3) | |
| Stop everything | `ACTION_STOP_SERVICE` | Car mode off, P2P/AP released, MediaSession released |
| Observe | `App.provide(ctx).commManager.connectionState` (StateFlow, in `:aa`) | CarLink mirrors it by broadcast |
| Join failure | `HostIntegration.onPhoneWifiJoinFailed` (CarLink addition) | Hotspot transport only |

**Existing hooks** (`HostIntegration.kt`, all CarLink additions): `homeActivity`, `appName`, `serviceChannelName`, `mediaSessionTag`, `toastHandler(text, forced)`, `chromeStyler`, `exitMenuHomeLabel`, `onPhoneWifiJoinFailed`.

**What a host still cannot see without code changes:**

- The Native AA stage: poke running, phone accepted, `WifiConnectStatus` codes, settle. These exist only in `AppLog`.
- Transport and band.
- The BSSID source.
- The "sessions without video" streak.
- Boot-loop pause.
- External-Bluetooth refusal.
- "Wi-Fi off" and "P2P owned by another app". These are only toasts.

§11 proposes a `HostIntegration.onEngineEvent` hook.

### 7.2 Side effects of running AapService headless (all hosts must account for them)

| Side effect | Evidence |
|---|---|
| `enableCarMode(0)` for the service's whole life | `AapService.kt:807-817,1851-1857` |
| Dynamic receivers: wake detection (launches `homeActivity` on hibernate wake if `auto-start-on-boot`), Wi-Fi auto-start, USB, MEDIA_BUTTON | `AapService.kt:1385-1461` |
| USB check at create, which can switch allowed phones | `:802` |
| Network callback that kicks discovery (mode 1/2 only) | `:1463-1513` |
| `START_STICKY` + `onTaskRemoved` restart | `:2034,1773-1783` |
| Wi-Fi lock `HIGH_PERF` while connected | `:1724-1733` |
| BYD outputs started on connect (no-op off BYD) | `:997` |

---

## 8. Standalone-app UX: what DiAuto does to make connecting work

| Area | What DiAuto does | Evidence | Must a host replicate it? |
|---|---|---|---|
| Onboarding | Welcome, safety (mandatory), permissions, ready (older display, DPI, GPS and vehicle steps hidden) | `main/OnboardingActivity.kt:43-48,155-156,744-753`; `docs/REVIEW.md:15` | Permissions: yes |
| Permissions registry | Mic; Location (fine+coarse); Nearby (`BLUETOOTH_CONNECT`/`SCAN`/`ADVERTISE`, `NEARBY_WIFI_DEVICES`); notifications; **overlay**; optional `WRITE_SETTINGS` (hotspot auto-enable); legacy storage. "Enable all" button. AppOps fallback for HUs where `canDrawOverlays` lies. | `utils/AppPermissions.kt:61-174` | Yes, except storage and `WRITE_SETTINGS` (optional) |
| Runtime check on each resume | Requests missing permissions (`MainActivity.checkSetupFlow`) | `main/MainActivity.kt:824-838,887-900` | Yes |
| Battery optimisation | **No prompt in the app.** Only the ADB `scripts/install.sh` exempts it. The engine logs "Battery optimization exempt" on boot starts. | `docs/INSTALL.md:40-42`; `AapService.kt:1758-1762` | Yes (CarLink's Diagnostics has this) |
| Overlay | Permission row: "Lets the app open the car screen on top of other apps, including auto start" | `strings.xml perm_overlay_help` | **Yes** (required for projection launch) |
| Autostart | Boot / screen-on / USB / Wi-Fi / Bluetooth toggles (`AutoStartFragment`), with the device-protected mirror | `main/AutoStartFragment.kt`; `Settings.kt:1032-1313` | Host's own |
| Home "Connect phone" | Checks the Bluetooth permission; **"Bluetooth off" / "no paired phones" dialogs with a link to Bluetooth settings**; list of bonded phones; sets mode 3; pokes the chosen phone; shows the auto-connect pill and overlay with a watchdog | `main/HomeFragment.kt:43-133`; `main/MainActivity.kt:256-457` | **Yes** |
| Connection setup guide | 1. Choose: car hotspot or Wi-Fi Direct. 2. Hotspot: on/off status, "Open car hotspot settings" (`WIFI_TETHER_SETTINGS`), enter SSID/password (validated 1–32 bytes, 8–63 characters), auto-hotspot toggle plus a `WRITE_SETTINGS` grant. Wi-Fi Direct: "Turn the car's Wi-Fi switch on. Allow Location / Nearby… enable Location when Android asks." 3. "Pair with the car's Bluetooth… tap Connect phone… Accept the Android Auto prompts on the phone." | `main/ConnectionSetupFragment.kt:51-138`; `strings.xml setup_*` | **Yes**, condensed |
| Toasts | Wi-Fi off; P2P in use; band/channel; config unreadable (forced); USB switching/permission; renderer fallback (forced) | §10.8 list | Replace with Home status |
| Diagnostics | Diagnostics fragment, log export ("Save diagnostic report", redacted), journal | `main/DiagnosticsFragment.kt`; `utils/LogExporter.kt`; `docs/CONNECTION_SETUP.md:45-52` | Yes (CarLink has its own) |
| Boot-loop notice | High-priority notification after 3 crash boots | `AapService.kt:2933-2966` | Yes, if CarLink ever counts boots |
| Static BSSID guidance | "open any Wi-Fi scanner app on the phone, find the network named `DIRECT-…` … enter its BSSID as Static BSSID" | `docs/INSTALL.md:64-67` | Fallback only |

---

## 9. Upstream issues and discussions

### 9.0 Scope and lineage

**What was scanned:**

| Repository | Issues | PRs | Discussions | Wiki | Read in full |
|---|---|---|---|---|---|
| `shihabal3amri/DiAuto` | 18 | 11 | none enabled | empty | all, plus 3 attached diagnostic reports |
| `F-e-n-y-x/DiAuto` | 0 | 0 | — | none | — |
| `andreknieriem/open-headunit` | 628 | 360 | 90 | 10 pages, cloned | 182 issue threads (about 760 maintainer comments), 16 discussions, about 15 PRs |

`andreknieriem/headunit-revived` redirects to `open-headunit`; it is the same repository.

**Lineage.** DiAuto forked Open Headunit at commit `562c8dc` (`README.md:87`). Its logs still say `pkg=com.andrerinas.headunitrevived` (DiAuto #6 attachment).

**Upstream is now ahead of DiAuto on several wireless fixes**, listed in §9.6. **[M]** marks a maintainer or collaborator, **[U]** a user report. All threads are from 2026.

### 9.1 Android Auto app versions

**16.4 moved the wireless start entry point.**

- [M] "Google changed the way how to start AA via intent. It was via Activity and is now a broadcast" (open-headunit #257).
- [M] "The new broadcast method does not have parameters for the network … The only way is to fake a VPN connection" (#298).
- Self Mode and Wireless Helper broke. The dummy VPN survives only in GitHub builds (#431).

**17.2–17.4 closed every third-party start path.** From #698:

- [M] "17.4.6630 is a Full blocker only Native mode works … Google disabled the WirelessStartupReceiver component."
- `START_WIRELESS_PROJECTION` "actively verifies bluetoothDevice.isConnected() == true … drops the request after 5 seconds."
- The owner's verdict: "they closed the intents … for all wireless connection except for native mode and the developer headunit server mode" (#706).

**Gates Gearhead applies**, seen in its own logs (#698):

- A momentary real-internet check (`NET_CAPABILITY_INTERNET`): "The internet requirement is real, but it's momentary, not continuous."
- An HFP-presence check, which turned out "to not actually be a blocker."
- **Rejection of a "car" that reports a Bluetooth battery level**: `WIRELESS_STOPPED_SETUP_WITH_HU_WITH_BT_BATTERY_LEVEL`. This is how headphones get rejected.
- **Inference:** a tablet that publishes its battery over Bluetooth could hit the same gate. Not observed.

**17.5 and later:**

- QR / "Can't connect with Wi-Fi" when the feature is switched off inside Android Auto (wiki Settings).
- Self-mode needs an online FRX on 17.5 (#897).
- 17.8 self-mode drops (#985).

**Google forces updates.** [M] "you can only stay short on a version lower than 17.4" (discussion #758).

**Other phone-side notes:**

- Some users fixed auto-connect by switching the AA assistant from Assistant to Gemini (#597).
- [C] "Only Pixel and Samsung devices support wireless Android Auto on Android 10. For devices from other brands, Android 11 or higher is required" (#302).

### 9.2 Wireless reliability: what works

**Native ranking.** Native is the shipped default with Wi-Fi Direct as its transport (wiki Settings). The README's order after 17.4: "1. USB Wireless Android Auto Dongle (Recommended) 2. Native Mode 3. Headunit Server 4. Wireless Helper (≤17.3) 5. Root workaround."

**BSSID is the most common failure.**

- [M] "The most not working thing is to get the bssid … masked … your phone … sees, that the actual id does not match the one given and just won't connect" (#534).
- Location must be on (#395; wiki: "with location services off, Android masks the WiFi Direct MAC … the usual cause of a wireless handshake that gets as far as sending credentials and no further").
- A wrong static BSSID gives `WifiConnectStatus WIFI_NETWORK_UNAVAILABLE(-11)` (#1003). This matches DiAuto's own -11 loop when it published eth0 (`docs/HOTSPOT_RECOVERY.md:3-7`).
- Upstream also added IPv6 EUI-64 recovery (#1034).

**Native over the HU's own hotspot.** It was first declared impossible, then proven by a user: "If you pass the hotspot connection parameters, it works" (#374). It shipped for units whose chipset reboots on P2P joins (#774). It needs typed credentials because "a lot of headunits refuse to show the hotspot credentials" (#809).

**The phone's own hotspot is incompatible with native.** [M] "The 'Hotspot' option in Native means the head unit hosts its own hotspot … it can't work the other way round" (#907). With the phone's hotspot on, Wi-Fi Direct failed in a user's A/B logs (#706).

**Single-radio HUs.**

- [C] "Wi-Fi Direct worked only when the head unit was not joined to another Wi-Fi network as a client" (DiAuto PR #22).
- [M] "There is one radio, and it has to cover three jobs … The worst state is therefore WiFi on and connected to nothing" (discussion #665).
- Upstream added a "Leave this unit's WiFi network while connecting" option. It is only possible up to Android 14 (#1037).
- DiAuto logs warn about station/group channel contention (DiAuto #6).

**A shared router can hijack the session.** [U] "the link between my phone and OpenHU seems to be over the shared WiFi … as soon as I drive away … I lose my WiFi connection" (#880). That is a direct argument against Same Wi-Fi in a moving car.

**The phone caches the network identity.**

- [M] "That is the name your phone had saved … When it does not match, your phone keeps looking for the network it remembers" (#976).
- PR #1006 measured "1.1 s to SSL on the five that reused a group against 8.9 to 9.5 s on the five that recreated one."
- Upstream now has "Keep the same WiFi Direct network", on by default.
- The HU hotspot IP also changes per boot, and the phone "dialled it forever" (PR #1014).

**Bands and resolution.**

- [M] "the max resolution for 2.4GHz is 720p at 60 fps" (#809).
- 3.4.0 enforces "720p at 30 FPS with AAC Audio mandatory for all 2.4GHz" (#964).
- The wiki: "Ensure you are using 5GHz WiFi."
- Regional 5 GHz: "a phone's WiFi country comes from its SIM". An Egyptian SIM could not see channel 149 (#809).

**Headunit Server mode.** It needs the phone's developer menu and "you'll have to start the server each time" (#684). "There is also no way to start it via another app" (discussion #758). It wedges (#161). Not "no fiddling".

**USB wireless dongles.** [M] "the absolute best option right now" (#759). It is real embedded Bluetooth/Wi-Fi hardware acting as a phone over USB, so for an Android HU it is just the USB path (§5).

### 9.3 Bluetooth and tablets

**AA starts on a Bluetooth connection event.**

- [M] "Android Auto starts a wireless session on a Bluetooth connection *event*, rather than simply relying on being connected … no new event ever happens."
- The fix cycles the HU's own radio up to Android 12L. The user confirmed 20/20 ignition cycles (#992).
- **Inference for tablets:** the poke, an ACL connect from the HU, is the way to produce that event without user action.

**Hold the RFCOMM channel for the whole session.**

- [M] "Android Auto 17.8 treats that channel as the phone's for the whole session: when the head unit closes it, the phone restarts wireless setup and re-dials every 5 s … that retry is the stutter … 1 to 13 fps … against 27 to 30 fps" (PR #1029).
- DiAuto still ends the handshake and closes the AA listeners on `CompleteSuccess` (`NativeAaHandshakeManager.kt:1025-1035,1286-1289`). **See §10.11.**

**The poke vs HFP.** [M] "A poke that **connects** takes the phone's single hands-free slot" (PR #810). That is the source of DiAuto's skip rule.

**A tablet acting as hands-free steals the car's HFP.** [M] "most mobile phones can typically manage only one active HFP connection at a time" (#529). Workaround: disable "Phone calls" for the tablet on the phone.

**Calls cannot go over Wi-Fi to a tablet.** [M] "Android Auto typically requires Bluetooth for phone call audio" (#110). "Calls on these units stay on the phone" (PR #1029).

**Pairing must be redone sometimes.**

- [M] "Wireless Mode on AA needs one specific pairing and that have to match." The fix was re-pairing from the HU, which prompts for the "Phone calls" permission (#910).
- The DiAuto owner: "unpair your phone from the car and repair it again" (DiAuto #7).

**Tablets do not auto-connect.**

- [U] "the phones dont auto connect to the tablet, it has be done manually" (#537).
- [U] "the tablet wont initiate a connection unless the HURev app is launched" (#563).
- This matches field result 1.

**A remembered "car" auto-starts.** [M] Once the phone saved the HU as a wireless AA car, "As soon as your phone connects to the head unit's Bluetooth, Android Auto … immediately initiates" projection. Forget it under "Previously connected cars" to stop it (#860). The same complaint appears in DiAuto #13.

**External Bluetooth modules** (ZLink/CAR8032, FYT `/dev/auto_serial`). Android Auto dials the other chip "14 times … never once" the Android radio (#706). Upstream now has per-vendor transports (PR #1027).

### 9.4 Phone-side settings

**"Switch between networks" / Network acceleration.** Xiaomi, Samsung and others kill the no-internet Wi-Fi link:

- [C] "Since the wireless AA dongle's WiFi network has no internet access … the OS aggressively kills the WiFi interface entirely" (#203).
- The wiki says to disable "Switch between networks", "WiFi Assistant", "Network acceleration" and "Avoid poor connections".

**Mobile data.**

- Blocking AA's mobile data breaks native wireless: "When only mobile data is denied, AA wont connect" (discussion #565). This is consistent with the momentary internet gate.
- Upstream keeps an optional "offline VPN … so Android does not route projection traffic over mobile data" (GitHub build).
- HU sockets used to bind to the cellular default network; upstream binds them to Wi-Fi now (#521).

**Samsung.**

- No Samsung-specific protocol bug was confirmed by a maintainer. The owner tests on an S24/S26.
- DiAuto reports: S21 Ultra wireless failing on DiLink 5.1 (#1); S25 Ultra on BYD Han, wired only, re-pairing advised (#7).
- Galaxy tablets cannot run AA itself (#416, #636). That only matters for Self Mode.

**Charging.** Wireless drops unless the phone is charging (discussion #531). USB flaps at 98–100% (#355; DiAuto handles accessory-detach re-sync).

### 9.5 USB lessons

- [M] "A USB Accessory has zero control over the USB bus … impossible on most devices to trigger the usb 'connect' again without a real unplug" (#615).
- Host-controller stalls → libusb option (#613).
- Do not `resetInterface()` on read errors (PR #205).
- **Two permission prompts** are by design: the device re-enumerates as `18D1:2D00` (#421, #471). An invisible attach-handler activity is the fix (#640). CarLink's `UsbRouterActivity` is exactly that.
- The phone must accept the Android Auto overlay on first USB use (#392).
- Some HUs have no AOA-capable host stack (#935), and micro-USB tablets need OTG (#881).

### 9.6 Lessons DiAuto has *not* absorbed (upstream fixes newer than DiAuto's base)

| Upstream fix | Reference | DiAuto 0.3.11 |
|---|---|---|
| Hold the BT RFCOMM channel for the whole session (AA 17.8 stutter) | PR #1029 | Releases it on success (§10.11) |
| Reuse the same Wi-Fi Direct network name and passphrase across sessions | PR #1006, issue #976 | Random `DIRECT-XX-…` and passphrase per group (`WifiDirectManager.kt:1334-1346`); CarLink also removes the group on every start |
| Cycle the HU's Bluetooth radio to produce a connection event (≤ 12L) | #992 | No |
| "Leave this unit's Wi-Fi network while connecting" for single-radio units (≤ A14) | #1037 | No |
| Stable hotspot IP handling | PR #1014 | Re-resolves on join failure only |
| Offline VPN to keep projection off mobile data | wiki Settings | VPN code unused |
| Lower video automatically on 2.4 GHz | wiki Settings; #964 | Advice only (`VideoStarvationPolicy`) |
| Per-vendor external-Bluetooth transports | PR #1027 | Refuses the route |

---

## 10. Review of CarLink's integration

Severity: **H** = breaks "just works" or causes wrong behaviour; **M** = degrades or risks; **L** = hygiene.

### 10.1 [H] `static-bssid` is a global override, and Same Wi-Fi pins the phone to the car's BSSID

- `EngineMappings.kt:188` writes the router BSSID from `WifiManager.connectionInfo.bssid` (`EngineSettingsApplier.kt:316-320`). The engine uses `static-bssid` first in **both** credential chains (`WifiDirectManager.kt:513-519`; `SoftApBssidPolicy.choose` first argument).
- CarLink resets it to `"0"` on the hotspot and Wi-Fi Direct paths (`EngineMappings.kt:196`) and in the fallback (`AndroidAutoWifiFallback.kt:43`), so cross-path leakage is avoided **only as long as every session goes through `applyForSession`**. A settings-only apply (`path=null`) leaves the previous session's value in place.
- The phone's specifier needs the exact BSSID. The car's current BSSID may be on a band or mesh node that is poor for the phone, and the other-band BSSID stalled in the field ("WiFi read timeout"; §3.3.4).
- **Fix:**
  - Always rewrite `static-bssid`, `hotspot-interface`, `hotspot-ssid`/`password` and `native-ap-transport` together in one provider call per session (already true).
  - Never leave a non-`"0"` value in place after a Same Wi-Fi session ends. Reset it on stop.
  - Prefer the 5 GHz BSSID and log the frequency (`connectionInfo.frequency`) alongside it.

### 10.2 [H] Same Wi-Fi pushes the phone onto mobile data, and it needs a typed password

Same Wi-Fi is first in `AUTOMATIC` when `homeWifi` is saved and valid (`EngineMappings.kt:65-69`). For Android Auto that means:

- the phone loses Wi-Fi internet during the drive (field result 2; §3.3.4);
- it depends on the router not isolating clients;
- it depends on the exact BSSID;
- it needs the user to type the router password.

None of that helps the driver compared with Wi-Fi Direct, which works on the tablet at 40–50 fps without typing.

**Fix:** for Android Auto, put Wi-Fi Direct first in AUTOMATIC. Keep Same Wi-Fi as an explicit opt-in, mainly for CarPlay, where the iPhone joins the router normally.

### 10.3 [M] `native-ap-transport` and `hotspot-interface`

- For CAR_HOTSPOT, CarLink writes `hotspot-interface=""` (heuristic). On tablets the tethering AP is typically `wlan1`, `ap0` or `swlan0` while `wlan0` is the station. The heuristic handles that because it excludes the station IPv4 (`SoftApNetworkPolicy.kt:107-110`).
- The SSID and password of a tablet's own hotspot cannot be read (`HotspotConfigReader` reflection is refused). CarLink therefore needs them typed in, or falls back to Wi-Fi Direct (`EngineMappings.kt:51-52`). That is acceptable.
- Value 2 (local-only hotspot) is impossible to select (§3.3). Raw prefs written through the provider are rewritten to 1 by the getter. Re-enabling it needs a vendor edit.

### 10.4 [M] `wifiConnectionMode` is never written

- CarLink relies on the default 3 (`AndroidAutoCommandReceiver.kt:106` only reads it).
- DiAuto's own Connect button writes `3` every time (`main/HomeFragment.kt:104`).
- A user who reaches DiAuto's SettingsActivity (still in the APK) or an imported backup can leave mode 1 or 2. `startWireless` would then send `ACTION_BT_AUTO_START`, which does nothing outside mode 3.
- **Fix:** write `wifi-connection-mode=3` in `applyForSession` for wireless.

### 10.5 [M] `startWireless` restarts the engine every time

`AndroidAutoCommandReceiver.kt:91-116`:

- If AapService runs and `isConnected` is false, it sends `STOP_SERVICE` and waits up to 3 s. That kills an attempt in `Connecting` too (TCP accepted, AAP starting), because `isConnected` excludes `Connecting` (`CommManager.kt:190-196`).
- `WifiDirectReset.removeGroup` plus clearing `p2p_ownership` is right for a stuck P2P owner. But the stop/start also re-runs `onCreate`: car mode, receivers, a USB check and a new P2P group.
- With an address: `onCreate`'s `initWifiMode` publishes credentials → `triggerPoke` (looping over all listed MACs) → `NATIVE_AA_POKE` → `manualPoke` (a single 20 s hold, **no retry**). The next credential redelivery's `triggerPoke` cancels the manual poke and restarts the loop (`NativeAaHandshakeManager.kt:684-691`). It works, but two pokers race the same radio.
- Without an address it sends `ACTION_BT_AUTO_START`. Because `onCreate` just armed everything, it does nothing, and the loop pokes **every bonded device** if the auto-start list is empty (`:729-731`). On a tablet that includes earbuds, watches and other cars.
- **Fix:**
  - Use `commManager.isBusy`, not `isConnected`.
  - Only stop the service when `NativeAaHandshakeManager` reports itself stuck (or simply skip the stop when the service was idle).
  - Set the poke targets to exactly the chosen phone for the session (replace the set, keep the others in CarLink's KnownPhones).
  - Prefer `ACTION_START_WIRELESS` followed by `ACTION_NATIVE_AA_POKE`.

### 10.6 [H] Process handling: phantom engines and side effects of engine defaults

1. **`START_STICKY` and `onTaskRemoved`.** If `:aa` is killed (low memory) or the user swipes CarLink away, Android restarts `AapService` with a null intent (`AapService.kt:2034,1773-1783`).
   - `onCreate` then arms mode 3, creates a P2P group (or reads the AP) and **pokes phones on its own** (`onNativeCredentials` → `triggerPoke`, `:2434-2450`).
   - CarLink's main process is not engaged, so it ignores the state broadcasts (`AndroidAutoEngine.publish` returns when `!engaged`, `:152-153`).
   - Result: a wireless engine CarLink does not show, which can pull the phone into Android Auto and hold the P2P radio while CarPlay wants it.

   Fix (needs a vendor edit): make the start mode a host decision (`HostIntegration.stickyService=false`), and skip the `onTaskRemoved` restart when hosted. Alternatively, have `AndroidAutoCommandReceiver` stop a service whose start CarLink did not ask for.
2. **`auto-start-on-boot` defaults to true.** While AapService runs, any screen-off longer than 60 s followed by screen-on, or any OEM ACC intent, launches `HostIntegration.homeActivity` through the overlay trampoline (`AapService.kt:607-628, 2781-2849`). On a tablet, waking it pops CarLink Home over whatever is on screen, even when CarLink's own `startOnBoot` is off.

   Fix: write `auto-start-on-boot=false` and `auto-start-on-screen-on=false`, and sync the device-protected copies, because CarLink owns boot behaviour.
3. **`auto-connect-last-session` defaults to true, and CarLink grows `allow-devices`.** CarLink adds every USB-started phone (`AndroidAutoCommandReceiver.kt:58`). On every AapService start, including wireless ones, `checkAlreadyConnectedUsb()` switches any allowed Android phone currently plugged in (for example, charging) into accessory mode (`AapService.kt:802, 2232-2259`). The UsbReceiver fallback does the same 2 s after any attach (`:2041-2068`).

   Fix: set `auto-connect-last-session=false`, and have CarLink perform the AOA switch itself (it already can, `:60-71`). Or clear `allow-devices` to the current device.
4. **Car mode.** `enableCarMode(0)` holds for the service's whole life, including idle "waiting" (`AapService.kt:807-817`). On phone/tablet ROMs this changes uiMode and may show a car-mode notification or driving UI (inference). CarLink keeps the service running after sessions until `stop()`.
5. **`kill-on-disconnect`.** If it were ever true, `appTasks.forEach { finishAndRemoveTask() }` would close **CarLink's Home task**, because tasks are per package, not per process (`CommManager.kt:562-565`).

   Fix: write `kill-on-disconnect=false` explicitly.
6. **Boot-loop guard.** CarLink removed `BootCompleteReceiver`, so strikes never accrue. Inherited pause state from a standalone DiAuto install cannot exist (different package). Low risk.

   Note that CarLink's paths never send `ACTION_START_WIRELESS`, so a pause would never be cleared.

### 10.7 [M] Auto-start lists

`auto-start-bt-macs` only ever grows (DiAuto adds every phone that connected; CarLink adds every chosen phone). Each poke cycle walks all of them (§4.4).

Fix: replace the set with the chosen phone at session start (§10.5). Keep the full list in CarLink's `KnownPhones`.

### 10.8 [H] Engine toasts are dropped, including the actionable ones

`CarLinkApplication.kt:57-60` drops every non-forced toast. Dropped, with no other signal to the host:

- "Native AA requires Wi-Fi. Please turn it on." On API ≥ 29 the P2P path **aborts** (`WifiDirectManager.kt:1161-1173`), so wireless silently never starts.
- "Wi-Fi Direct is in use by another app…" (`:1674`).
- "…most phones cannot join. Restart WiFi" (`:659`).
- USB permission denied / failed (`AapService.kt:2130,2143`).

**Fix:** before every wireless start CarLink should check, and show on Home:

- Wi-Fi radio on (offer the `Settings.Panel.ACTION_WIFI` panel);
- Bluetooth on;
- the **system Location switch on** (`LocationManager.isLocationEnabled`) — Diagnostics checks only the permission (`carlink/diagnostics/Checks.kt:116-118`);
- `NEARBY_WIFI_DEVICES`;
- overlay;
- the phone bonded.

Also map known toast texts to Home statuses inside `toastHandler`, or add the event hook (§11).

### 10.9 [L] Exported engine entry points

- `AutomationActivity` is exported with `headunit://exit|connect|disconnect|nightmode` and actions.
- `AapProjectionActivity` is exported.

Any installed app can start, stop or switch the Android Auto engine. Mark both `exported=false` in CarLink's manifest. CarLink's own code reaches them through explicit intents in the same package.

### 10.10 [L] State mapping

- `CONNECTION_ERROR` → `FAILED` is shown even though DiAuto immediately retries or re-arms. It is also rarely observed at all (conflated; `AapService.kt:855-862`).
- After an unexpected wireless drop, the engine re-arms in 1.5 s and the poke resumes. CarLink shows WAITING, which is right.
- The richer Native AA stages are invisible (§7.1).

### 10.11 [H] The engine drops the Bluetooth channel after the handoff, and CarLink makes every group new

**The Bluetooth channel.** On `CompleteSuccess`, DiAuto closes the AA listeners, and the handshake coroutine then closes the RFCOMM socket (`NativeAaHandshakeManager.kt:1025-1035,1286-1289`). Upstream measured that Android Auto 17.8 then "restarts wireless setup and re-dials every 5 s … 1 to 13 fps … against 27 to 30 fps" (open-headunit PR #1029).

The field results are consistent with this, though not proven. If the phone runs AA ≥ 17.8, this is a candidate cause of mid-session stutter. Port upstream's fix: keep the RFCOMM socket and the reader alive until the AAP session ends, keep answering pings, and close it on disconnect.

**The Wi-Fi Direct group.** Every `startWireless` removes the P2P group (`AndroidAutoCommandReceiver.kt:103-104`), and DiAuto names each group randomly (`WifiDirectManager.kt:1334-1346`). The phone's saved profile therefore never matches. Upstream measured 1.1 s to SSL when the group was reused, against 8.9–9.5 s when it was recreated (PR #1006), and saw phones keep looking for the remembered name (#976).

**Fix:** a stable per-install group name and passphrase, and no removal on start unless another owner holds the group.

### 10.12 What CarLink does right

- The engine is isolated in `:aa`.
- A single settings writer.
- Key receivers are gated per session.
- The poke fix for gateway-role links.
- `p2p_ownership` is cleared after its own leftovers.
- The join-failure fallback to Wi-Fi Direct with refusal memory.
- USB routing through one attach handler (the package-wide grant).
- `vehicle-display-name` is set.
- The projection chrome is branded.

---

## 11. Recommendations for a "just works" host

### 11.1 Defaults

Applies to both a head unit and a tablet.

| Setting | Value | Why |
|---|---|---|
| `wifi-connection-mode` | 3 (written every session) | The only no-companion route |
| Android Auto wireless path, AUTOMATIC | **Wi-Fi Direct** → car hotspot (only if credentials are readable or saved *and* the AP is up) → Same Wi-Fi (opt-in only) | Proven on both kinds of device, no typing, BSSID self-recovers |
| `static-bssid` | `"0"`, except Same Wi-Fi; reset at stop | Global override (§10.1) |
| `auto-start-on-boot`, `auto-start-on-screen-on`, `auto-start-on-usb`, `auto-start-on-wifi` | false (plus the device-protected sync) | The host owns entry points |
| `auto-connect-last-session` | false; the host performs the AOA switch | Prevents surprise AOA switches |
| `kill-on-disconnect` | false | Protects the host's tasks |
| `music-via-bluetooth` | false on tablets and generic HUs. True only if the HU is the phone's A2DP sink *and* the user wants Bluetooth music. | A tablet has no sink |
| `byd-navigation-enabled` | Default **false** unless the vehicle profile is BYD | §12 |
| `native-wifi-version-exchange` | false; offer it as a troubleshooting toggle | Unverified |
| `fps-limit` / `resolutionId` | Auto at 60 on 5 GHz. If the starvation advice fires (3 sessions without video), drop automatically to 720p/30 and tell the user. | `VideoStarvationPolicy` |

### 11.2 Wireless start, done right

1. **Pre-flight on Home**, each a one-tap fix:
   - Bluetooth on;
   - the phone bonded (else open Bluetooth settings with "Pair your phone with this screen");
   - Wi-Fi radio on (Wi-Fi panel);
   - Location switch on (`ACTION_LOCATION_SOURCE_SETTINGS`);
   - permissions: `BLUETOOTH_CONNECT`/`SCAN`, `NEARBY_WIFI_DEVICES`, fine location, mic, notifications;
   - overlay.

   Optional: battery optimisation exemption and, on API 34+, full-screen intent access.
2. Write the session settings (one provider call), including `auto-start-bt-macs={chosen phone}`.
3. `ACTION_START_WIRELESS` (arms, clears the pause), then `ACTION_NATIVE_AA_POKE(mac)`. Stop and restart the service only if the previous attempt is wedged: the handshake is backed off, or the P2P group is owned by another app.
4. Watch for:
   - no AA accept within 45 s → show "Open Bluetooth on your phone and connect to <this device>";
   - a join failure → fall back (existing hook);
   - three sessions without video → lower the video.

### 11.3 Making Bluetooth initiation work, or guiding it

- **Head unit** (has HF/A2DP-sink): the phone reconnects by itself. `SystemEventReceiver` → Home → start works. Keep the poke but respect the HF link (the engine already does).
- **Tablet:** nothing reconnects by itself, so CarLink must initiate:
  - **On Home open, screen wake or the Connect tap:** start the wireless engine for the last-used Android phone and poke it. The poke from a tablet is an RFCOMM connect to the phone's HFP-AG record. The phone accepts it as a bonded device, the ACL comes up, and Gearhead then dials the AA UUID. That the poke alone works on the Redmi/S25 is **not yet proven**: field result 1 predates or bypassed it. Test it with the CarLink gateway patch and logs (`NativeAA: Successfully poked … via HFP-AG`, then `Connection accepted from …`).
  - **If the phone answers pokes but never connects back** (the engine logs this every 3 pokes): show "On your phone: Settings → Connected devices → <tablet> → Connect", plus "Make sure Android Auto → Wireless Android Auto is on".
  - **Pair while CarLink's Android Auto listener is running**, so the phone's SDP cache contains the AA UUID (inference). If a phone was paired earlier, a "Forget and pair again" hint is a cheap recovery.
- After the first successful wireless session, the phone saves the HU as a wireless car. From then on, the phone's own Bluetooth connect starts Android Auto (open-headunit #860). The tablet still has to bring the ACL up through the poke. Upstream's radio-cycle trick (#992) works only up to Android 12L, so it is useless on an Android 16 tablet.
- **Troubleshooting card on the phone side** (from §9.4): Wireless Android Auto on; Android Auto allowed to use mobile data; "Switch to mobile data" / "Network acceleration" / "Avoid poor connections" off if the session drops; re-pair if the phone answers pokes but never connects back.
- **Do not** try to make a tablet an HFP-HF or A2DP sink. Those roles need system privileges or a system Bluetooth configuration that an app cannot change.

### 11.4 USB

Keep the single attach handler (package-wide permission). Perform the AOA switch in the host with `UsbAccessoryMode.connectAndSwitch`, then `ACTION_CHECK_USB`. Disable the engine's own auto USB behaviour (§11.1). Show "Unlock your phone and accept Android Auto" during the first switch.

### 11.5 Engine changes worth making (vendor edits, small)

1. `HostIntegration.stickyService` / `restartOnTaskRemoved` (default true).
2. `HostIntegration.onEngineEvent(kind, detail)` for:
   - Wi-Fi off; P2P owned by another app; DFS channel;
   - BSSID unresolved (Location off);
   - poke answered but no accept;
   - handshake backoff;
   - `WifiConnectStatus` / `StartResponse` codes;
   - settle timeout;
   - starvation advice;
   - boot-loop pause;
   - external Bluetooth;
   - USB permission denied.
3. Allow `native-ap-transport=2` behind a host flag, for an experiment with the Android 16 local-only hotspot on tablets.
4. Gate `BydFullscreen.expand` on a vehicle profile (§12).
5. **Port open-headunit PR #1029:** hold the Bluetooth RFCOMM channel for the whole session (§10.11).
6. **Port PR #1006:** a stable Wi-Fi Direct name and passphrase; stop removing the group on every start (§10.11).
7. Optional ports: Bluetooth radio cycle to produce a connection event (#992, ≤ Android 12L only); "leave station Wi-Fi while connecting" on single-radio HUs (≤ Android 14; #1037).

### 11.6 What cannot be made automatic, and why

| Thing | Why not |
|---|---|
| Bluetooth pairing | Needs user confirmation on both devices. Android has no silent pairing for apps. |
| A tablet acting as a car kit (HFP-HF / A2DP sink) | System profile configuration; not available to apps |
| The phone's Android Auto settings (wireless toggle, first-run consent, "Start head unit server") | Live on the phone. No phone app is allowed in this design. |
| Reading the router password or the tablet's hotspot password | Android hides Wi-Fi passwords; `getSoftApConfiguration` is privileged |
| Turning the HU hotspot on (API 26+) | Needs `TETHER_PRIVILEGED` or `WRITE_SETTINGS` with OEM support; best effort at most (`docs/LOCAL_HOTSPOT_TEST.md`; `SoftApCredentialsProvider.kt:49-51`) |
| Turning Wi-Fi, Bluetooth or Location on (API 29+/33+) | Apps may only open panels and settings |
| Keeping internet on the phone's Wi-Fi during wireless Android Auto | Gearhead uses a local-only specifier (§3.9) |
| Overlay, battery exemption, full-screen intent (API 34+) | Special accesses granted in system settings |
| USB "Allow access" on first plug (if the host is not the default handler) and the phone's "Use USB for…/unlock" prompts | System UI |

---

## 12. BYD-specific vs generic

The car and stereo in this project are **not BYD**. This section lists everything BYD/DiLink-specific that DiAuto carries, whether it runs or could misbehave elsewhere, what the generic behaviour should be, and how a vehicle profile could gate it.

### 12.1 Inventory

| # | Feature / code path | Gate today | Runs on a non-BYD HU or tablet? | Risk off-BYD | Generic behaviour |
|---|---|---|---|---|---|
| 1 | **Standalone windshield HUD** (`hud/BydStandaloneHudOutput.kt`): broadcast `byd.hud.NAVIGATION` to `com.byd.clusterdebug/.BroadcastReceiverCAN` | Package allowlist (includes `com.carlink.app`, `:49-52`), exact `Build.FINGERPRINT` `BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260722.221155…` (`:53`), receiver version 10601004, system flag and signer SHA-256 (`:54-65`) | No (fingerprint gate) | None; one `getPackageInfo` per check | Off |
| 2 | **Cluster output** (`hud/BydClusterOutput.kt`): broadcast `AUTONAVI_STANDARD_BROADCAST_SEND` to `com.byd.amapservice` | `installed("com.byd.amapservice")` and `byd-navigation-enabled` (`hud/BydNavigationOutputs.kt:41-44,73`) | Only if that package exists | Low | Off |
| 3 | **SOME/IP HUD bridge** (`hud/BydHudBridge.kt`): binds `com.ts.car.someip.service` | Package installed, nav enabled | Only if installed | Low | Off |
| 4 | **CarServer navigation output** (`hud/BydCarServerNavigationOutput.kt`, `BydCarServerTransport.kt`, `com/byd/spi/ipc/cursor/BinderCursor.java`) | Package name ends `.bydhudtest`/`.hudtest` (`:20`); debug manifest asks for `BYDAUTO_INSTRUMENT_COMMON` and `com.byd.car.server.PROVIDER` (`app/src/debug/AndroidManifest.xml`) | No in release; yes for `com.carlink.app.hudtest` | Debug only | Off |
| 5 | **`BydStarterBridge`** (`hud/BydStarterBridge.kt`): client of an ADB-started local socket bridge | Package ∈ {…bydhudtest, …hudtest, `com.carlink.app.hudtest`} (`:29,39`) | No in release | Debug only | Off |
| 6 | `BydNavigationOutputs.start()` on every connect, and from `AapNavigation` init | `available()` = API ≥ 28 and (#1 or #2 or #3) (`hud/BydNavigationOutputs.kt:20-29`; `aap/AapService.kt:997`; `aap/AapNavigation.kt:19`) | Called, returns early | Log line only | No-op |
| 7 | `BydNavigationOutputs.onAppOpened` (journal cleanup after a force-stop) | Only from `MainActivity.onCreate` (`main/MainActivity.kt:122`) | n/a | **In CarLink it never runs** (MainActivity unused). On a BYD a force-stopped HUD arrow would stay until the next session. | Call it from the host when the profile is BYD |
| 8 | **`BydFullscreen`**: forces the projection container to full window height, disables ancestor clipping, adds a touch-forwarding strip window over the "dead" bottom area | **Only API ≥ 30**; acts whenever the parent is ≥ 8 px shorter than `currentWindowMetrics` (`utils/BydFullscreen.kt:34-70`; called unconditionally at `aap/AapProjectionActivity.kt:885-887`) | **Yes, on every API 30+ device** | **Could misbehave on tablets and generic HUs** when system bars or cutouts are visible, i.e. `fullscreen-mode` NONE, STATUS_ONLY or IMMERSIVE_WITH_NOTCH, or OEM bars that cannot be hidden. The container is stretched under the nav bar and a strip window intercepts touches there (inference; not observed) | Only on DiLink ("byd-freeform" caption). Otherwise rely on insets. |
| 9 | **`music-via-bluetooth` default** = `Build.MODEL == "DiLink5.1"` (`utils/Settings.kt:607-610`) | Model string | Default false elsewhere | None (CarLink overwrites it, default false) | false unless the HU is the A2DP sink |
| 10 | **Renderer default** = SurfaceView on DiLink5.1, else TextureView (`view/ProjectionRendererPolicy.kt`) | Model string | TextureView elsewhere | None | Keep |
| 11 | **Soft-AP interface exclusions** `briotgw` (BYD bridge), `ethN`, modem names (`aap/SoftApNetworkPolicy.kt:56-60,112-116`) | Name match | Yes | Positive: generic protection. Could exclude a real AP named `eth_*`? No: only `eth[0-9]+` | Keep |
| 12 | **IPv6 EUI-64 → BSSID** (`connection/P2pInterfaceBssid.kt`) | MAC masked | Yes | None; it rejects opaque identifiers. Proven also on HiBy R4 (Qualcomm A12) | Keep (generic) |
| 13 | **Local-hotspot BYD workarounds**: disconnect a 2.4 GHz station on A11–12 (`LocalHotspotCredentialsProvider.kt:82-99`); plain reservation on A11–12 (`:101-107`) | API level | Only if transport 2, which is unreachable | — | Revisit if re-enabled |
| 14 | **Wireless-channel alignment** to the station's 5 GHz channel (`NativeGroupFrequencyPolicy`) | API 29+, station on 5 GHz non-DFS | Yes | Generic benefit (fewer stalls); a driver may reject it, and retries use band selection | Keep |
| 15 | `com.byd.carsettings` special-casing when opening Wi-Fi or hotspot settings (`main/ConnectionSetupFragment.kt:202`; `main/SettingsFragment.kt:2257`) | Resolved package | No | Standalone UI only | n/a |
| 16 | Manifest `<queries>` for `com.byd.clusterdebug`, `com.byd.amapservice`, `com.ts.car.someip.service` | — | Harmless | None | Keep or drop |
| 17 | Strings and About ("Android Auto receiver for BYD DiLink"), website, `README.md:5` support scope ("These projects focus on BYD cars… other brands are unsupported") | — | — | Expectation: upstream will not fix non-BYD issues | — |
| 18 | **Car keys** (`connection/CarKeyReceiver.kt`, `connection/carkey/fyt/*`, manifest intent list): **not BYD**. Generic Chinese-HU broadcasts: Microntek, FYT (`com.syu`), NWD, Winca, `hy.`, Eryanet, QQMusic car and others. `CarKeyReceiver` **aborts ordered broadcasts** at priority 1000 | Enabled per session by CarLink | Yes | On generic FYT/MTCD stereos it is useful. On a tablet it is harmless. On a stereo it can steal keys from the OEM media app during Android Auto sessions (intended). | Keep, gated to Android Auto sessions |
| 19 | **Boot/ACC and hibernate handling** (`BootCompleteReceiver` list; `AapService` wake receiver; `HIBERNATE_WAKE_THRESHOLD_MS=60s`): **not BYD**. MediaTek IPO, FYT/GLSX `ACCON`, Microntek `ACC_ON`, QUICKBOOT | `auto-start-on-boot` | Yes, while AapService runs | **Tablet:** pops Home on every wake (§10.6.2) | Host-owned; engine flags off |
| 20 | DiLink "Music through car Bluetooth" focus rules (`PlaybackFocusPolicy` NEVER when `music-via-bluetooth`) | Setting | Only if set | — | Follows #9 |
| 21 | Defaults chosen for DiLink: `native-ap-transport=1` (car hotspot; `Settings.kt:1454`), Connection Setup ordering, `docs/CONNECTION_SETUP.md` | — | Yes | **On a tablet or generic HU the hotspot default fails without typed credentials.** CarLink overrides it per session. | Wi-Fi Direct (0) |
| 22 | `wifiDirectName` default "HeadUnit" in the P2P SSID (`WifiDirectManager.kt:1334-1341`) | — | Yes | Cosmetic | Set to the vehicle profile name |

### 12.2 A vehicle-profile setting: how to gate cleanly

Add to CarLink's `UnifiedSettings` a `vehicleProfile` with values `GENERIC` (default), `BYD_DILINK`, and possibly `FYT`/`MTCD`. Auto-suggest it from `Build.MANUFACTURER`/`MODEL`/`FINGERPRINT` (BYD: `FINGERPRINT` starts `BYD-AUTO/`, `MODEL` `DiLink*`) and let the user override it. Map it in `EngineMappings.androidAuto`:

| Profile → | GENERIC (tablet / any HU) | BYD_DILINK |
|---|---|---|
| `byd-navigation-enabled` | **false** (today CarLink defaults `bydDirections=true`; harmless but wrong intent) | user choice, default true |
| `music-via-bluetooth` | false | true (DiAuto's own DiLink default) |
| `native-ap-transport` default for AUTOMATIC | 0 (Wi-Fi Direct) | 1 if hotspot credentials known, else 0 |
| `view-mode` | leave unset (TextureView) | leave unset (Surface on DiLink5.1) |
| BYD HUD journal cleanup | — | call `BydNavigationOutputs.onAppOpened(ctx)` from the host at Home start |
| `BydFullscreen` | **needs a hook**: `HostIntegration.claimHiddenBottomStrip: Boolean` (default true for standalone) checked at `AapProjectionActivity.kt:887` | true |
| Car-key receivers | enabled during Android Auto (existing) | same |
| DiPlay side | `BydOutputSettings.setEnabled(false)` | user choice |

What already self-gates and needs nothing: #1–#6 (fingerprint, package and installed-package checks), #10, #11, #12 and #14.

What needs a host setting or hook: #7, #8, #9, #19 and #21.
