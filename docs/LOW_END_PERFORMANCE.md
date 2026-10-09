# CarLink on low-end head units

October 2026. What was measured on a cheap-head-unit emulator, what changed, and the rules
CarLink now follows on weak hardware: lite rendering, deferred start-up work, Automatic picture
from the hardware decoder, and a smaller APK.

Typical targets: Android 10-13, 1-4 GB of RAM, MediaTek MT8163/MT8227, UNISOC UIS7862/7870 or
Allwinner T3/T5, weak GPUs, 1024×600 or 1280×720 panels at 160-240 dpi, hardware decoders that
are often H.264-only, slow eMMC.

## Results

Release APK (`assembleRelease`, signed locally with the debug key for measuring only), on the
`carlink_lowend` emulator described below. Cold start is the median of 10 `am start -W -S` runs
(TotalTime; the "Displayed" log line matched it within 1 ms on every run). The "after" build is
measured twice: with Automatic visual effects (which picks **lite** on this 2 GB, 2-core device)
and with **Full** forced in Settings, to separate the lite-mode gain from the start-up changes.

| Measure | Before (4bca8130) | After, Automatic = lite | After, Full forced |
|---|---|---|---|
| Cold start to Home's first frame (median, 10 runs) | **1284 ms** (1260-1333) | **804 ms** (792-893) | 1272 ms (1263-1290) |
| Main process PSS, 10 s idle on Home | 61.9 MB | 64.9 MB | 64.1 MB |
| `:aa` process PSS (started by the prewarm) | 26.1 MB | 26.8 MB | 27.0 MB |
| Frames drawn while Home sits idle in Ready (10 s) | 0 | 0 | 0 |
| Open Settings and back ×3: frames / janky / p50 / p90 / p99 | 133 / 61.7 % / 16 / 20 / 97 ms | 133 / 61.7 % / 17 / 19 / 105 ms | 131 / 69.5 % / 18 / 21 / 117 ms |
| Settings scroll ×4 and three category switches | 349 / 7.2 % / 7 / 15 / 32 ms | 356 / 7.3 % / 5 / 15 / 30 ms | 348 / 19.0 % / 11 / 18 / 48 ms ¹ |
| Release APK, universal | 34.0 MB (arm64, armv7, x86_64) | 29.4 MB (arm64, armv7) | |
| Release APK, arm64-v8a part only ² | 28.7 MB | 27.4 MB | |
| Release APK, armeabi-v7a part only ² | 24.7 MB | 23.4 MB | |

¹ A second Full run of the same scroll gave 353 / 7.7 % / 7 / 15 / 32 ms: this row is within the
emulator's noise. ² The universal APK minus the other ABIs' native libraries (what an ABI split or
an app bundle would deliver).

What the numbers say, honestly:

