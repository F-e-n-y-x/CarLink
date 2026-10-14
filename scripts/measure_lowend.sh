#!/usr/bin/env bash
# Measures a CarLink release APK on one of CarLink's own low-end emulators
# (docs/LOW_END_PERFORMANCE.md, "Method"). Emulator only: refuses any device whose AVD name is not
# carlink_lowend or carlink_uis8581.
#
#   scripts/measure_lowend.sh <serial> <apk> [label] [compile]
#
#   serial   e.g. emulator-5562
#   apk      the release APK (built with -Pcarlink.emulatorAbi=true)
#   label    a name for the output (default: run)
#   CARLINK_RENDERING=Full|Lite  sets Settings › General › Visual effects before measuring
#            (default: Automatic, what the device resolves it to)
#   compile  speed-profile (default: the APK's baseline profile, as ProfileInstaller + background
#            dexopt leave it) or verify (no AOT code: a fresh sideload before any dexopt)
#
# Prints one line per measure and writes the raw dumps to $OUT (default build/lowend/<label>/).
set -euo pipefail

SERIAL="${1:?serial}"; APK="${2:?apk}"; LABEL="${3:-run}"; COMPILE="${4:-speed-profile}"
PKG="${CARLINK_MEASURE_PKG:-com.carlink.app}"
ADB="$HOME/android-sdk/platform-tools/adb"
OUT="${OUT:-build/lowend/$LABEL}"
mkdir -p "$OUT"
a() { "$ADB" -s "$SERIAL" "$@"; }

avd="$(a emu avd name 2>/dev/null | head -1 | tr -d '\r')"
case "$avd" in carlink_lowend|carlink_uis8581) ;; *) echo "refusing $SERIAL (AVD '$avd' is not a CarLink low-end AVD)" >&2; exit 2 ;; esac
echo "device $SERIAL avd=$avd $(a shell wm size | tail -1 | tr -d '\r') $(a shell wm density | tail -1 | tr -d '\r')"

# Taps the centre of the first node whose text or content description is exactly $1
# (find_text only checks that it is on screen).
find_text() { CARLINK_FIND_ONLY=1 tap_text "$1"; }
tap_text() {
  a shell uiautomator dump /sdcard/carlink-ui.xml >/dev/null 2>&1 || true
  local xy
  xy="$(a exec-out cat /sdcard/carlink-ui.xml | python3 -I -c '
import re, sys
want = sys.argv[1]
xml = sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    t = re.search(r" text=\"([^\"]*)\"", n); d = re.search(r" content-desc=\"([^\"]*)\"", n)
    if (t and t.group(1) == want) or (d and d.group(1) == want):
        b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
        x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
' "$1")"
  [ -n "$xy" ] || { [ -n "${CARLINK_FIND_ONLY:-}" ] || echo "no node '$1' on screen" >&2; return 1; }
  [ -n "${CARLINK_FIND_ONLY:-}" ] || a shell input tap $xy
}

frames() { # label: summary line from gfxinfo
  a shell dumpsys gfxinfo "$PKG" > "$OUT/gfx-$1.txt"
  python3 -I - "$OUT/gfx-$1.txt" "$1" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
def g(p):
    m = re.search(p, s); return m.group(1) if m else "?"
v = [g(r"Total frames rendered: (\d+)"), g(r"Janky frames: \d+ \(([\d.]+%)\)"),
     g(r"50th percentile: (\d+)ms"), g(r"90th percentile: (\d+)ms"), g(r"99th percentile: (\d+)ms")]
if v[0] == "0": v[2:] = ["-", "-", "-"]
print("%s: frames %s janky %s p50 %s p90 %s p99 %s" % tuple([sys.argv[2]] + v))
PY
}

