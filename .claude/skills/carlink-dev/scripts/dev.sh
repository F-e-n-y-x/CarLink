#!/usr/bin/env bash
# CarLink dev loop: build, test, install and screenshot on the EMULATOR only.
#
#   dev.sh build            debug APK with CarPlay authentication (local files, never committed)
#   dev.sh test             all unit-test suites (app, carlink-core, both engines) + resource collision check
#   dev.sh install          install the debug APK on the emulator and launch CarLink
#   dev.sh shot <name>      screenshot the emulator to /tmp/carlink-shots/<name>.png
#   dev.sh size <WxH> [dpi] resize the emulator screen (e.g. 1080x2400 420 for a phone; "reset")
#   dev.sh logs             CarLink/engine log lines since the last install
#
# The emulator is the default target. The user's tablet (192.168.10.103:5555) may be used for live
# connection testing (permission given 2026-10-09): pass CARLINK_TARGET=tablet explicitly.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../../.." && pwd)"
SERIAL="${CARLINK_EMULATOR:-emulator-5560}"
[ "${CARLINK_TARGET:-}" = tablet ] && SERIAL="192.168.10.103:5555"
ADB="$HOME/android-sdk/platform-tools/adb"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.carlink.app.hudtest"
SHOTS="/tmp/carlink-shots"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"  # Gradle refuses to configure when the two disagree (the shell sets /opt/android-sdk).

case "$SERIAL" in emulator-*|192.168.10.103:5555) ;; *) echo "refusing unknown target $SERIAL" >&2; exit 2 ;; esac
adb_e() { "$ADB" -s "$SERIAL" "$@"; }

ensure_emulator() {
  if ! adb_e get-state >/dev/null 2>&1; then
    echo "emulator $SERIAL is not running; start it with:" >&2
    echo "  ~/android-sdk/emulator/emulator -avd carlink_headunit -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -port ${SERIAL#emulator-} &" >&2
    exit 3
  fi
}

cmd="${1:-}"; shift || true
case "$cmd" in
  build)
    cd "$ROOT"
    auth=()
    [ -f "$HOME/.carlink-private/offline-mfi/identity.pk8" ] && auth=(env DIPLAY_AUTH_ASSETS_DIR="$HOME/.carlink-private")
    "${auth[@]}" ./gradlew --no-daemon -q :app:assembleDebug
    echo "built $APK"
    ;;
  test)
    cd "$ROOT"
    python3 -I scripts/resource_collisions.py --check
    ./gradlew --no-daemon -q --continue :app:testDebugUnitTest :carlink-core:testDebugUnitTest :androidauto:testGithubDebugUnitTest \
      :carplay-shared:testDebugUnitTest :carplay-common:testDebugUnitTest
    echo "all tests passed"
    ;;
  install)
    ensure_emulator
    adb_e install -r "$APK" | tail -1
    adb_e logcat -c
    adb_e shell am start -n "$PKG/com.carlink.app.ui.HomeActivity" >/dev/null
    echo "launched on $SERIAL"
    ;;
  shot)
    ensure_emulator
    mkdir -p "$SHOTS"
    sleep "${CARLINK_SHOT_DELAY:-1}"
    adb_e exec-out screencap -p > "$SHOTS/${1:-shot}.png"
    echo "$SHOTS/${1:-shot}.png"
    ;;
  size)
    ensure_emulator
    if [ "${1:-}" = reset ]; then adb_e shell wm size reset; adb_e shell wm density reset
    else adb_e shell wm size "$1"; [ -n "${2:-}" ] && adb_e shell wm density "$2"; fi
    ;;
  logs)
    ensure_emulator
    adb_e logcat -d -v time | grep -iE "CarLink|xcertplay|DiPlay|openheadunit|AndroidRuntime|FATAL" | tail -"${1:-80}"
    ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
