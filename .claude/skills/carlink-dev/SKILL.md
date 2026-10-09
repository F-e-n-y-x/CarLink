---
name: carlink-dev
description: How to build, change, test and ship the CarLink Android app (one APK that runs Apple CarPlay via the DiPlay engine and Android Auto via the DiAuto engine, auto-detecting the phone). Use this skill for ANY work in the CarLink repository — UI screens, settings, engine integration, USB/Bluetooth/Wi-Fi connection logic, vendor engine changes, builds, tests, screenshots, emulator checks, commits — even when the request only says "fix the app", "make the UI better", "add a setting" or "why doesn't my iPhone connect".
---

# CarLink development

CarLink is one Android app for car head units, tablets and phones used as a car display. Plug in
or pair a phone: an iPhone gets CarPlay, an Android phone gets Android Auto. The user wants a
polished, single, fully integrated app: plug-and-play for beginners, every engine setting
available to advanced users, and no trace of the two original apps.

Read `README.md`, `docs/VENDOR_CHANGES.md` and the latest design docs in `design/` before a
non-trivial change. The user's decisions recorded there override older documents.

## Architecture rules

- **Engines are vendored, not forked in place.** `vendor/diplay` (CarPlay, main process) and
  `vendor/diauto` (Android Auto, `:aa` process) are git subtrees so upstream fixes can be pulled.
  Change them only through small hooks marked `// CarLink`, and list every vendor edit in
  `docs/VENDOR_CHANGES.md`. Prefer a host-integration object (`HostIntegration`,
  `CarPlayHostIntegration`) over editing engine logic.
- **One app, never "app inside app".** CarLink's own screens (`app/`) are the only UI the driver
  sees, apart from the two projection surfaces (`CarPlayHostActivity`, `AapProjectionActivity`).
  Never route the driver to `DiPlayActivity`, DiAuto's `MainActivity`/`SettingsActivity`, or any
  engine settings screen; drive the engines through `EngineCoordinator` and expose their settings
  natively.
- **One settings model.** A setting both engines share (resolution, frame rate, interface size,
  audio routing, microphone, GPS, day/night, driving side, wheel keys, auto-start, wireless
  method) is ONE CarLink setting that is written into each engine's own storage. Engine-only
  settings live under that engine's section of Advanced. DiAuto's settings belong to the `:aa`
  process: read/write them through the `:aa` process, never via a second `SharedPreferences`
  instance in the main process (each process caches prefs and would overwrite the other).
- **Android Auto stays in `:aa`.** DiAuto casts the application context to `App`, keeps
  singletons, enables car mode and may `System.exit` on disconnect. `CarLinkApplication` extends
  `App` and only initializes the engine in `:aa`.
- **One engine at a time.** `EngineCoordinator` stops one engine before starting the other (Wi-Fi
  Direct group, hotspot, VPN slot, audio focus, MediaSession are exclusive). DiAuto's key/media
  receivers stay disabled except during Android Auto sessions.
- **Detection is CarLink's job.** USB: Apple VID 0x05AC → CarPlay; Android interfaces → Android
  Auto; peripherals ignored (`PhoneClassifier`). Don't rely on Android's USB attach dispatch alone:
  iPhones also expose PTP, so Photos/MTP compete for it; CarLink watches USB itself.
- **Wireless defaults only to paths proven on a real device.** Wi-Fi Direct is the engines' own
  most reliable path (DiAuto/Open Headunit wiki: "Best for stability. No hotspot required";
  DiPlay's Existing Wi-Fi is validated on one car only and needs the router to pass Bonjour).
  Same Wi-Fi is an option, not something to assume. Any change to path choice is verified with the
  `carlink-device` skill on the real tablet before it is called done.
- **Generic, not BYD.** Both engines were written for BYD DiLink head units; the user's car is not
  a BYD. Everything BYD-specific (HUD output, DiLink hooks, cluster, MAC quirks) stays off unless
  the driver picks that car brand. See `docs/dissection/` for the per-engine maps.

## Build and test loop

Use `.claude/skills/carlink-dev/scripts/dev.sh` (it encodes paths, the CarPlay auth files and the
emulator-only rule):

```sh
S=.claude/skills/carlink-dev/scripts/dev.sh
$S build        # debug APK; includes ~/.carlink-private CarPlay files when present
$S test         # resource-collision check + all four unit-test suites
$S install      # install on the emulator and launch
$S shot home    # /tmp/carlink-shots/home.png — then LOOK at it with the Read tool
$S size 1080x2400 420   # phone; "1920x1080 240" head unit; "reset" to restore
$S logs
```

Environment facts: the Gradle daemon needs JDK 25 (registered in `~/.gradle/gradle.properties`);
the SDK is the writable overlay `~/android-sdk`; CarPlay authentication files live only in
`~/.carlink-private/offline-mfi/` and must never be committed (`.gitignore` blocks `*.pk8`,
`*.p7b`).

## Device rule

UI work is checked on the emulator (`emulator-5560`, AVD `carlink_headunit`). Connection work
(USB, Bluetooth, Wi-Fi Direct, real CarPlay/Android Auto) cannot be tested there: the user allowed
live testing on their tablet (`192.168.10.103:5555`, Redmi Pad Pro 5G, Android 16) on 2026-10-09 —
use `CARLINK_TARGET=tablet dev.sh install|logs|shot`, ask the user to plug in or tap Connect, and
read the logs (DiAuto writes `files/diagnostics/current.log`, readable with `run-as`). Never change
the tablet's system settings. **Current instruction (2026-10-09 03:10): do not use the tablet; use
the emulator only until the user says otherwise.**

Work autonomously (the user granted full permission, internet access and use of this PC) and run
**at most two agents at a time**.

A UI change is not done until you have installed it, screenshotted it at head-unit size and at
phone size, and looked at the images for clipped text, wrong colors, empty areas and anything
that looks unfinished. Report what you saw, honestly, including what you could not verify
(USB, Bluetooth and real phones cannot be tested on the emulator).

## UI rules

- Visual direction: **premium dashboard** (Tesla/Rivian-like): full-bleed dark canvas, bold
  type, large hero for the phone/connection state, layered translucent cards faked without
  real-time blur (head-unit GPUs are weak). Follow the current `design/` documents for tokens.
- English only. No hard-coded strings in code; prefix new strings `carlink_`.
- Two size profiles from `UiProfile`: CAR (76dp targets, 24sp minimum text) and HANDHELD
  (Material 3 sizes). Layouts adapt to window size classes: phone portrait/landscape, tablet,
  head unit, BYD split screen.
- Beginners: zero configuration — Home shows one status and one action; connection is automatic.
  Advanced users: an Advanced area with every engine setting, labelled with what it affects
  and whether it applies now or at the next connection.

## Commit and push

Small commits with an imperative subject and a body explaining why; end with
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Build and run `$S test` before each
commit; never push a commit that does not compile. The remote is the user's private repo
`F-e-n-y-x/CarLink` (`git push` from `main`).