- **Lite rendering is the start-up win.** On this emulator the app's own work up to Home's first
  `onDraw` takes about 190 ms from process start; the remaining ~1.1 s of the old cold start was
  the GPU rendering the first frame (the Atmosphere's four full-screen gradients, the grain
  bitmap shader, the dial's gradients). The emulator renders with SwiftShader, a CPU renderer, so
  fill cost is exaggerated here; a real Mali-T720/PowerVR GE8300 class GPU is also fill-limited
  and compiles each new shader on first use, so the gain should be in the same direction but
  smaller. Not yet measured on a real head unit.
- **The start-up changes alone are worth ~1 %** on this device (1284 → 1272 ms in Full). CarLink's
  own `Application.onCreate` was already ~10 ms. Their real value is removing a second process
  start and an engine round-trip from the first second (below).
- **Memory did not drop.** PSS is dominated by code; the new baseline profile compiles CarLink's
  own classes ahead of time (+1.4 MB of `Code` in PSS), which outweighs the 64 KB grain bitmap
  lite mode no longer allocates. GPU memory could not be read on this emulator (`Graphics: 0`).
- **Transitions jank the same in both modes.** Opening Settings is dominated by composing the new
  screen, not by drawing the background. Ready Home draws nothing while idle in either mode; the
  connecting animations (the only idle-looking motion) cannot be driven on the emulator without a
  phone, see "Lite rendering" for what changes there.

## Method

**Emulator** (`~/.android/avd/carlink_lowend.avd`): API 30 `default` (AOSP, no Play Store) x86_64
image, 1024×600 at 160 dpi, 2048 MB RAM, 2 cores, SwiftShader GPU, landscape, dark mode. It runs
headless on port 5562 (`emulator-5562`) only while measuring:

```sh
ANDROID_SDK_ROOT=$HOME/android-sdk ~/android-sdk/emulator/emulator -avd carlink_lowend \
  -sysdir ~/android-sdk/system-images/android-30/default/x86_64 -port 5562 \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -cores 2
```

(`-sysdir` is needed because the emulator binary resolves the SDK through its real path in
`/opt/android-sdk`, while the API 30 image is installed in the `~/android-sdk` overlay, where
`system-images/` is now a directory of per-API links.)

**Build**: `assembleRelease` with `ANDROID_KEYSTORE_*` pointing at `~/.android/debug.keystore`, lint
vital skipped, and for the emulator `-Pcarlink.emulatorAbi=true` (adds x86_64 to the release).

**Per APK**: uninstall, `install -g`, launch once, deliver the APK's baseline profile as
ProfileInstaller does (`am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE`), force
stop, `cmd package compile -m speed-profile -f` (status `speed-profile` in both runs), then:

1. 10 × (`force-stop`, 2 s, `am start -W -S`, read TotalTime and the `Displayed` line);
2. 10 s idle on Home, `dumpsys meminfo` for `com.carlink.app` and `com.carlink.app:aa`;
3. `dumpsys gfxinfo reset`, 10 s idle on Home, read frames;
4. `gfxinfo reset`, tap Settings and Back three times, read;
5. in Settings: `gfxinfo reset`, two down/up swipes of the content, tap Display, General,
   Connection, read.

The host was otherwise idle (no Gradle build) during every run. Baseline numbers come from the
same script on the APK built from 4bca8130.

## Lite rendering

`ui/theme/RenderingMode.kt`. Settings › General › **Visual effects**: Automatic (default), Full,
Lite. The row shows what Automatic picked ("Automatic · Lite").

**How Automatic decides** (`RenderingPolicy`, from `DeviceSignals` in
`connection/DeviceCapabilities.kt`): lite when any of these holds, otherwise full.

| Signal | Lite when | Why |
|---|---|---|
| `ActivityManager.isLowRamDevice` | true | Android Go / ≤1 GB builds |
| Total RAM (`MemoryInfo.totalMem`) | ≤ 3300 MB | "3 GB or less": a 3 GB unit reports ~2.7-2.9 GB, a 4 GB one ~3.6-3.8 GB |
| `Build.SUPPORTED_ABIS` | no 64-bit ABI | 32-bit-only SoCs and firmwares (Allwinner T3, MT8227) |
| CPU cores | ≤ 4 | quad A7/A53 units (MT8163, MT8227, T3); current SoCs have 8 |

Panel size and density are deliberately not signals: a small panel is cheap to fill. No
frame-time probe was added (it would cost frames at start-up to measure frames); the driver can
pick Lite by hand on a fast-RAM, slow-GPU unit (for example a 4 GB UIS7862, which Automatic
leaves on Full). Unknown values (0 RAM, 0 cores, no ABIs) are not signals.

**What lite changes** (same layout, sizes, colours and states):

| Element | Full | Lite |
|---|---|---|
| Atmosphere (every screen's background) | key light, bottom fill, state glow (radial gradients), floor vignette, 128 px grain bitmap shader | the theme's background colour |
| Link dial | bloom under lit arcs, radial-lit core disc, gradient rim | no bloom, core disc in its mid tone, solid rim |
| Glass cards, chips, sheets' cards, switcher panel and prompt | top-lit gradient hairline | solid hairline in the middle tone |
| Ion (primary) button | top sheen and gradient rim | solid Ion; pressed state kept |
| Connecting charge (dial) and active-step pulse | animated every vsync | the same motion in 8 (charge) / 3 (pulse) steps per cycle: ~5 redraws a second |
| Transitions (screen changes, sheets, day/night) | unchanged | unchanged |

The stepped animations use `derivedStateOf` over the running animation, so nothing is
invalidated between steps and no frame is drawn. "Remove animations" still stops them entirely.

Gallery: `docs/screenshots/*/*_headunit-1024x600_dark.png` (full) and
`*_headunit-1024x600-lite_{dark,light}.png` (lite), for Ready, the same-Wi-Fi offer, Connecting,
Connected, a failure, the Phones sheet, Settings › General and the Visual effects sheet.

## Start-up

- **The Android Auto engine process starts after the first frame.** `CarLinkApplication` used to
  call `Connection.prewarm` in `onCreate`, which forks `:aa` (DiAuto's whole `App.onCreate`)
  while Home draws its first frame, on a device that may have two cores. Worse, the session layer,
  created a few milliseconds later by Home's ViewModel, then found a live `:aa` process, asked it
  for a report and stopped an "orphaned" Android Auto session on every cold start (a broadcast
  round-trip and a Wi-Fi Direct group release). Now the prewarm runs 2.5 s after start
  (`deferStartupWork`) and Home's `onResume` prewarm 2.5 s after resume. A phone that connects in
  those 2.5 s starts `:aa` itself, as it always could; the orphan check still runs when an engine
  really outlived the main process.
- **The decoder probe for Automatic picture runs in the same deferred step**, on a low-priority
  thread, so the first session does not pay for it (5 ms on the emulator; longer on units with
  many codecs).
- **Baseline profile** (`app/src/main/baseline-prof.txt`): all of `com.carlink.app` is listed, so
  ART compiles CarLink's own code at install (with ProfileInstaller) instead of interpreting it on
  the first launches. Libraries (Compose, Material 3) ship their own profiles, merged by AGP.
- Unchanged and measured cheap: brand hooks, DiPlay hooks, USB watcher registration and the
  switcher install (~10 ms together).

## Picture rules

`connection/DeviceCapabilities.kt` reads the hardware decoders once (`MediaCodecList`, the first
hardware decoder per MIME type, as `createDecoderByType` would get), and
`connection/PicturePolicy.kt` turns that into Automatic picture for both engines. A size counts as
carried at a frame rate when the decoder supports the size and the rate and, on Android 10+, a
declared performance point covers it (DiAuto's `DecoderCapabilityReport.adequate`). Only
**Picture = Automatic** uses it; Best, Balanced, Light and Custom are unchanged. Automatic never
asks for more than before; it only steps down.

| Rule | Android Auto (DiAuto) | CarPlay (DiPlay) |
|---|---|---|
| Codec | `video-codec` = H.264 when there is no hardware HEVC decoder; otherwise untouched | `hevc_enabled` = false when there is no hardware HEVC decoder; otherwise untouched |
| Size | DiAuto's own panel class (800×480 → 480p, up to 1280×720 → 720p, larger → 1080p; portrait 720×1280 / 1080×1920), then down the ladder 1080p → 720p → 480p to the first the H.264 decoder carries at 30 fps. The panel class itself is written as `resolutionId` 0 (DiAuto's Automatic) | The panel at 100 %, 75 % or 50 % (`display_scale_percent`), the first the decoder carries at 30 fps |
| Frame rate | 60 when the decoder carries 60 at that size and the device is not low-end ([lite signals](#lite-rendering)); otherwise 30 | 30 (DiPlay's default; Automatic never asked for 60) |
| Software-only H.264 | at most 720p, 30 fps | at most 1280×720 pixels |
| Probe failed or no H.264 decoder | engine defaults (Automatic, 60 fps) | engine defaults (100 %, 30 fps) |

Examples:

| Device | Android Auto | CarPlay |
|---|---|---|
| Strong tablet (hardware HEVC, 1080p60 H.264, 8 GB) | Automatic, 60 fps, codec untouched (as before) | 100 %, 30 fps (as before) |
| 1024×600 head unit, 2 GB, decoder carries 1080p60 (the emulator) | 720p (Automatic), **30 fps**, **H.264** | 100 %, 30 fps, **HEVC off** |
| 1920×720 head unit whose decoder tops out at 720p30 | **720p**, **30 fps**, H.264 | **75 %** (1440×540), 30 fps |
| 1920×1080 unit with only a software decoder | **720p**, **30 fps** | **50 %** (960×540) |

Each session logs the decision to CarLink's event log ("Automatic picture: Android Auto 720p
(panel) 30 fps H.264; CarPlay 100% of 1024x600 30 fps H.264; decoder …; low-end: 1981 MB RAM, 2
CPU cores"), and logcat `CarLink-Capabilities` has the full probe. The only connection-layer
change is this hook: `Connection.writeSession` passes the choice to `CarPlaySettingsPolicy.map`
and `AaSessionSettings.values`.

## APK size

- **ABIs**: release builds ship arm64-v8a and armeabi-v7a only (car stereos are ARM; many are
  still 32-bit). x86_64 (3.3 MB of native code, mostly Conscrypt) stays in debug builds for the
  emulator and in release builds only with `-Pcarlink.emulatorAbi=true`.
- **Excluded Java resources**: Bouncy Castle's Picnic post-quantum tables (1.2 MB; neither engine
  uses Picnic and only Picnic's classes read them), its German certificate-path messages, and
  kotlinx-coroutines' `DebugProbesKt.bin`.
- **Checked and kept**: the four Inter font files (250 KB) are all used (Compose type scale and the
  engines' chrome); the screenshot gallery (`docs/screenshots`, ~1 GB) is not packaged (no asset
  or resource from `docs/` is in the APK); DiPlay's `navigation_test.pcm` (171 KB compressed) is
  used by DiPlay's own test button and left alone (vendor asset).
- **Not done**: R8 shrinking. The five dex files are 18.5 MB compressed, the bulk of the APK, but
  both engines reflect into their own, framework and BYD classes (the reason release builds have
  `optimization { enable = false }`); shrinking needs keep rules for both engines and a full
  CarPlay and Android Auto session test on a device. ABI splits / an app bundle would also cut
  each download to its ABI (the "part only" rows above).

## Not verified

- No real low-end head unit was available: everything above is from the emulator (SwiftShader
  GPU) and Robolectric renders. The decoder probe ran only against the emulator's goldfish
  decoders.
- Connecting/connected animations were not measured for frame rate (they need a phone).
- The deferred prewarm changes when `:aa` starts; plug-in and wireless start times on the tablet
  were not re-measured (`carlink-device` skill).