a uninstall "$PKG" >/dev/null 2>&1 || true
a install -g "$APK" | tail -1
a shell am start -W -n "$PKG/com.carlink.app.ui.HomeActivity" >/dev/null
sleep 4
# First launch shows the setup over Home: skip it once, like a driver would.
tap_text "Skip setup" 2>/dev/null && sleep 2 || true
if [ -n "${CARLINK_RENDERING:-}" ]; then
  tap_text "Settings"; sleep 2; tap_text "General"; sleep 1.5; tap_text "Visual effects"; sleep 1.5
  tap_text "$CARLINK_RENDERING"; sleep 1; a shell input keyevent KEYCODE_BACK; sleep 1
  a shell input keyevent KEYCODE_BACK; sleep 1; a shell input keyevent KEYCODE_BACK; sleep 1
  echo "visual effects set to $CARLINK_RENDERING"
fi
# What ProfileInstaller does on a sideloaded install, then what background dexopt does.
a shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n "$PKG/androidx.profileinstaller.ProfileInstallReceiver" >/dev/null || true
sleep 2
a shell am force-stop "$PKG"
a shell cmd package compile -m "$COMPILE" -f "$PKG" | tr -d '\r' | tail -1
a shell dumpsys package dexopt | grep -A3 "\[$PKG\]" | grep -o "status=[a-z-]*" | head -1 || true

# 1. Cold start, 10 runs.
times=()
for i in $(seq 1 10); do
  a shell am force-stop "$PKG"; sleep 2
  t="$(a shell am start -W -S -n "$PKG/com.carlink.app.ui.HomeActivity" | tr -d '\r' | awk '/TotalTime/ {print $2}')"
  times+=("$t")
done
echo "${times[*]}" > "$OUT/coldstart.txt"
python3 -I -c 'import sys,statistics as s; v=sorted(map(int,sys.argv[1:])); print(f"cold start: median {s.median(v):.0f} ms (min {v[0]}, max {v[-1]}) runs {v}")' "${times[@]}"

# 2. Memory after 10 s idle on Home.
sleep 10
for p in "$PKG" "$PKG:aa"; do
  a shell dumpsys meminfo "$p" > "$OUT/meminfo-${p##*:}.txt" || true
  pss="$(grep -m1 'TOTAL PSS:' "$OUT/meminfo-${p##*:}.txt" | awk '{print $3}' || true)"
  [ -z "$pss" ] && pss="$(grep -m1 -E '^ +TOTAL ' "$OUT/meminfo-${p##*:}.txt" | awk '{print $2}' || true)"
  echo "PSS $p: ${pss:-not running} KB"
done

# 3. Idle frames.
a shell dumpsys gfxinfo "$PKG" reset >/dev/null; sleep 10; frames "idle-10s"

# 4. Settings and back, three times.
a shell dumpsys gfxinfo "$PKG" reset >/dev/null
for i in 1 2 3; do tap_text "Settings"; sleep 2; a shell input keyevent KEYCODE_BACK; sleep 2; done
frames "settings-back-x3"

# 5. Settings: two down/up swipes of the content, then three categories.
tap_text "Settings"; sleep 2
read -r W H < <(a shell wm size | tail -1 | tr -d '\r' | sed 's/.*: //; s/x/ /')
x=$((W * 2 / 3)); y1=$((H * 3 / 4)); y2=$((H / 4))
a shell dumpsys gfxinfo "$PKG" reset >/dev/null
for i in 1 2; do a shell input swipe $x $y1 $x $y2 250; sleep 1; a shell input swipe $x $y2 $x $y1 250; sleep 1; done
for c in Display General Connection; do tap_text "$c" || true; sleep 1.5; done
frames "settings-scroll"
a shell input keyevent KEYCODE_BACK; sleep 1

# 6. Settings › General › Diagnostics and back (Home shows Diagnostics only after a failure).
tap_text "Settings"; sleep 2; tap_text "General"; sleep 1.5
find_text "Diagnostics" || { a shell input swipe $x $y1 $x $y2 250; sleep 1; }
a shell dumpsys gfxinfo "$PKG" reset >/dev/null
tap_text "Diagnostics" || true; sleep 3
a shell input swipe $x $y1 $x $y2 250; sleep 1; a shell input swipe $x $y2 $x $y1 250; sleep 1
a shell input keyevent KEYCODE_BACK; sleep 2
frames "diagnostics-scroll-back"
a shell input keyevent KEYCODE_BACK; sleep 1
