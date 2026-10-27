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
| GPU (`GL_RENDERER`, round 2) | a weak GPU (`GpuClass`) | PowerVR GE8322 in the 4 GB, 8-core UIS8581A; rendering only |
| Fastest core (`cpuinfo_max_freq`, round 2) | ≤ 1.7 GHz | all-little-core SoCs; rendering only |

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

Gallery: `docs/screenshots/*/*_headunit-1024x600_dark.webp` (full) and
`*_headunit-1024x600-lite_{dark,light}.webp` (lite), for Ready, the Same Wi-Fi password card, Connecting,
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
- **Baseline profile** (`app/src/main/baseline-prof.txt` lists all of `com.carlink.app`,
  `carlink-core/src/main/baseline-prof.txt` all of `com.carlink.core`), so ART compiles CarLink's
  own code at install (with ProfileInstaller) instead of interpreting it on the first launches.
  AGP merges the library's profile, and those of Compose and Material 3, into the APK.
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
| Size | DiAuto's own panel class (800×480 → 480p, up to 1280×720 → 720p, larger → 1080p; portrait 720×1280 / 1080×1920), then down the ladder 1080p → 720p → 480p to the first the H.264 decoder carries at 30 fps. The panel class itself is written as `resolutionId` 0 (DiAuto's Automatic) | The panel at 100 %, 75 % or 50 % (`display_scale_percent`), the first the decoder carries at 30 fps; on slow hardware (the [lite signals](#lite-rendering)) also at most 1600×900 pixels |
| Frame rate | 60 when the decoder carries 60 at that size and the device is not low-end ([lite signals](#lite-rendering)); otherwise 30 | 30 (DiPlay's default; Automatic never asked for 60) |
| Software-only H.264 | at most 720p, 30 fps | at most 1280×720 pixels |
| Video output | untouched (TextureView) | **Direct video output** (SurfaceView) on slow hardware (the lite signals, also when the probe failed); otherwise the driver's switch is left alone |
| Probe failed or no H.264 decoder | engine defaults (Automatic, 60 fps) | engine defaults (100 %, 30 fps) |

Examples:

| Device | Android Auto | CarPlay |
|---|---|---|
| Strong tablet (hardware HEVC, 1080p60 H.264, 8 GB) | Automatic, 60 fps, codec untouched (as before) | 100 %, 30 fps (as before) |
| 1024×600 head unit, 2 GB, decoder carries 1080p60 (the emulator) | 720p (Automatic), **30 fps**, **H.264** | 100 %, 30 fps, **HEVC off**, **direct video output** |
| Issue #4: 2 GB, 8× 1.6 GHz, Android 12, 1280×720, no hardware HEVC | 720p (Automatic), 30 fps, H.264 | 100 %, 30 fps, HEVC off, **direct video output** |
| The same unit with a 2000×1200 panel | 1080p (Automatic) or less, 30 fps, H.264 | **75 %** (1500×900), 30 fps, HEVC off, direct video output |
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
- **R8 (release builds, 2026-10-10, phase 1)**: shrink, optimize and shrink resources, without
  renaming (`-dontobfuscate`); rules in `app/src/main/keepRules/carlink.keep` and the engines'
  consumer files (`docs/VENDOR_CHANGES.md`). Measured with `assembleRelease` signed with the debug
  key, no CarPlay authentication files, arm ABIs only; `apkanalyzer` and `unzip -lv`:

  | Measure | Before (e05e8ce5, R8 off) | After (R8 phase 1) |
  |---|---|---|
  | Release APK | 29.63 MB (29,630,256 B) | **15.82 MB** (15,818,867 B), −47 % |
  | Dex, compressed in the APK | 18.76 MB in 5 files | **5.66 MB** in 2 files, −70 % |
  | Dex, uncompressed | 50.14 MB | 13.25 MB |
  | Classes defined | 39,194 | 10,696 |
  | Methods defined | 276,644 | 82,439 |
  | Method references (sum over the dex files) | 315,243 | 92,807 |
  | `assets/dexopt/baseline.prof` | 15,029 B | 7,458 B (rewritten by R8 for the code that is left) |

  The baseline profile still reaches the APK (`assets/dexopt/baseline.prof` and `.profm`). A
  startup profile now exists ([round 2](#round-2-2026-10-15)) and R8 lays out the dex by it. The biggest
  remaining code is DiAuto's (1.7 MB), full protobuf (1.2 MB, kept whole by DiAuto's rule), DiPlay
  (1.0 MB) and Compose. R8 reported no missing classes. Start-up time and memory with R8 are not
  measured yet, and the shrunk build is not yet verified on a device (below).
- **Class names stay true.** `-dontobfuscate` alone did not keep them: the first R8 build merged
  `ConnectionProfiles` and DiPlay's `LiveVideoMeter` into `androidx.lifecycle.ViewModelLazy` and
  moved composables' static methods into `kotlin.ExceptionsKt` (horizontal class merging), which
  would have put wrong class names in stack frames and in the diagnostics' `javaClass` names.
  `carlink.keep` keeps the identity (not the members) of the app's and engines' classes; it costs
  0.6 % of dex. Inlined frames still disappear from raw stack traces: keep
  `app/build/outputs/mapping/release/mapping.txt` with each release build and retrace with it.

## Not verified

- No real low-end head unit was available: everything above is from the emulator (SwiftShader
  GPU) and Robolectric renders. The decoder probe ran only against the emulator's goldfish
  decoders.
- Connecting/connected animations were not measured for frame rate (they need a phone).
- The deferred prewarm changes when `:aa` starts; plug-in and wireless start times on the tablet
  were not re-measured (`carlink-device` skill).
- The R8 release build (above) has passed the build, the unit tests and an audit of reflection,
  JNI and by-name loading, but has not run on a device yet. Install it with
  `dev.sh build-release` and `CARLINK_TARGET=tablet dev.sh install-release` and run the device
  matrix in `design/RESEARCH_2026-10_R8_PLATFORM.md` before calling it done.

## Round 2 (2026-10-15)

Target: the user's FYT stereo (Unisoc UIS8581A: 8× Cortex-A55 at 1.6 GHz, PowerVR GE8322 GPU,
4 GB RAM, Android 10, 1280×720) and the cheaper units above (1-2 GB, MT8163, 1024×600).

### What helps on weak devices now (research, primary sources, read 2026-10-15)

| Topic | Finding | Source (date) | For CarLink |
|---|---|---|---|
| Baseline profiles, sideloaded | Outside Play, a profile is not compiled at install: "users don't see the benefits until background dexopt runs, which is likely overnight". ProfileInstaller writes it ~5 s after the first frame (plus up to 1 s jitter) | [Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview) (updated 2026-10-07); [ProfileInstallerInitializer](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/profileinstaller/profileinstaller/src/main/java/androidx/profileinstaller/ProfileInstallerInitializer.java) | Declared `androidx.profileinstaller` 1.4.1 explicitly |
| Background dexopt on Android 10 | `BackgroundDexOptService` runs once a day and only while the device is **idle and charging** | [AOSP android10-release](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android10-release/services/core/java/com/android/server/pm/BackgroundDexOptService.java) | A stereo that powers off with the ignition may never compile the profile. One adb command after installing does it: `cmd package compile -f -m speed-profile com.carlink.app` ([manual measurement page](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure), updated 2026-10-01) |
| Startup profile (dex layout) | Needs R8; puts start-up classes first in `classes.dex`; "15-30 % faster than with Baseline Profiles alone" | [Dex layout optimizations](https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations) (updated 2026-06-16) | Works without dexopt (it is the APK's layout), so it is the part that reaches a stereo that never dexopts |
| Profile generation | BaselineProfileRule needs root below API 33; benchmark / baselineprofile plugin **1.5.0** (2026-09-09) supports AGP 9 | [Create baseline profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile) (2026-10-07); [benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark) | Generated on CarLink's API 30 AVD with `adb root` |
| Compose versions | BOM 2026.02.01 = Compose 1.10.4 (pausable composition in lazy prefetch on by default). 1.12 (Aug 2026) claims start-up TTID comparable to Views; needs AGP ≥ 9.1.1 | [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping); [Dec 2025](https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html), [Aug 2026](https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html) posts | Upgrade not done here (it moves every screen; separate change with a gallery re-render) |
| Strong skipping | On by default since Kotlin 2.0.20; unstable parameters are compared by identity | [Strong skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping) (2026-01-16) | Value classes from `:carlink-core` declared stable (`app/compose-stability.conf`) |
| Deferred reads, caching | Read fast-changing state in lambda modifiers / draw (`drawBehind`, `offset {}`); `drawWithCache` keeps brushes and shaders until size or read state changes; an alpha < 1 `graphicsLayer` renders off-screen | [Best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices) (2026-09-22); [Graphics modifiers](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers) (2026-10-12) | Dial charge and step pulse read in draw; Atmosphere cached |
| R8 | Package-wide `-keep … { *; }` "completely removes R8's benefits for that package"; optimized resource shrinking is AGP 9's default; Configuration Analyzer needs AGP 9.3 | [Keep rules post](https://android-developers.googleblog.com/2025/11/configure-and-troubleshoot-r8-keep-rules.html) (2025-11-18); [Analyzer](https://developer.android.com/topic/performance/app-optimization/r8-configuration-analyzer) (2026-09-08) | Protobuf keep left (below) |
| App start-up | Excessive cold start is ≥ 5 s; initialise lazily | [Launch time](https://developer.android.com/topic/performance/vitals/launch-time) (2026-09-21) | Round 1 already defers `:aa` and probes; App Startup library adds nothing here (one provider, ProfileInstaller's) |
| Memory | Android 14+ delivers only `TRIM_MEMORY_UI_HIDDEN` and `BACKGROUND`; 10-13 still deliver every level. A running service shrinks the cached-process pool | [Manage app memory](https://developer.android.com/topic/performance/memory/manage-app-memory) (2026-09-21) | `onTrimMemory` drops the decoder probe; `:aa` not prewarmed on a memory-tight unit without an Android phone |
| Video surface | TextureView is composited by the app's GPU ("every visible pixel is composited twice"); SurfaceView gets its own layer, often a hardware overlay; media3 recommends SurfaceView for lower power and accurate frame timing | [AOSP TextureView](https://source.android.com/docs/core/graphics/arch-tv) (2026-10-09); [media3 surfaces](https://developer.android.com/media/media3/ui/surface) (2026-03-30) | Both engines default to TextureView (below) |
| Thread priority | `THREAD_PRIORITY_AUDIO` = -16, `URGENT_AUDIO` = -19 (apps "can not normally" use it), `DISPLAY` = -4; AAudio's data callback already runs on a high-priority thread; `PerformanceHintManager` is API 31+ | AOSP `Process.java` (android10-release); [AAudio](https://developer.android.com/ndk/guides/audio/aaudio/aaudio) (2026-03-06) | Engines' own threads; nothing for CarLink to change (not on Android 10 stereos) |
| Device tier signals | Media performance class only exists from Android 12 (cheap stereos report 0); `largeHeap` "doesn't guarantee a fixed increase"; no official GPU-name tiering exists | [Performance class](https://developer.android.com/topic/performance/performance-class) (2026-09-21); [`<application>`](https://developer.android.com/guide/topics/manifest/application-element) (2026-08-21) | Our own heuristic: RAM, cores, ABI (round 1) + GL renderer and CPU clock (round 2) |

Not confirmed from a primary source: the RAM threshold behind `isLowRamDevice` (AndroidX calls it
"512 MB-class"), any official guidance on protobuf full-runtime keep rules, and a per-process
memory cost (measured below instead).

### Results

Release APKs built with `-Pcarlink.emulatorAbi=true`, signed with the debug key, measured with
`scripts/measure_lowend.sh` (round 1's method as a script: install, skip the setup, ProfileInstaller
broadcast, `cmd package compile -m speed-profile -f`, 10 cold starts, PSS after 10 s idle, gfxinfo
for idle Home, Settings and back ×3, Settings scrolling and categories, Settings › General ›
Diagnostics). Before = 9511a8af7; after = this round. The host was idle during every run.

Two AVDs (both API 30 `default` x86_64, SwiftShader GPU, landscape):
`carlink_lowend` (1024×600, 160 dpi, 2 GB, 2 cores; round 1's) and the new `carlink_uis8581`
(1280×720, 160 dpi, 4 GB, 8 cores (the guest reports 6), port 5564: the user's stereo's panel
and memory). Start it like `carlink_lowend` with `-avd carlink_uis8581 -port 5564 -memory 4096 -cores 8`.

**`carlink_uis8581`** (the user's stereo's class). Automatic visual effects picked **Full** before
(4 GB, 8 cores) and picks **Lite** now (the GPU probe reads "Google SwiftShader", a software
renderer; on the stereo it would read "PowerVR Rogue GE8322"):

| Measure | Before (Automatic = Full) | After (Automatic = Lite) | After, Full forced |
|---|---|---|---|
| Cold start to Home (median of 10) | **1210 ms** (1188-1237) | **772 ms** (765-801) | 1204 ms (1177-1255) |
| PSS main / `:aa` | 45.3 / 18.0 MB | 46.0 / 18.8 MB | 45.9 / 18.7 MB |
| Idle Home, 10 s | 0 frames | 0 frames | 0 frames |
| Settings and back ×3: frames / janky / p50 / p90 / p99 | 137 / 50.4 % / 16 / 21 / 73 ms | 134 / 8.2 % / 5 / 16 / 101 ms | 136 / 37.5 % / 16 / 19 / 73 ms |
| Settings scroll and categories | 180 / 26.7 % / 10 / 19 / 34 ms | 281 / 2.5 % / 5 / 15 / 24 ms | 250 / 18.4 % / 9 / 18 / 26 ms |
| Diagnostics, scroll, back | 47 / 17.0 % / 15 / 16 / 22 ms | 46 / 0 % / 5 / 8 / 11 ms | 47 / 14.9 % / 15 / 16 / 20 ms |

**`carlink_lowend`** (Automatic = Lite in both builds; no phone known):

| Measure | Before (two runs) | After |
|---|---|---|
| Cold start to Home (median of 10) | 767 ms / 762 ms | **742 ms** (733-758) |
| PSS main / `:aa` | 45.3 / 17.8 MB | 51.0 MB / **not running** |
| PSS, both processes | **63.0 MB** | **51.0 MB** (−12 MB) |
| Idle Home, 10 s | 0 frames | 0 frames |
| Settings and back ×3 | 134 / 20.9 % and 6.7 % / 10 and 5 / 17 and 16 / 105 and 101 ms | 135 / 5.9 % / 5 / 15 / 105 ms |
| Settings scroll and categories | 319 and 322 / 16.9 % and 4.4 % / 6 and 5 / 18 and 15 / 34 and 28 ms | 321 / 4.1 % / 5 / 15 / 30 ms |
| Diagnostics, scroll, back | 92 / 46.7 % / 10 / 19 / 93 ms | 92 / 38.0 % / 6 / 19 / 97 ms |

The main process's PSS rises because PSS divides shared pages (the APK's dex and compiled code)
between the processes that map them: with `:aa` gone, the main process carries all of them.
The total is what the device pays.

**Profiles (Macrobenchmark, `carlink_uis8581`, after build, TTID median of 10 cold starts):**

| | `CompilationMode.None` | `Partial(BaselineProfileMode.Require)` |
|---|---|---|
| Run 1 (Full: the GPU probe had not run yet on the fresh install) | 1395 ms | 1284 ms (−8 %) |
| Run 2 (Lite) | 899 ms | 860 ms (−4 %) |

The same APKs with no AOT code at all (`compile -m verify`, Full forced), to see the dex layout
alone (a stereo whose background dexopt never runs): before 1259 ms, after 1278 ms, no gain on the
emulator. The Settings frame benchmark was inconclusive: its frame count swung between 204 and 357
in both modes (the journey is not deterministic enough on this emulator).

**Recomposition** (`AnimationRecompositionTest`, Robolectric, one second of the connecting
animation): before, `LinkDial` ran 60 times (Full) or 6 times (Lite) and `ProgressLine` 3 times
(Lite); after, nothing recomposes in either mode; the charge and pulse only redraw their layer.

**APK**: 19.52 → 19.93 MB with x86_64 (+0.4 MB: the start-up dex is a third dex file, which
compresses a little worse); `baseline.prof` 7.7 → 14.2 KB. The shipping arm-only release is
16.54 MB. R8 reported no missing classes.

### What changed

- **Baseline and startup profiles** (`:baselineprofile`, `androidx.baselineprofile` 1.5.0):
  journeys for cold start to Home (the only one in the startup profile), and Settings with every
  category and Diagnostics. Generated on `carlink_uis8581` with `adb root`; committed in
  `app/src/release/generated/baselineProfiles/` (8,782 baseline rules, 8,146 startup rules: 1,021
  CarLink, 4,642 Compose, 177 DiPlay, 143 DiAuto). The hand-written wildcard profiles of `:app` and
  `:carlink-core` stay and are merged. R8 lays out a start-up `classes.dex` (2,409 classes). The
  journeys run without a phone, so the connecting states are not in the generated profile; they
  are covered by the wildcard profile of `com.carlink.app`. Regenerate with
  `ANDROID_SERIAL=emulator-5564 ./gradlew :app:generateReleaseBaselineProfile -Pcarlink.emulatorAbi=true`
  (signing variables as `dev.sh build-release` sets them); measure with
  `:baselineprofile:connectedBenchmarkReleaseAndroidTest`. `androidx.profileinstaller` 1.4.1 is
  declared explicitly, and the manifest has `<profileable android:shell="true"/>`.
- **Automatic visual effects** also picks lite for a weak GPU (`GL_RENDERER` of a 1×1 EGL context,
  probed once per firmware in the deferred start-up step, 4-12 ms on the emulator, cached in
  preferences; `GpuClass`: PowerVR GE8xxx/G6xxx/SGX, Mali-4xx/T6xx-T8xx/G31/G51, Adreno 3xx/50x,
  Vivante, software renderers) or a processor with no core above 1.7 GHz (`cpuinfo_max_freq`). The
  first start after an install uses the old signals; the GPU applies from the next start.
  `isLowRamDevice`, RAM, ABI and core count are unchanged; the memory class is recorded but is not a
  signal (it follows RAM). Rendering only: Automatic picture still uses the round-1 low-end signals,
  so the stereo keeps Android Auto at 60 fps when its decoder carries it (a picture change would
  need a device test). Tests: `LowEndSignalsTest`, `RenderingPolicyTest`.
- **Compose**: the dial's charge and the progress line's pulse are `State`s read in the draw phase
  (`loopingState`), so an animation frame redraws one layer instead of recomposing both
  composables; the Atmosphere builds its four brushes and the grain shader once per size
  (`drawWithCache`) and reads the state glow there. Pixel-identical in the gallery (Home in Full
  and Lite). `app/compose-stability.conf` declares `:carlink-core`'s immutable value classes
  (`LinkState`, `PairedPhone`, …) and read-only collections stable: `HomeState` and `HomeUi` are now
  stable (unstable classes in the app 30 → 22). `-Pcarlink.composeReports=true` writes the
  compiler's reports to `app/build/compose_compiler/`.
- **Memory**: `:aa` is not prewarmed on a memory-tight unit (low-RAM build or ≤ 3.3 GB) unless an
  Android phone is known (`EnginePrewarmPolicy`; logged once). The main process answers
  `onTrimMemory` (UI hidden and above, and the running-low levels Android 10-13 still send) by
  dropping the decoder probe.

### Projection video surface (round 2: report only; CarPlay changed in round 3)

Both engines draw the picture into a **TextureView** by default:

- DiPlay (`CarPlayVideoSurface.carPlayVideoSurfaceMode`) uses a `TextureView` unless the window is
  not hardware-accelerated, "Smooth video" (Settings › Lab, `smooth_video`) or "Direct video
  output" is on, or the decoder is an FFmpeg Codec2 one whose pictures a texture cannot take; then
  a `SurfaceView` (its own note: direct output "can save about a refresh").
- DiAuto `Settings.viewMode` resolves to TextureView except on the DiLink 5.1 (`ProjectionRendererPolicy`);
  `AapProjectionActivity` escalates to SurfaceView by itself after a display stall. CarLink offers
  it as Settings › Lab › "Video surface" (`view-mode`, default TextureView) and writes nothing per session.

A SurfaceView would save the GPU composition pass of every video frame (the AOSP docs), which
matters most on a PowerVR GE8322. It was not changed: both engines' authors kept TextureView as the
default (DiAuto switches to SurfaceView only on one verified car), the default is not a setting
CarLink writes per session, and the picture path cannot be tested without a phone. Next step on the stereo: compare Android Auto
with Lab › Video surface = SurfaceView and CarPlay with Smooth video on, against the defaults.

### What did not help, or was not done

- **The profiles on an already-compiled install**: Full forced, 1204 ms vs 1210 ms before. On the
  emulator the gain only shows against no AOT code (−4 to −8 % in Macrobenchmark), and the dex
  layout alone showed none (the emulator reads the APK from a fast host disk with a warm page
  cache; slow eMMC is where layout should matter). The stereo only gets the AOT part after a
  background dexopt, which on Android 10 needs idle and charging; run
  `adb shell cmd package compile -f -m speed-profile com.carlink.app` once after installing.
- **AOT code costs memory**: the same APK compiled with `verify` used 37 MB of main-process PSS
  against 46 MB with `speed-profile` (the compiled code). On a 1 GB unit that is a real trade-off;
  not changed (the start-up and first-frame gains were measured worth it in round 1).
- **Frame timing of Settings**: within the emulator's noise in Full (janky % moves 5-20 points
  between identical runs); the large drop on `carlink_uis8581` comes from Automatic now choosing
  lite, not from the profiles or the Compose changes.
- **R8 phase 2 (protobuf)**: DiAuto's `-keep class com.google.protobuf.** { *; }` keeps 1.21 MB of
  the 10.1 MB dex (12 %), all of it used only in `:aa` during an Android Auto session. The full
  runtime reflects on generated messages, and no test without a phone exercises the protocol, so
  the rule was left as is: an APK-size gain, not a start-up one (the main process never loads it).
- **Compose BOM upgrade** (1.10 → 1.12, start-up TTID improvements): not done; it changes every
  screen and needs a gallery re-render and review. Worth its own change.
- **Thread priorities, `largeHeap`, App Startup**: nothing to gain in CarLink's code (the audio and
  video threads belong to the engines; `largeHeap` is the engines' requirement and only raises a
  limit; App Startup would not remove work CarLink does).
- **The `:aa` process when a phone is known**: still prewarmed on every device (the plug-in time
  it saves was measured on the tablet in round 1); a stereo with an Android phone pays 18 MB.

### Not verified (round 2)

Everything above is from the emulator (SwiftShader, x86_64) and Robolectric. The GPU names in
`GpuClass` come from the SoCs' published specifications, not from reading them on each device;
the user's stereo should be checked with `adb logcat -s CarLink-Capabilities` ("GL renderer
'…'") and Settings › General › Visual effects ("Automatic · Lite"). The prewarm change alters when
`:aa` starts on 2-3 GB units without a known Android phone: the first Android Auto plug-in there
starts the process cold (round 1 measured 4.5 s for that on the tablet).

## Round 3 (2026-10-20): CarPlay lag on a 2 GB stereo (issue #4)

Report: a cheap Android 12 head unit (8 cores at 1.6 GHz, 2 GB), iPhone 12 Pro on iOS 27 over
USB, CarLink 1.0.4: "lag and lack of fluency", "with Carlinkit box + Autokit it works way better".
No log yet.

**What CarLink asked for on that unit (before this round).** `DeviceSignals.lowEnd` (1.8-1.9 GB
reported) → Automatic picture: CarPlay at 100 % of the panel, 30 fps (CarPlay was never asked for
60), HEVC off unless a hardware HEVC decoder exists. Resolution, frame rate and codec were already
the cheap ones. What it did not change was the video output: DiPlay drew the picture into a
**TextureView**, so every decoded frame was drawn a second time by the app's GPU before the
compositor showed it ([AOSP TextureView](https://source.android.com/docs/core/graphics/arch-tv)).

**What a dongle does differently.** A Carlinkit dongle runs the CarPlay protocol itself (USB
networking, the encrypted AirPlay stream, the audio) and hands Autokit a plain H.264 stream and
PCM audio, at a size and frame rate the head-unit app chooses: the open-source host for those
dongles defaults to 800×640 at 20 fps
([node-CarPlay `DEFAULT_CONFIG`](https://github.com/rhysmorgan134/node-CarPlay/blob/main/src/modules/DongleDriver.ts));
Autokit offers a frame-rate setting "depending on android radio capability", hardware or
software decoding (software "fixed 800 * 480 resolution") and a render-mode switch for stutter
([Carlinkit: Autokit options](https://carlinkitcarplay.com/blogs/for-models-with-android-head-unit/explanation-of-autokit-app-options-settings));
the dongle's own settings page suggests a lower frame rate for an obviously delayed screen and a
bit rate under 8 Mbps for freezing or lag
([Carlinkit: 192.168.50.2 options](https://autokitcarplay.com/blogs/u2w-plus/as-for-the-192-168-50-2-backend-what-are-those-option-settings-mean)).
CarLink does all of that on the stereo: DiPlay decrypts every video frame with ChaCha20-Poly1305
(`ScreenStream`/`ScreenCodec.decryptFrame`), decodes the audio and runs the USB network, on
little cores. That CPU cost is the protocol's and stays; the GPU pass and the picture size are
what CarLink can cut.

**Code findings.**

| Suspect | Finding | Changed |
|---|---|---|
| Video view | DiPlay `carPlayVideoSurfaceMode`: TextureView unless Smooth video, Direct video output, no hardware acceleration or an FFmpeg Codec2 decoder. **Smooth video** paces frames to the iPhone's timestamps plus a delay that starts at 40 + 3000/fps ms (140 ms at 30 fps, `smoothVideoDelayMillis`): steadier but *more* touch latency, so not for lag. **Direct video output** is a SurfaceView with frames "shown as soon as they are decoded"; DiPlay documents it as able to save "about one screen refresh", experimental, off by default, picture adjustments unavailable, "turn it off if the picture shows artifacts or freezes" (DiPlay `docs/INSTALL.md`, 0.2.16 notes). CarLink already offers it for the iOS 27 black screen | Automatic turns it on on slow hardware |
| CarLink's waiting view | `CarPlayWaitingContent` (Compose) stays attached in the host window; DiPlay sets its panel `GONE` while a screen stream is active (`connectionPanel`). In the Connected state Home's dial and progress line have no endless animation (`DialReading(progress = 1f)`, no `ProgressLine`) and the waiting view passes no `connectedSince`, so no one-second ticker: nothing runs behind the video | No |
| Home behind the projection | `HomeActivity` is stopped under the full-screen host, so its recomposer is paused | No |
| Floating switcher | A small `TYPE_APPLICATION_OVERLAY` window (translucent) over the projection, static after its fade to idle alpha; it draws no frames by itself but is one more layer for the compositor | No (Settings › General › Floating switcher can turn it off) |
| Audio | DiPlay's music clock trim (`MediaClockTrim`) changes the AudioTrack rate by at most 26 cents and only for music; the music buffer (300 ms wired by default) delays music, not touches or the picture. Neither explains a laggy picture | No |

**What changed** (`PicturePolicy`, `CarPlaySettingsPolicy`, `AdvancedSettings`):

- With **Picture = Automatic** and slow hardware (`DeviceSignals.liteRendering`: ≤ 3.3 GB RAM,
  low-RAM build, 32-bit only, ≤ 4 cores, weak GPU or no core above 1.7 GHz), each CarPlay session
  writes DiPlay's `direct_video_output` = true. On other devices nothing is written, so the
  iOS 27 "Turn on and reconnect" fix and the driver's own choice stay.
- On the same devices CarPlay's size is also held to 1600×900 pixels: 1024×600 to 1920×720 panels
  stay at 100 %, 1920×1080 and 2000×1200 panels go to 75 %.
- **Settings › Display › Direct video output** now counts as a Picture override like Resolution
  and Frame rate: changing it sets Picture to Custom, under which CarLink writes no picture
  values, so a driver who turns it off keeps it off.
- Not changed: CarPlay stays at 30 fps (Automatic never asked for 60); Android Auto is untouched
  (its TextureView/SurfaceView choice is DiAuto's, Lab › Video surface).
- Tests: `PicturePolicyTest.issue4TwoGigabyteStereoGetsTheCheapCarPlayPath` (the reporter's unit)
  and `aWeakGpuOrSlowCpuAloneTurnsOnDirectVideo`, plus the Automatic-only mapping.

**Not verified.** No measurement: the CarPlay video path needs an iPhone, and `carlink_lowend` has
none (the emulator cannot carry a USB CarPlay session), so neither the GPU saving nor Direct video
output's stability on cheap HWCs was measured. The user's FYT stereo (GE8322) and the reporter's
unit are the next checks: DiPlay's log line `Video output mode=SURFACE … directVideoOutput=true`
and CarLink's `Automatic picture: … direct video output` show the new path was taken.

## Round 4 (2026-10-27): the wired CarPlay data path on a weak SoC (issues #4, #5)

Reporter's unit (issue #5 report, CarLink 1.0.4): Unisoc UIS8581A (sprd s9863a1h10), 8 × 1.6 GHz,
2 GB, reports "Android 12 (SDK 29)", 1280×720 panel. Picture was already panel-native 1280×720
at 30 fps H.264 (round 3); audio on wired is already LPCM (`AirPlayInfoPlist.audioFormats`: only
the buffered music stream is AAC-LC); video decryption already uses Conscrypt's native
ChaCha20-Poly1305 (`AirPlayCrypto.platformChacha`; DiPlay logs `chacha=<provider>` on the first
frame). What CarLink does per packet that a dongle does not:

| Cost | Where | Changed |
|---|---|---|
| USB networking in Java: per Ethernet frame (~1 per 1.4 KB of video) a `copyOfRange`, a tun `write`; per TCP ack a tun `read`, 3 allocations and one bulk-OUT transfer | `NcmUsbBridge`, `Ntb16Codec.parse`, `Ipv6NcmBridge` | No (needs measuring; a zero-copy rewrite is not low-risk) |
| One bulk-IN request, re-queued only after every frame of the last completion reached the tun: while the reader works or is descheduled the phone cannot send | `NcmUsbBridge.readChunk` | Re-armed right after the copy |
| `ncm-ipv6-in`, `ncm-ipv6-out`, `ncm-bulk-out`, `airplay-screen` and the `carplay-video` worker at default priority, while the audio receive thread (`AUDIO`, upstream #737) and the decoder output thread (`DISPLAY`) were raised: on wired, audio too waits for the NCM threads | DiPlay threads | NCM threads `URGENT_DISPLAY`, video receive/worker `DISPLAY` |
| Per frame: a body array, a decrypted array (plus two direct-buffer copies on API 31+), a decode-queue job | `ScreenStream`, `AirPlayCrypto` | No (~2 MB/s at 30 fps; small next to the per-packet path) |
| Diagnostics: `CONNECTION_DIAGNOSTIC … wired io operation=READ result=TIMED_OUT` every 10 s in a healthy session, shown as a warning because its counters say `readTimeouts` (the iAP2 control read polls every 100 ms) | `ConnectionLog.diPlayLevel` | Not a problem unless `result=FAILED` (E) or `ENDED` (W) |

**Verify on a slow unit** (no adb yet): `WIRED_TIMING first-frame`, DiPlay's video stats lines
(decode latency, late frames, `recovery:`), `chacha=` on the first frame, and that a 10-minute
session's Diagnostics shows no `wired io` warnings. **On the tablet**: a USB CarPlay session
connects in ≈ 3.4 s as before and video/audio stay smooth for 10 minutes.
