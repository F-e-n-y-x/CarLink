# CarLink rebuild plan

> **Status (2026-10-09): superseded by [REBUILD_V2.md](REBUILD_V2.md).** This plan led to the v1
> connection layer (`EngineCoordinator`, `CarPlayEngine`, `AndroidAutoEngine`,
> `WirelessPathSelector`), which v2 replaced and which has since been deleted. Kept as a record of
> the first build's problems and the settings inventories.

Status: proposal for approval. Date: 2026-10-09.
Inputs: the user's feedback on the first build, `design/RESEARCH_PREMIUM_UX.md` (visual and settings
research), and full settings inventories of both engines (DiPlay ≈70 settings, DiAuto ≈90).

## What went wrong in the first build

| Problem the user saw | Cause |
|---|---|
| "App inside app" | CarLink opened DiPlay's own home screen to start CarPlay and linked to both engines' settings screens. |
| USB iPhone did nothing, no "Trust" prompt | An iPhone also exposes a photo (PTP) interface, so Photos/MTP competed for the plug-in event and Android never launched CarLink. When DiPlay's screen was on top, CarLink's own USB watcher was paused. |
| CarPlay could not connect at all | The build had no CarPlay authentication files (they ship only in DiPlay's release APK). Fixed: extracted from the verified official APK into `~/.carlink-private`. |
| UI "very bad" | Generic light settings-app look, nothing premium; two apps' worth of screens. |

## Goals

1. **One app.** The driver only ever sees CarLink screens and the CarPlay / Android Auto picture.
2. **Plug and play.** Plug in or bring a paired phone into the car: it connects. No setup wizard; one
   inline card asks for permissions the first time.
3. **One set of settings** that applies to both CarPlay and Android Auto. Engine-only options live in
   Advanced.
4. **Beginner and advanced.** 13 Essentials by default; every engine option under "Show advanced";
   experimental and debug items in a hidden Lab.
5. **Best wireless path automatically.** Same Wi-Fi when possible, otherwise car hotspot, otherwise
   Wi-Fi Direct (details and the Android Auto limit below).
6. **Premium dashboard look** that runs smoothly on head-unit GPUs, works on phones and tablets.

## Wireless: what is possible

| | Same Wi-Fi (phone and car on one router) | Car hotspot | Wi-Fi Direct |
|---|---|---|---|
| CarPlay (iPhone) | **Yes, today.** The car must already be on that Wi-Fi; CarLink stores its password once and tells the iPhone over Bluetooth to join it. | Yes | Yes |
| Android Auto (stock app, no phone app) | **Not supported by the protocol path in this engine.** Stock Android Auto wireless joins a network the car creates. An untested trick (send the router's details instead of the hotspot's) goes in Lab only. | Yes (default) | Yes |

Bluetooth stays the "doorbell" for both: the phone's Bluetooth link starts every wireless session.

**Automatic choice ("Connection method: Automatic")**, decided just before each wireless session:
1. iPhone and the car is on exactly one known Wi-Fi whose name matches the saved one → Same Wi-Fi.
2. Car hotspot is on and its details are saved → car hotspot.
3. Otherwise → Wi-Fi Direct.

Android phones skip step 1. The choice is never changed during a session. Home shows which path is
used ("Same Wi-Fi · fastest", "Car hotspot", "Wi-Fi Direct").

## One settings model

Each unified setting says what the driver wants; CarLink translates it into each engine's own
storage before every session (and immediately for live settings). DiAuto's values are written
inside the Android Auto process through a small settings bridge, because two processes writing the
same preferences file lose each other's changes.

### Essentials (shown to everyone; max 14)

| Setting | Choices | CarPlay (DiPlay) | Android Auto (DiAuto) | Applies |
|---|---|---|---|---|
| Connect automatically | On / Off | CarLink dispatcher | CarLink dispatcher | live |
| Start with the car | On / Off | CarLink boot receiver | CarLink boot receiver | next boot |
| Connection method | Automatic / Same Wi-Fi / Car hotspot / Wi-Fi Direct | `wireless_hotspot_mode` EXISTING_WIFI / MANUAL / WIFI_P2P | `native-ap-transport` 1 (hotspot) / 0 (Wi-Fi Direct) | next connection |
| Car hotspot name and password | text (only when needed) | `manual_hotspot_ssid/passphrase` | `hotspot-ssid/password` | next connection |
| Home Wi-Fi password | text (only for Same Wi-Fi) | `existing_wifi_ssid/passphrase` | – | next connection |
| Picture | Automatic / Sharp / Balanced / Smooth | `display_scale_percent` + `display_fps` | `resolutionId` + `fps-limit` | next connection |
| Interface size | Small / Medium / Large | `display_width_physical_mm` 350 / 300 / 250 | `dpi-pixel-density` computed from the panel | next connection |
| Day and night | Follow the car / Day / Night / Schedule | `carplay_night_mode` | `night-mode` | live |
| Driving side | Left / Right | `right_hand_drive` | `right-hand-drive` | next connection |
| Sound | Through CarLink / Through the car's Bluetooth | `car_bluetooth_audio` | `music-via-bluetooth` | next connection |
| Send car location to phone | On / Off | `location_reporting_enabled` | `gps-navigation` | next connection |
| Directions on HUD and dashboard (BYD only) | On / Off | `BydOutputSettings.enabled` | `byd-navigation-enabled` | live |
| Appearance | Follow the car / Dark / Light | CarLink only | CarLink only | live |

### Advanced (one "Show advanced" switch; rows appear in their normal category)

Shared: exact resolution and frame rate, video codec (Automatic / H.264 / HEVC), software decoding,
audio streams for media and navigation, music buffer, audio focus behaviour, volume offsets,
microphone source and echo cancellation, steering-wheel keys (Siri/Assistant, joystick, map zoom,
key mapping), CarPlay dock position, full screen, Wi-Fi Direct channel/band, hotspot security,
static BSSID, USB auto-confirm, per-engine overrides ("Different for Android Auto…").
CarPlay-only: dashboard map and cluster options, picture adjustments, split screen, side panel,
rotation, buffered music, call voice filters, car button icon/name, vehicle data (ADB).
Android Auto-only: view mode, pixel aspect ratio, insets, rotary controller, AAC audio, latency and
queue, media-key routing, monochrome at night, Bluetooth adapter selection.

### Lab (tap the version 7 times; amber frame while on)

Debug logs and FPS counter, video fault injection, memory profile, legacy 5 GHz Wi-Fi Direct,
Android Auto over the same Wi-Fi (experimental), the engines' self-test modes.

Every category gets "Reset to recommended", a dot on changed settings, and an "Undo recent
changes" offer when connections start failing after an Advanced change. Known upstream bugs are
handled: DiAuto's codec value is always written in canonical form ("Auto", "H.264", "H.265"); its
"disable video lock" switch is dropped because nothing reads it.

## Visual direction: "Graphite and Ion" (premium dashboard)

From the research (Tesla, Rivian, Lucid, Polestar, NIO, MBUX, BYD launchers): near-black canvas with
one soft light source, one accent used only for tappable things, one large hero per screen, clean
sans-serif with tabular numbers, depth from imagery not shadows, calm motion.

- Palette: base `#0C1016`, text `#EEF2F6`, accent `#5BD6F0`, connected `#7EE2A8`; warm "day" variant.
  All text ≥4.5:1.
- Type: Inter (bundled, subset ≤400 KB); bold only at 48sp and above.
- Materials: background atmosphere, "glass" cards faked without blur (each card draws its crop of a
  pre-blurred background, plus a faint fill and a lit edge), solid accent button. Automatic "Lite"
  tier for weak GPUs.
- Hero: an unbranded phone render whose screen shows live status (detected phone, progress,
  connected). No car renders (CarLink cannot know the car).
- Home: one hero, one headline, up to three status chips, one 96dp button; phones list beside it.
- Car and Handheld size profiles and window-size layouts stay (head unit, split screen, tablet,
  phone).

## Architecture changes

1. **CarLink starts CarPlay directly.** CarLink runs DiPlay's pre-flight checks itself (authentication,
   saved Wi-Fi details, iPhone selection, permissions) and opens only the CarPlay picture
   (`CarPlayHostActivity`). DiPlay's home and settings screens are never shown
   (`CarPlayHostIntegration`, done).
