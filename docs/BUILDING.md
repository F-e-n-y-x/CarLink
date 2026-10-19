# Building and testing CarLink

How to build the debug APK, run the tests, render the screenshot gallery and test on a real
device. Releases are covered separately in [RELEASING.md](RELEASING.md).

## Requirements

JDK 25 for the Gradle daemon, Android SDK platforms 36 and 37, build-tools 35 and 36,
NDK `29.0.14206865` (Android Auto engine) and `28.2.13676358` (CarPlay engine), CMake `3.22.1`.
Set `ANDROID_HOME` (and no conflicting `ANDROID_SDK_ROOT`) or put `sdk.dir=...` in an untracked
`local.properties`.

## Build and test

```sh
python3 scripts/resource_collisions.py --check
./gradlew :app:testDebugUnitTest :carlink-core:testDebugUnitTest :androidauto:testGithubDebugUnitTest \
          :carplay-shared:testDebugUnitTest :carplay-common:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK (`com.carlink.app.hudtest`) is in `app/build/outputs/apk/debug/`. It installs
alongside DiAuto and DiPlay. Run one projection app at a time.

The project uses **no GitHub Actions**: tests run locally, before every pull request and (through
`scripts/release.sh`) before every release. `dev.sh test` (below) runs the same checks as the
commands above.

Releases are built, signed and published from the maintainer's computer with
`scripts/release.sh`: [RELEASING.md](RELEASING.md). Versions and changes:
[CHANGELOG.md](../CHANGELOG.md).

## CarPlay authentication files (not in git)

Ordinary source builds contain **no CarPlay accessory identity**, exactly like DiPlay's. CarPlay
cannot connect without the two files DiPlay's releases use (`offline-mfi/identity.pk8` and
`offline-mfi/certificate.p7b`). They are never committed (`.gitignore` blocks `*.pk8` and
`*.p7b`). Provide them only for a local car-test build:

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/dir-containing-offline-mfi ./gradlew :app:assembleDebug
```

Read DiPlay's README about what this identity is and its limits before distributing such a
build. Android Auto does not need these files. Diagnostics shows whether they are present.

## Development

Work in this repository follows two project skills:

- [.claude/skills/carlink-dev](../.claude/skills/carlink-dev/SKILL.md): architecture rules, the
  build and test loop (`scripts/dev.sh build|test|install|shot|size|logs`), UI checks on the
  emulator, vendor-hook rules and commit rules.
- [.claude/skills/carlink-device](../.claude/skills/carlink-device/SKILL.md): testing connections
  on a real tablet or head unit and reading the merged CarLink / DiPlay / DiAuto logs
  (`scripts/report.py`). The emulator has no Bluetooth or real Wi-Fi, so a connection change is
  only done once it is verified on a device.

The usual loop, with the helper script:

```sh
S=.claude/skills/carlink-dev/scripts/dev.sh
$S build        # debug APK; includes ~/.carlink-private CarPlay files when present
$S test         # resource-collision check + all five unit-test suites (app, carlink-core, engines)
$S install      # install on the emulator and launch
$S shot home    # screenshot of the running app
$S build-release   # the shrunk (R8) release APK, com.carlink.app
```

Release builds run R8; debug builds do not. A change that adds reflection, JNI or a class loaded
by name needs a narrow keep rule (`app/src/main/keepRules/carlink.keep`, or the engine's consumer
rules listed in [VENDOR_CHANGES.md](VENDOR_CHANGES.md)). Details are in the `carlink-dev` skill.

## Screenshot gallery

The screenshot gallery renders every screen state without any device or emulator:

```sh
./gradlew --no-daemon :app:recordScreenshots   # rewrites docs/screenshots
```

See [screenshots/README.md](screenshots/README.md) for the configurations, the index and how to
add a state.

## Testing on a device

Connections (USB, Bluetooth, Wi-Fi Direct, CarPlay and Android Auto) cannot be tested on the
emulator. Use a real tablet or head unit and the `carlink-device` skill, which installs the
build, drives a connection and merges the three logs into one report. Each engine writes
always-on timing lines (`WIRED_TIMING`, `WIRELESS_TIMING`) to its log, so any device can be
measured.

## Engines

`vendor/diplay` and `vendor/diauto` were imported as git subtrees (both are now updated by
applying the upstream diff, not by `git subtree pull`; see VENDOR_CHANGES.md). Edit them only through small, marked hooks
and record every edit in [VENDOR_CHANGES.md](VENDOR_CHANGES.md); see
[CONTRIBUTING.md](../CONTRIBUTING.md#changing-the-engines).
