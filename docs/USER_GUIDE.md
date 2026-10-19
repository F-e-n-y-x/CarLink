# CarLink user guide

Everything a driver may want to know beyond the [README](../README.md): first-time setup, how
CarLink picks and connects a phone, updates, the screens and settings, connection times and the
known limits. Problems and their fixes are in [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

## Install

Install CarLink on the display (the car stereo, tablet or phone used as the car's screen), not
on your phone. The steps, and how to check the download, are in the
[README](../README.md#install). To check the signing certificate on a computer with the Android
SDK's build-tools:

```sh
sha256sum -c CarLink-X.Y.Z.apk.sha256          # prints "CarLink-X.Y.Z.apk: OK"
apksigner verify --print-certs CarLink-X.Y.Z.apk
```

`apksigner` must show exactly one signer, with
`Signer #1 certificate SHA-256 digest: 037468ca5beb70ea5f68543329b03368247dfb393d3f626a6b472d10e5657d21`
(the README's colon-separated fingerprint, written without colons). An APK signed with any other
key is not CarLink's release: Android refuses to install it over the release, and the updater ignores it.

## First-time setup

The first time CarLink opens, **Set up CarLink** walks through five steps (about two minutes;
**Skip setup** leaves it, and everything can be done later from Home and Settings):

1. **Welcome.** Tap **Start**.
2. **Access.** Tap **Allow all**; Android asks for each permission once: Bluetooth, nearby Wi-Fi
   devices, location (Android needs it for Wi-Fi Direct; maps get the car's position), local
   network, microphone (Siri, Google Assistant, calls), notifications, display over other apps,
   running in the background, and the wired CarPlay link (a local network link to an iPhone on
   the cable, no internet traffic). Items marked **Optional** can be allowed later.
3. **Car.** Pick the car's brand, or **Other / not listed** (see [Car brand](#car-brand)).
4. **Phone.** On the phone, open Bluetooth and tap this screen's name. If the phone shows a
   code, check that it matches and tap **Pair**. A tablet cannot start the car-kit side of
   pairing itself, so pairing is always started on the phone. Or choose **Plug in with a cable
   instead**: a cable works without pairing. Then accept **Use CarPlay** on the iPhone, or
   Android Auto's screens on the Android phone, and keep the phone unlocked; the first
   connection can take up to half a minute.
5. **Done.** Next time the phone connects by itself when it is near the screen, or when you plug
   it in.

Later, **Pair a phone** on Home opens the display's Bluetooth settings so it is visible, and
**Connect** on Home (or plugging in) starts a session.

## How connections work

| The phone… | CarLink decides | Engine |
|---|---|---|
| is plugged in and has Apple's USB vendor ID (0x05AC) | iPhone | CarPlay over USB |
| is plugged in and exposes Android interfaces (accessory, MTP, PTP, ADB, RNDIS) | Android phone | Android Auto over USB |
| connects over Bluetooth and publishes the iAP2 service, or is named "iPhone"/"iPad" | iPhone | wireless CarPlay |
| connects over Bluetooth and publishes phone services without iAP2 | Android phone | wireless Android Auto |
| was identified before (a successful session or the driver's choice) | as remembered | |

USB flash drives and other peripherals are ignored. A phone without enough evidence is queried
over Bluetooth SDP first; nothing starts until CarLink is sure.

- **Wi-Fi Direct by default.** Wireless sessions use a Wi-Fi Direct link between the phone and
  the display, which needs no router and no hotspot. On a car head unit whose own hotspot
  details are known, wireless CarPlay tries that hotspot first. The path that worked last time
  for a phone is tried first next time; only a network-related failure moves on to the next path.
- **Same Wi-Fi is opt-in.** Settings › Connection can use the Wi-Fi
  network the display is already on. It needs that network's password once. It is never chosen
  automatically (see [Known limits](#known-limits)).
- **Plugging in starts at once.** A cable is a deliberate act, so a USB phone starts its engine
  straight away when nothing else runs.
- **The last phone connects by itself.** When Home comes to the front (the app opens, the screen
  wakes), CarLink starts the last-used phone wirelessly, as a car stereo does when the car starts.
  It does this at most once a minute and not right after a session ended. After you press
  Disconnect or Cancel, automatic starts pause until you connect again or for 10 minutes.
- **A second phone asks first.** Plugging in another phone while a session runs shows
  "Switch to &lt;phone&gt;?" (Switch / Not now) instead of taking over.
- **Unplugging ends a wired session** and brings Home back; the engines' own USB re-enumeration
  while connecting is not mistaken for an unplug.
- **One engine at a time.** A new session starts only after the previous one has fully stopped,
  so the two engines never share the Wi-Fi Direct group, VPN slot, audio focus or media session.

## Updates

CarLink updates itself from this repository's Releases:

- It checks GitHub at most **every 6 hours**, only while CarLink's own screen is open and **no
  phone is connecting or connected**. It never interrupts CarPlay or Android Auto.
- When a newer version exists, a popup shows **Update available — CarLink X.Y.Z** with the release
  notes and three choices: **Ignore this version** (no more popups for it; a later version is
  offered again), **Later** (asks again at the next check) and **Download & install**.
- The download goes to CarLink's private storage with a progress bar and **Cancel**. Before
  anything is installed, CarLink checks that the file matches the release's `.sha256` file, that
  it is CarLink (`com.carlink.app`) with a higher version, and that it is signed with CarLink's
  release key. If any check fails, it says why and deletes the file.
- Android asks once to let CarLink install apps, then shows its own install confirmation.
- **Settings › About › Check for updates** checks now ("You're up to date" or the popup);
  **Automatically check for updates** in the same place turns the automatic checks off.

Only the official release updates itself. Debug builds (`com.carlink.app.hudtest`) and builds you
sign yourself never check or install updates. The only data sent is the request to GitHub's
public API (with CarLink's version in the user agent).

## What the app has

- **One Home screen.** One status and one action: a permission or Bluetooth fix when one is
  needed, "Connect &lt;phone&gt;?", a step-by-step Connecting view, the running session with its timer,
  or a plain-language problem with its one-tap fix. A Phones sheet lists paired phones.
- **Native settings.** Every screen is CarLink's own, never an engine's settings app. Settings
  shared by both engines (picture, interface size, day/night, driving side, sound routing,
  location, connection method) are one setting written into each engine before each session.
  **Show advanced** adds every engine-only option inside its category; **Lab** (seven taps on the
  version) holds experimental and debug options.
- **A floating switcher** over CarPlay and Android Auto: a small bubble that opens a panel to
  switch to another paired phone or open CarLink's Home.
- **Diagnostics.** Device checks with one-tap fixes, the live session, and a connection log
  that merges CarPlay, Android Auto and CarLink's own decisions (addresses hidden), shareable.
- **One identity.** Engine notifications, media sessions, the name the phone shows for the car
  and the projection screens' own chrome say CarLink.

Screenshots of every screen and state: [screenshots/README.md](screenshots/README.md).

## Car brand

First-time setup and **Settings › Car › Car brand** ask which car the screen is in: makes
popular in India first, then luxury makes, then **Other / not listed**. For every make except
BYD this is only a label: CarPlay and Android Auto work the same in any car, and the brand never
reaches the phone (the name the phone shows for the car stays "CarLink"). Choosing **BYD** turns
on the DiLink extras the engines were written for: head-up display, instrument cluster and
steering-wheel keys. The picker shows what CarLink detected about the screen (for example "Detected:
Generic"); until you choose, **Other / Generic** applies.

## Steering-wheel buttons

**Settings › Car › Steering-wheel buttons** lets a button on the steering wheel do one of six
things in both CarPlay and Android Auto: **Voice assistant** (Siri or Google Assistant), **Answer
call**, **Hang up**, **Play / Pause**, **Next** and **Previous**. Tap **Learn** next to an action,
then press the button within 15 seconds; **Cancel** or Back stops waiting. Each action has one
button, and a button learnt for a second action moves to it. **Reset all** forgets every button.
The buttons apply from the next connection.

CarLink turns the learnt button into the standard Android key for that action, which both
engines already handle. In CarPlay this works while the CarPlay screen is in front; in Android
Auto it also works for buttons the stereo sends as media buttons or as its own broadcasts.

Limits:

- **Buttons the stereo keeps for itself never reach CarLink.** Many stereos act on some wheel
  buttons in their firmware (often volume, mode, the stereo's own voice or phone button). No app
  sees those presses, so Learn waits and then says no button arrived. On some stereos the
  manufacturer's "steering wheel" settings app can give such a button a standard function first.
- Volume, Back and Home cannot be learnt: they keep their usual job.
- Buttons a stereo reports only through its own broadcast (some FYT, NWD, Eonon units) cannot be
  learnt here yet, because CarLink's screen sees key presses only.

## Slower head units

**Settings › General › Visual effects** is **Automatic** by default. On a device with little
memory or processor power it picks **Lite**: plain fills and fewer redraws, with the same layout
and colours. **Full** keeps the soft light, glow, grain and animation. Measurements are in
[LOW_END_PERFORMANCE.md](LOW_END_PERFORMANCE.md).

## Connection times

Measured on the test tablet (Redmi Pad Pro 5G, Android 16) with the maintainer's iPhone and Samsung
Galaxy S25 Ultra, from plug-in or Connect to the first picture, 2026-10-09:

| Connection | Measured on the test tablet |
|---|---|
| CarPlay over USB | about 3.4 s |
| Android Auto over USB | about 3.8 s |
| Android Auto, wireless (Wi-Fi Direct) | about 5 s |
| CarPlay, wireless (Wi-Fi Direct) | about 16-20 s, dominated by paging the iPhone over Bluetooth |

Other head units, tablets and phones are expected to be in the same range but have not been
measured. Each engine writes always-on timing lines (`WIRED_TIMING`, `WIRELESS_TIMING`) to its
log, so any device can be measured; see [BUILDING.md › Testing on a device](BUILDING.md#testing-on-a-device).

## Known limits

- **Some stereos cannot connect wirelessly yet.** On many FYT and ZLink-type stereos the
  Bluetooth is a separate module that apps cannot use; the phone pairs with that module, not with
  Android. Wireless CarPlay and Android Auto both start over Bluetooth, so CarLink does not try
  wireless there: Home says "Wireless isn't available on this stereo yet" and Diagnostics names
  what it found. A USB cable works the same. See
  [troubleshooting](TROUBLESHOOTING.md#while-connecting) and
  [design/EXTERNAL_BLUETOOTH.md](../design/EXTERNAL_BLUETOOTH.md).

- **During wireless Android Auto the phone uses mobile data.** The phone joins the display's
  Wi-Fi link, which has no internet, so its apps go over mobile data. Allow Android Auto to use
  mobile data on the phone. No app on the display can change this.
- **Same Wi-Fi CarPlay needs a router that passes mDNS (Bonjour).** Many home and mobile routers
  block it between clients; then the iPhone authenticates over Bluetooth but never reaches the
  display, and Home suggests Wi-Fi Direct. It did not work on the test router. Same Wi-Fi
  Android Auto needs the network's password and the router's exact address (BSSID), so both
  stay opt-in.
- **A tablet is not a Bluetooth car kit.** It has no hands-free or car-audio role, so the phone
  never connects to it by itself when you get in. CarLink starts the connection instead (Home
  shown, Connect, plug-in). A car head unit with those roles is reconnected by the phone itself.
- **CarPlay needs authentication files** that are not part of this repository. The release APK
  includes them; a build from source does not (see
  [BUILDING.md](BUILDING.md#carplay-authentication-files-not-in-git)). Without them Android Auto
  still works and Home says why CarPlay cannot sign in.
