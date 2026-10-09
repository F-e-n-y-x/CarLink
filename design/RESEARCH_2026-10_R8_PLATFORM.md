# R8, platform changes and receiver projects (research, 2026-10-10)

> **Status: current.** Input for the release-shrinking task and later reliability work. Primary
> sources only; list at the end. Nothing here is verified on the tablet yet.

## Ranked recommendations

| # | Item | Gain | Effort | Risk | Evidence |
|---|---|---|---|---|---|
| 1 | Check Android 17 background-audio hardening | A session started from the background could play no sound | S (test) | High if hit | Rule proven; whether CarLink hits it is untested (tablet is Android 16) |
| 2 | R8 in release, in phases (below) | Startup, memory, APK size on low-end units | M–L | Medium | Proven technique; CarLink's numbers unmeasured |
| 3 | Baseline + startup profiles covering engine code; explicit `androidx.profileinstaller` | Faster first launch / first connection | M | Low | Proven; sideloaded APKs get it after background dexopt |
| 4 | Pull DiPlay 0.2.16 (we vendored 0.2.15) | **Done 2026-10-10.** Checked against the commits: video backlog recovery, Qualcomm low-latency decoding + FPS counter, smooth-video frame requests, call echo-cancel alignment, opt-in BYD Bluetooth pause, side panel and settings UI. It does *not* contain NCM/USB-retry/address-family changes (the first reading of the release notes was wrong) | S–M | Low–Med | Pulled; see docs/VENDOR_CHANGES.md |
| 5 | Handle `BluetoothDevice.ACTION_KEY_MISSING` (Android 16) → "forget and re-pair" | Stale-pairing failures explained | S | Low | API proven |
| 6 | CompanionDeviceManager association + device presence | Phone nearby could wake CarLink | M–L | Medium | Speculative for a tablet associating to a phone |
| 7 | Wi-Fi Direct R2 bootstrapping, Wi-Fi Aware, 6 GHz P2P | none | – | – | Neither AA nor CarPlay phones use them |

