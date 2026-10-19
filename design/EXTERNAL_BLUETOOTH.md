# External Bluetooth modules (FYT/ZLink-type stereos)

> **Status: current.** CarLink detects these stereos, explains the limit and blocks wireless
> before an engine starts (`detect/ExternalBluetoothProbe`, `SessionManager`). Both upstream module
> routes (ZBT and BLINK) are ported into DiAuto from open-headunit v.3.5.0 and can be tried with an
> **experimental, off-by-default** setting ([below](#the-module-routes-experimental)). Not yet
> verified on a real stereo.

## The problem

Many cheap Android stereos (FYT units, ZLink-type units) do not use Android's Bluetooth for
phones. Their Bluetooth is a separate module on a serial link, driven by vendor firmware; the
phone pairs with that module. Android's own adapter either does not exist, reports a placeholder
address, or "connects" without reaching anything.

Wireless CarPlay and wireless Android Auto both begin over Bluetooth (iAP2 for CarPlay, the
RFCOMM Wi-Fi credential exchange for Android Auto), and both engines open an Android
`BluetoothSocket`. On these stereos nothing written there reaches the phone. **USB CarPlay and USB
Android Auto are unaffected.**

Seen on the user's stereo (2026-10): FYT-type unit (MCU "…FYTR"), Unisoc UIS8581A, Android 10,
1280×720; phone OnePlus Nord 5 paired to the module. DiAuto emitted `EXTERNAL_BLUETOOTH`
(evidence from `ExternalBtPolicy`), Home said "Bluetooth connection failed — turn Bluetooth off
and on…" (wrong advice) and Diagnostics said "Everything looks ready". CarLink had no adb access to
the stereo, so the app must collect the evidence itself.

## What upstream does

Upstream [open-headunit](https://github.com/andreknieriem/open-headunit) (DiAuto's origin) carries
the whole wireless Android Auto handshake through such modules by two routes, shipped in
open-headunit 3.5.0-beta4 ([releases](https://github.com/andreknieriem/open-headunit/releases)):

- **ZBT** (ZLink-type modules): talks to the module daemon `gocsdk_zj` on TCP `127.0.0.1:3152`
  and has it run the RFCOMM exchange with the phone. PRs
  [#971](https://github.com/andreknieriem/open-headunit/pull/971),
  [#1010](https://github.com/andreknieriem/open-headunit/pull/1010),
  [#1031](https://github.com/andreknieriem/open-headunit/pull/1031),
  [#1051](https://github.com/andreknieriem/open-headunit/pull/1051).
- **BLINK** (FYT's DUDUAUTO module): talks to the module over `/dev/auto_serial`, signalled by
  `sys.fyt.bluetooth_type` > 0. The stock FYT app `com.syu.carlink` must be disabled, because it
  owns the same serial port. PR [#1027](https://github.com/andreknieriem/open-headunit/pull/1027).

Background and reports: issue [#1005](https://github.com/andreknieriem/open-headunit/issues/1005).
DiAuto's `ExternalBtPolicy` (vendor/diauto, `aap/ExternalBtPolicy.kt`) is upstream's older
detector: it only knows ZLink markers (`/dev/rf_serial`, `/dev/zj_bt_serial`,
`rw.zlink.bt.type`/`rw.zj.bt.type` = `extra`) and only refuses; it carries nothing.

Which route the user's stereo needs is unknown. That is what the probe is for.

## What the probe collects

`carlink-core/.../detect/ExternalBluetoothProbe.kt`, once per app start on its own thread (the port
check takes at most 300 ms), cached for the process. Its block goes into `files/logs/carlink.log`
and into Diagnostics › **Share log**, right after the header:

```
External Bluetooth probe: classification=FYT_MODULE complete=true took=318ms
  signals=FYT_SETTING,MODULE_SERIAL_PORT
  diauto.evidence=/dev/rf_serial exists
  hardware=<Build.HARDWARE>/<Build.BOARD>
  prop sys.fyt.bluetooth_type=1
  prop rw.zlink.bt.type=<unset>
  prop rw.zj.bt.type=<unset>
  node /dev/auto_serial=absent
  node /dev/BT_serial=absent
  node /dev/rf_serial=exists
  node /dev/zj_bt_serial=absent
  port 127.0.0.1:3152=CLOSED
  packages=com.syu.carlink 2.1 (21)
  adapter present=true enabled=true realAddress=false
  bonded=0
  knownPhones=phone1(ANDROID) bondedToAndroid=false
```

(The values above are an example, not the user's stereo.)

| Field | Source | Why |
|---|---|---|
| `diauto.evidence` | `ExternalBtPolicy.detect` (called directly) | what DiAuto refuses on |
| `prop sys.fyt.bluetooth_type` | system property | BLINK's signal |
| `prop rw.zlink.bt.type`, `rw.zj.bt.type` | system properties | ZLink markers |
| `node /dev/auto_serial` | file exists | BLINK's serial port |
| `node /dev/BT_serial` | file exists | recorded only; decides nothing |
| `node /dev/rf_serial`, `/dev/zj_bt_serial` | file exists | ZLink markers |
| `port 127.0.0.1:3152` | TCP connect, then close at once; nothing is sent | ZBT's daemon |
| `packages` | package manager: `com.syu.carlink`, `com.zjinnova.zlink`, and names containing zlink, zjinnova, carlink, carplay, androidauto, easyconn, autokit, phonelink, carbit, speedplay, gocsdk, tlink (never CarLink itself) | name and version only |
| `adapter` | Android's `BluetoothAdapter`: present, enabled, and whether its address is real (not `02:00:00:00:00:00`; the address itself is never stored) | |
| `bonded`, `knownPhones` | how many devices Android lists as bonded; for each phone CarLink knows, whether it is among them | never a MAC; `LogMasking` applies to the block |
| `hardware` | `Build.HARDWARE/Build.BOARD` | the SoC family |

The package query works on Android 10 (the stereos in question) without extra permissions;
Android 11+ sees only what `carlink-core`'s manifest `<queries>` names (the two packages and
launcher apps).

## Classifications and what each points to

| Classification | Rule (in this order) | Wireless | Future port |
|---|---|---|---|
| `NONE` | no signal | normal | nothing |
| `LIKELY_FALSE_POSITIVE` | a signal, but a known phone is bonded to Android's adapter and that adapter has a real address | not blocked; Diagnostics warns "may" | none needed if wireless works; else treat as below |
| `ZLINK_DAEMON` | 127.0.0.1:3152 accepts a connection | blocked | **ZBT** |
| `FYT_MODULE` | `sys.fyt.bluetooth_type` > 0 or `/dev/auto_serial` | blocked | **BLINK** (and disable `com.syu.carlink`) |
| `EXTERNAL_UNKNOWN` | ZLink markers, no daemon | blocked | unknown: the log decides (a daemon on another port, or a new route) |

Android 6+ gives apps the placeholder address, so `LIKELY_FALSE_POSITIVE` mostly fires on firmware
that still exposes the real one (`Settings.Secure` `bluetooth_address`). That is deliberate: the
classification blocks only on positive evidence and never guesses that a module is unused.

## What CarLink does today

- **Pre-flight.** `SessionManager.start` blocks every WIRELESS request for both engines with
  `Problem.EXTERNAL_BLUETOOTH` before any engine, settings write, Bluetooth warm-up or Wi-Fi
  Direct group, when `Environment.wirelessUnavailable` (`DeviceEnvironment`: the probe says
  `ZLINK_DAEMON`, `FYT_MODULE` or `EXTERNAL_UNKNOWN`, and the driver's override is off). USB is
  never blocked. Home's wireless auto-start (`onHomeShown`) and Bluetooth auto-start
  (`onBluetoothPhone`, including the switch offer) are skipped. Before the full probe finishes,
  pre-flight uses the quick probe (files, properties, bonds; no port check), which reaches the
  same block decision except for a daemon with no other marker.
- **Engine reports.** DiAuto's `EXTERNAL_BLUETOOTH` event becomes `FailureReason.EXTERNAL_BLUETOOTH`
  (`AaSessionTracker`); DiPlay's `BLUETOOTH_UNUSABLE` becomes it when the probe found any signal
  (`CarPlayEventMapping.classify(..., externalBluetooth)`). Both map to
  `LinkProblem.EXTERNAL_BLUETOOTH` (`LinkStateMapping`), not to "Bluetooth connection failed".
- **Home:** "Wireless isn't available on this stereo yet", with **How to connect** (the cable,
  step by step) and **Diagnostics**; no Try again.
- **Diagnostics:** a "Car stereo Bluetooth" check (green on `NONE`, amber otherwise, naming the
  evidence), listed first while no session runs, so the summary never says "Everything looks
  ready" on such a stereo.
- **Setup:** the pairing step says wireless is not available yet and that USB works.
- **Opt-in:** Settings › Connection › (Show advanced) **Wireless through the stereo's Bluetooth
  module (experimental)**, shown only where the probe found a module (`ZLINK_DAEMON`, `FYT_MODULE`,
  `EXTERNAL_UNKNOWN`). It replaced "Try wireless on this stereo anyway" and keeps its preference
  key (`wireless_on_external_bluetooth`). See the next section.

## The module routes (experimental)

Ported from [open-headunit](https://github.com/andreknieriem/open-headunit) tag **v.3.5.0**, commit
`1c1b5b7261ebebde8000778ff2c06e3af3e3a326` (AGPL-3.0, like CarLink); each new file says so in its
header, and `docs/VENDOR_CHANGES.md` lists every edit.

| Piece | Where (vendor/diauto/app/src/main/java/com/andrerinas/openheadunit/) | Upstream |
|---|---|---|
| `HandshakeLink`, `BluetoothSocketLink`, `ExternalModuleCarrier` | `connection/HandshakeLink.kt` | `nativeaa/HandshakeLink.kt`, `ExternalModuleCarrier.kt` |
| Route decision NORMAL / ZBT / BLINK / BLOCKED, the host's key, step logging, failure report | `connection/ExternalBtTransportPolicy.kt` | `nativeaa/ExternalBtTransportPolicy.kt` (only `route()`, with CarLink's inputs) |
| ZBT: 16-byte BE header (magic `0x0000FFFF`, version `0x101`, id, length) + protobuf body, `RequestInit{enable_type=2}` (0x101), raw RFCOMM bytes (0x105), `RequestReconn` (0x114) as the wake, link-state presence, attempt/wake/reopen/retransmit pacing | `connection/zbt/` | PRs #971, #1010, #1031, #1051 |
| BLINK: `/dev/auto_serial` line protocol (`JH<MAC>`, `AA`, `SS`, `ZB<hex>` in; `AT#ZA<hex>\r\n` out), raw tty (`stty raw -echo -iuclc …`), opened as the app when `com.syu.carlink` is disabled, else through `su` (refused when the stock app is enabled), idle ping answers | `connection/blink/` | PR #1027 |

How a session uses it:

1. The driver turns the setting on. `DeviceEnvironment.wirelessBlocked` stops blocking wireless
   Android Auto (CarPlay stays blocked),
   and `DeviceEnvironment.moduleRoute` names the route: `ZLINK_DAEMON` and `EXTERNAL_UNKNOWN` → ZBT,
   `FYT_MODULE` → BLINK. Pre-flight for wireless Android Auto then skips "Bluetooth is off" and
   "phone not paired" (the phone pairs with the module, not with Android).
2. `AaSessionSettings` writes the route into DiAuto's settings (`carlink-external-bt-route` = `ZBT`,
   `BLINK`, or empty for USB sessions and every other stereo); carlink.log says
   `Android Auto Bluetooth through the stereo's module: <ROUTE> (experimental)`.
3. In `:aa`, mode 3 asks `ExternalBtTransportPolicy.routeFor` (the host's route wins; without one,
   DiAuto's own ExternalBtPolicy markers still refuse as before). Wi-Fi Direct starts as usual, and
   `NativeAaHandshakeManager.start` hands over to the module carrier instead of opening RFCOMM
   listeners. The carrier hands each phone to the unchanged handshake (`WppHandshakeSession`) as a
   `HandshakeLink`. CarLink's wake pokes become `RequestReconn` on ZBT and nothing on BLINK (its
   module opens Android Auto itself once the phone's hands-free link is up).
4. If the module cannot be reached (ZBT: nothing answers on 3152 for 30 s; BLINK: `com.syu.carlink`
   enabled or still running, `/dev/auto_serial` not openable and no root), the engine reports
   `EXTERNAL_MODULE_UNAVAILABLE`, CarLink fails the session with `FailureReason.EXTERNAL_MODULE`, and
   Home says **Couldn't reach the stereo's Bluetooth module** with the cable help. Root is never
   required: `su` is only tried as upstream's fallback, and a stereo without it fails cleanly.

Every step is logged in DiAuto's journal (`files/diagnostics/current.log`, in **Share log** and
**Report on GitHub**) with a `NativeAA: [ZBT]`, `[BLINK]` or `[module]` prefix, and the milestones as
`WIRELESS_TIMING` lines:

| Line | Means |
|---|---|
| `module-reachable` | ZBT: the daemon accepted the connection and `RequestInit` went out. BLINK: whether `/dev/auto_serial` exists and the stock app's state |
| `module-ready` | ZBT: the daemon answered. BLINK: the port is open and listening |
| `module-wake` | ZBT: `RequestReconn` sent. BLINK: nothing to send (said once) |
| `phone-on-module` | BLINK: the phone opened the Android Auto channel (`AA`) |
| `phone-first-bytes` | the phone's first bytes arrived over the module |
| `phone-rfcomm`, `wifi-start-request`, `wifi-info-response` | the handshake started, WifiStartRequest and the credentials went out (as on the normal route) |
| `phone-joined`, `tcp-accepted`, `first-frame` | the phone joined Wi-Fi Direct and Android Auto started (as on the normal route) |

The Wi-Fi password is never logged: raw module frames are logged by size only (an upstream log line
that printed them in hex was changed).

### Trying it on a stereo, step by step

1. Install CarLink, open Diagnostics and check **Car stereo Bluetooth**: it should name the module
   (the full probe block is in **Share log**, right after the header).
2. On an FYT stereo (probe `FYT_MODULE`): Android Settings › Apps › Car Link (`com.syu.carlink`) ›
   **Disable**. CarLink never disables it itself. Calls keep going through the module.
3. Pair the phone with the stereo as usual (the stereo's own Bluetooth screen, i.e. the module).
4. CarLink › Settings › Connection › **Show advanced** › turn on **Wireless through the stereo's
   Bluetooth module (experimental)**.
5. On the phone, make sure Android Auto's wireless option is on; keep Wi-Fi and Location on the
   stereo on.
6. Home › **Connect** with the phone (wireless). Wait up to a minute.
7. Whatever happens, open Diagnostics › **Report on GitHub** (or **Share log**). The
   `WIRELESS_TIMING` lines above show the last step reached; the `[ZBT]`/`[BLINK]` lines around it
   say why.
8. To go back: turn the setting off (wireless is blocked again) and use the cable.

### Not ported, and why

- Upstream's daemon measurement (`ZbtProbe`, `ZbtDaemonReachability`): CarLink's own probe decides,
  and the driver opts in.
- Seeding this unit's Bluetooth address from the module (upstream uses it to announce the
  Bluetooth service so calls stay on the car): this DiAuto has no such setting.
- Join-refusal pacing, upstream's UI toggles, toasts and the main screen's Wi-Fi button.
- **CarPlay**: DiPlay's iAP2 runs over `BluetoothRfcommDuplexStream`; neither route carries iAP2.
  The setting therefore unblocks wireless Android Auto only: wireless CarPlay stays blocked before
  any engine starts ("Wireless isn't available on this stereo yet"), and Home's wireless auto-start
  still skips an iPhone.

## Alternatives that work today

- **A cable.** USB CarPlay and USB Android Auto work on these stereos.
- **A USB wireless Android Auto (or CarPlay) dongle.** The dongle pairs with the phone over its own
  Bluetooth and Wi-Fi and presents itself to the stereo as a wired phone on USB, so CarLink's USB
  path serves it. Not yet tested with CarLink.
- **Headunit Server.** On the phone, Android Auto's developer settings offer "Start head unit
  server"; the head unit then connects to the phone over Wi-Fi (TCP port 5277), with no Bluetooth
  handshake. The vendored engine already has this client mode (`NetworkDiscovery`, `CommManager`).
  Android Auto only, the phone and stereo must share a Wi-Fi network, and the driver has to start the
  server on the phone for every drive. Not yet exposed or evaluated in CarLink.
