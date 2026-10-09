# R8, platform changes and receiver projects (research, 2026-10-10)

> **Status: current.** Input for the release-shrinking task and later reliability work. Primary
> sources only; list at the end. Nothing here is verified on the tablet yet.

## Ranked recommendations

| # | Item | Gain | Effort | Risk | Evidence |
|---|---|---|---|---|---|
| 1 | Check Android 17 background-audio hardening | A session started from the background could play no sound | S (test) | High if hit | Rule proven; whether CarLink hits it is untested (tablet is Android 16) |
| 2 | R8 in release, in phases (below) | Startup, memory, APK size on low-end units | M–L | Medium | **Phase 1 built 2026-10-10** (APK 29.6 → 15.8 MB, dex 18.8 → 5.7 MB); device test pending |
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

**Phase 1 is built (2026-10-10, branch `carlink-r8`), not yet verified on a device.** Release:
`optimization { enable = true }` (R8 full mode, `proguard-android-optimize.txt`, resource
shrinking), `-dontobfuscate`. Release APK 29.63 → 15.82 MB, dex 18.76 → 5.66 MB compressed,
classes 39,194 → 10,696, methods 276,644 → 82,439 (docs/LOW_END_PERFORMANCE.md § APK size).
Test it with `dev.sh build-release` + `CARLINK_TARGET=tablet dev.sh install-release` (release
artifact, `com.carlink.app`, signed with the debug key when no release keystore is set).