Android 17 audio hardening: apps targeting 37 need a foreground service with while-in-use
capability to take audio focus or play from the background; a service started from the background
does not get it. Risky paths: USB plug-in / auto-start while CarLink is not visible (DiAuto
`AapService`, DiPlay's session service). Test on an Android 17 device with
`adb shell cmd audio set-enable-hardening throw` and `dumpsys audio | grep AudioHardening`.

Already compliant: `ACCESS_LOCAL_NETWORK`; RFCOMM `read()` returning -1 on Android 17 (DiPlay
`BluetoothRfcommDuplexStream`, DiAuto HFP loop); `OnBackPressedCallback`.

## R8 plan

State: release has `optimization { enable = false }`; dex ≈ 18.5 MB compressed. Only DiAuto ships
consumer rules (`proguard-project.txt`, from open-headunit).

AGP 9.3+: `optimization { enable = true }` (code + resources, optimize defaults); app rules in
`src/<variant>/keepRules/*.keep`; full mode is default; libraries use `consumerProguardFiles`
without global options; `optimization.keepRules { ignoreFrom(...) }` filters a dependency's rules;
`-printconfiguration` and `-whyareyoukeeping` for debugging.

What needs rules (from the code):
- JNI Java→native: 6 classes with `external` methods; default rules keep them.
- JNI native→Java: FFmpeg `onNativeYuv420Frame` (covered by DiAuto's `FfmpegHevcDecoder` keep);
  DiPlay I2C `FindClass` of `LinuxI2cNativeException.<init>(int,String)` (not covered).
- Reflection: ~120 sites, nearly all into framework/hidden/BYD classes (not renamed). Exceptions:
  DiAuto `HotspotManager` → dexmaker `TypeId.get(...)`; Conscrypt (ships its own rules).
- DiAuto uses full `protobuf-java` 3.25.1 with generated `GeneratedMessageV3` classes: keep the
  generated package (lite runtime is a later, larger change).
- Bouncy Castle (lightweight API only) is a large shrink target; jmdns needs `-dontwarn`.
- DiPlay's `P2pConfigBuildDiagnostics` / `MediaFailureSummary` filter stack frames by package
  name: obfuscation would degrade diagnostics.

Phases:
1. R8 on with `-dontobfuscate` (shrink + optimize, readable logs). Device matrix: CarPlay USB +
   wireless, AA USB + wireless, engine settings screens, hotspot/dexmaker path, HEVC FFmpeg
   decoder, Speex echo cancelling, switcher, Diagnostics share.
2. Narrow DiAuto's broad keeps (`com.google.protobuf.**`, `androidx.navigation.**`, `main.**`),
   then obfuscation with `mapping.txt` kept per release.
3. Baseline/startup profiles generated on the tablet.

Starting rules:

```
# app/src/main/keepRules/carlink.keep
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-printconfiguration build/outputs/logs/r8-config.txt

# vendor/diplay/shared consumer rules
-keep class com.shilapi.xcertplay.transport.LinuxI2cNativeException { <init>(int, java.lang.String); }
-dontwarn javax.jmdns.**
-dontwarn org.slf4j.**

# vendor/diauto/app/proguard-project.txt additions (move its -keepattributes to the app)
-keepclassmembers class com.android.dx.TypeId { public static *** get(...); }
-keepclassmembers class * { boolean onNativeYuv420Frame(int,int,java.nio.ByteBuffer,int,java.nio.ByteBuffer,int,java.nio.ByteBuffer,int); }
```

open-headunit ships release builds minified with the same rules (with `proguard-android.txt`,
i.e. without optimization). No published rules exist for DiPlay or other CarPlay receivers.

## Receiver projects

- open-headunit 3.5.0-beta4 (2026-10-08): rejoins a still-existing Wi-Fi Direct group instead of
  rebuilding it; confirm DiAuto parity (seconds per reconnect, unmeasured).
- aa-proxy-rs: detects a user-chosen disconnect so it does not auto-reconnect; worth copying.
- WirelessAndroidAutoDongle v0.5.0: 5 GHz to avoid Bluetooth interference, Bluetooth connect retry,
  phone-first/dongle-first strategy; claims < 30 s (CarLink ≈ 5 s).
- pycarplay / node-CarPlay / FastCarPlay drive Carlinkit dongles: nothing to reuse.
- No primary source found for a faster wireless CarPlay technique without the HFP role.
  CarPlay Ultra (2025) changes nothing for iAP2/AirPlay receivers. No new AA wireless protocol.

## Claude Code skills found (none installed)

- android/skills (Google, Apache-2.0): `performance/r8-analyzer`, `build-system/agp/agp-9-upgrade`,
  `profilers/android-profiler`, `testing/testing-setup`. Trustworthy.
- chrisbanes/skills (Apache-2.0): `compose-performance`, `kotlin-concurrency-and-flow`,
  `android-benchmark-comparison`. Trustworthy.
- skydoves/compose-performance-skills (Apache-2.0): `configuring-r8-for-compose`,
  `generating-baseline-profiles`. Trustworthy.
- anthropics/claude-plugins-official: `kotlin-lsp`, `clangd-lsp`, `code-review`.

## Sources

- AGP release notes: https://developer.android.com/build/releases/gradle-plugin
- Enable app optimization with R8: https://developer.android.com/build/shrink-code
- R8 keep rules (2025-11-18): https://developer.android.com/blog/posts/configure-and-troubleshoot-r8-keep-rules
- Library optimization: https://developer.android.com/topic/performance/app-optimization/library-optimization
- Startup profiles: https://developer.android.com/topic/performance/baselineprofiles/dex-layout-optimizations
- Baseline Profiles: https://developer.android.com/topic/performance/baselineprofiles/overview
- Protobuf lite: https://github.com/protocolbuffers/protobuf/blob/main/java/lite.md
- Android 17 behavior changes: https://developer.android.com/about/versions/17/behavior-changes-17
- Android 17 background audio: https://developer.android.com/about/versions/17/changes/bg-audio
- Android 16 behavior changes: https://developer.android.com/about/versions/16/behavior-changes-16
- Local network permission: https://developer.android.com/privacy/local-network-permission
- Companion device pairing: https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing
- open-headunit: https://github.com/andreknieriem/open-headunit (rules, releases)
- DiPlay v0.2.16: https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.16
- DiAuto releases: https://github.com/shihabal3amri/DiAuto/releases
- aa-proxy-rs: https://github.com/aa-proxy/aa-proxy-rs
- WirelessAndroidAutoDongle: https://github.com/nisargjhaveri/WirelessAndroidAutoDongle
- CarPlay Ultra: https://www.apple.com/newsroom/2025/05/carplay-ultra-the-next-generation-of-carplay-begins-rolling-out-today/
- Skills: https://github.com/android/skills, https://github.com/chrisbanes/skills,
  https://github.com/skydoves/compose-performance-skills, https://github.com/anthropics/claude-plugins-official
