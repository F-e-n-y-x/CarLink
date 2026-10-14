# External Bluetooth modules (FYT/ZLink-type stereos)

> **Status: current.** CarLink detects these stereos, explains the limit and blocks wireless
> before an engine starts (`detect/ExternalBluetoothProbe`, `SessionManager`). Porting a module
> route (below) is a later task and is not started.

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
- **Override:** Settings › Connection › (Show advanced) **Try wireless on this stereo anyway**,
  shown only where the probe found a signal. It lifts CarLink's block only; DiAuto's own
  `ExternalBtPolicy` still refuses unless a manual secondary Bluetooth service is configured, and
  then its report shows the same Home problem.

## Porting plan (about 3-5 days, after a shared log shows which route)

1. **`HandshakeLink` seam in `NativeAaHandshakeManager`** (DiAuto, a `// CarLink` hook and a row in
   `docs/VENDOR_CHANGES.md`): the RFCOMM server/poke code reads and writes a `BluetoothSocket`
   directly (`listenUsingRfcommWithServiceRecord`, `createRfcommSocketToServiceRecord`). Put the
   accept/connect and the byte streams behind an interface with today's Android-socket
   implementation as the default, so the Wi-Fi credential protocol above it stays untouched.
2. **`zbt` package**: upstream's ZBT client for `gocsdk_zj` on 127.0.0.1:3152 (PRs #971, #1010,
   #1031, #1051) as a `HandshakeLink` implementation.
3. **`ExternalBtTransportPolicy`**: picks the link from the probe's classification
   (`ZLINK_DAEMON` → ZBT, `FYT_MODULE` → BLINK, else Android), replacing the blanket refusal, and
   tells `SessionManager` that wireless Android Auto is available again for that classification.
4. **BLINK** (PR #1027) over `/dev/auto_serial`, including the check (and the driver's consent) to
   disable `com.syu.carlink`; verify on an FYT unit.
5. CarPlay is a separate question: DiPlay's iAP2 runs over `BluetoothRfcommDuplexStream`; neither
   upstream route carries iAP2. Until someone shows a module that does, wireless CarPlay stays
   blocked on these stereos.

Every step is verified on the user's stereo with a shared log before it is called done; there is
no emulator stand-in for these modules.

## Alternatives that work today

- **A cable.** USB CarPlay and USB Android Auto work on these stereos.
- **A USB wireless Android Auto (or CarPlay) dongle.** The dongle pairs with the phone over its own
  Bluetooth and Wi-Fi and presents itself to the stereo as a wired phone on USB, so CarLink's USB
  path serves it. Not yet tested with CarLink.
- **Headunit Server.** A phone-side companion app takes the place of the head unit's Bluetooth
  handshake, so wireless Android Auto can start without it. Android Auto only, it needs an extra
  app on the phone, and it has not been evaluated with CarLink.