AGP 9.3 facts confirmed: app rules in `src/main/keepRules/*.keep` (merged with AGP's
`proguard-android-optimize.txt`, which `keepRules.includeDefault` controls); libraries keep
`consumerProguardFiles` (DiAuto's file with `-keepattributes` was accepted); R8 writes the merged
configuration to `app/build/outputs/mapping/release/configuration.txt` and a keep-rule report to
`app/build/reports/r8/r8-config-analyzer-release.{pb,html}` (`:app:analyzeReleaseR8Config`). The
R8-specific `-nohorizontalclassmerging` is test-only (rejected as an unknown option).

Rules added (each found by the audit below or by diffing the dex before and after):

| Rule | Where | Why |
|---|---|---|
| `-dontobfuscate`; `-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*` | `app/src/main/keepRules/carlink.keep` | Readable logs and stack frames; nested-class simple names in diagnostics |
| `-keep,allowshrinking,allowobfuscation,allowaccessmodification class com.carlink.**` (and `com.shilapi.**`, `com.andrerinas.**`, `com.byd.spi.**`), no members | `carlink.keep` | `-dontobfuscate` does not stop horizontal class merging: the first build merged `ConnectionProfiles` and `LiveVideoMeter` into `androidx.lifecycle.ViewModelLazy` and moved composables' statics into `kotlin.ExceptionsKt`. Keeps class identity only (merging, class inlining; full mode also keeps `InnerClasses` only for matched classes); +0.6 % dex |
| `main(String[])` of `HotspotJoinRepairMain`, `BydCarPlayCallTool`, `BydClusterSongTool`, `Byd13CatalogProbeMain`, `LocalMfiProbe` | DiPlay `shared/ambient-consumer-rules.pro` | app_process entry points started by class name (`BydAmbientLightTool` was already kept upstream) |
| `LinuxI2cNativeException { <init>(int, String); }` | same | `linux_i2c_jni.c` `FindClass` + `GetMethodID` |
| `-keepclassmembers class com.android.dx.TypeId { public static *** get(...); }` | DiAuto `app/proguard-project.txt` | `HotspotManager` reflects on it (Android 7-10 tethering callback) |
| `SUExecutor$ShizukuImpl$PrivilegedService { <init>(...); }` | same | Shizuku's server instantiates the user service by name |
| `com.byd.spi.ipc.cursor.BinderCursor$BinderParcelable { CREATOR; binder; }` | same | Unmarshalled by name from the BYD provider's Bundle; the first build removed it |

No `-dontwarn` was needed: R8 reported no missing classes (slf4j comes in with jmdns as a real dependency).
The note's `onNativeYuv420Frame` rule was not added: the method is `@Keep` and DiAuto keeps
`FfmpegHevcDecoder { *; }`.

Audit (all modules, vendor included): `Class.forName` (52 sites) and `getMethod`/`getDeclared*`/
`getField` (≈300, half in DiAuto's generated protos) reach framework, hidden-API, BYD or Conscrypt
classes (never shrunk), DiAuto's protos (kept whole) or dexmaker (rule above). JNI: Java→native
methods keep their names by the default rule and all survived (only the unused
`UsbNative.nativeResetDevice` went); native→Java: the I2C exception (rule) and
`onNativeYuv420Frame` (`@Keep`). Manifest components (43), `androidx.startup` initializers, the
classes named in layouts and the navigation graph, `META-INF/services` (coroutines) and
`@JavascriptInterface` (`MapPickerFragment.JsBridge`, default rule) are all present in the shrunk
dex. Enums whose names are persisted (`UiPreferences`, `UnifiedSettings`) are read with
`enumValueOf<T>`, compiled to direct `valueOf` calls, and names are not renamed. No
`ServiceLoader`, Gson/serialization, Kotlin reflection or Bouncy Castle JCA provider use; the
cross-process intents (`AaProtocol`, `DeviceEnvironment`'s component names) name manifest
components only. AIDL: `IShizuku` (rule above), FYT `ModuleCallback` (unused, removed). Analyzer
scores (r8-analyzer skill): optimization 71.6 %, shrinking 72.9 %, obfuscation 0 % (by design).

Device matrix for phase 1 (release build on the tablet; after each step
`adb logcat -d | grep -E "FATAL|ClassNotFound|NoSuchMethod|NoSuchField|NoClassDefFound|AbstractMethodError|UnsatisfiedLink|ExceptionInInitializer"`
must be empty for `com.carlink.app`):

1. Launch; Home ready; `:aa` warm-up line; Settings › Show advanced, every category; change Visual
   effects and an Android Auto and a CarPlay setting, force-stop, reopen: values kept (persisted
   enum names, the `:aa` ContentProvider).
2. CarPlay over USB (MFi offline auth: Bouncy Castle Ed25519/X25519/ChaCha20; NCM; `WIRED_TIMING`
   to `first-frame`); in-session menu; a call or Siri (Speex echo canceller JNI); unplug.
3. CarPlay wireless (Wi-Fi Direct, RFCOMM, JmDNS, AirPlay crypto) to `first-frame`; switcher
   bubble; Disconnect.
4. Android Auto over USB (`:aa`: libusb JNI, Conscrypt via reflection, protobuf) to the first
   frame; exit menu; media keys (`adb shell input keyevent KEYCODE_MEDIA_NEXT`, the receivers
   enabled by component name).
5. Android Auto wireless (Wi-Fi Direct, WPP handshake) to the first frame.
6. Android Auto with Settings › Lab: Video codec H.265, Software video decoding on, Software
   decoder Built-in FFmpeg (FFmpeg JNI and its `onNativeYuv420Frame` callback).
7. Diagnostics screen and Share (FileProvider); About › licences.
8. Not testable on the tablet (Android 16, not BYD): the dexmaker tethering callback (Android
   7-10 only), BYD app_process tools and `BinderParcelable`, Shizuku; covered by the rules and
   the dex check only.

Phase 2 (left on purpose):
- Narrow DiAuto's broad keeps: `com.google.protobuf.**` (9.5 % of live items kept un-optimized),
  `…aap.protocol.proto.**` (8.5 %), `androidx.navigation.**` (3.7 %; navigation ships its own
  rules), `…openheadunit.main.**` (1.3 %). Protobuf full runtime reflects on generated classes,
  so narrowing it needs its own device run; the lite runtime is the larger, better change.
  Conscrypt's own `org.conscrypt.**` keep (2.1 %) stays.
- Obfuscation with `mapping.txt` kept per release; DiPlay's package-filtered diagnostics then
  need retrace (or the class-identity rules extended into `-keeppackagenames`).
- Revisit the class-identity rules if a stable R8 option to restrict merging appears.

Phase 3: baseline + startup profiles generated on the tablet (none exists yet, so R8 has no
startup profile to lay out the dex).

Original audit notes (2026-10-10, before phase 1):

State: release had `optimization { enable = false }`; dex ≈ 18.5 MB compressed. Only DiAuto ships
consumer rules (`proguard-project.txt`, from open-headunit).

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