2. **USB detection that always works**: an app-wide watcher that keeps running while CarLink or the
   CarPlay/Android Auto picture is open, plus the system plug-in route. It identifies the phone and
   lets the engine ask for USB access (which is what triggers the iPhone's "Trust" prompt).
3. **Settings bridge to the Android Auto process** (a private ContentProvider in `:aa`) so unified and
   Advanced settings write DiAuto's storage safely and notify the running service.
4. **Unified settings store + per-engine appliers**, unit tested (each setting → exact engine keys).
5. **Remove the old CarLink screens** (onboarding wizard, link-out settings) and DiAuto's/DiPlay's
   leftover entry points.

## Build order (each step: build, unit tests, emulator screenshots at head-unit and phone size)

1. Engine integration: direct CarPlay start, app-wide USB watcher, Android Auto settings bridge.
2. Unified settings store, appliers and automatic connection-method selection, with tests.
3. Visual system "Graphite and Ion" and the new Home + connecting experience.
4. Settings UI: Essentials, Advanced, Lab; inline permission card.
5. Diagnostics and About restyled; remove old screens; docs and screenshot gallery.

Testing: the emulator only (UI, settings, flows). Real USB, Bluetooth, CarPlay and Android Auto
sessions can only be verified by the user on the tablet and in the car.
