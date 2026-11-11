#!/usr/bin/env bash
# Low-end benchmark: boots the carlink_lowend emulator with little RAM, installs an APK and appends
# one dated line to docs/lowend-bench.csv: cold start (median of `am start -W -S`), PSS of the
# main and :aa processes after 10 s on Home, and low-memory kills seen meanwhile.
# Method and earlier numbers: docs/LOW_END_PERFORMANCE.md ("Benchmark script").
#
#   scripts/lowend-bench.sh APK                 1024 MB, 10 cold starts, emulator-5562
#   MEM_MB=768 RUNS=5 scripts/lowend-bench.sh APK
#   scripts/lowend-bench.sh --self-test         checks the output parsers, no emulator
#
# Env: MEM_MB (1024), RUNS (10), PORT (5562), AVD (carlink_lowend), SDK ($HOME/android-sdk),
#      CSV (docs/lowend-bench.csv), FORCE=1 to start although the host looks short of memory.
set -euo pipefail

die() { echo "lowend-bench: ERROR: $*" >&2; exit 1; }

# ---- Parsers (also run by --self-test) --------------------------------------------------------
# TotalTime from `am start -W` output.
total_time() { sed -n 's/^TotalTime: *\([0-9]*\).*/\1/p' | head -1; }
# Total PSS in KB from `dumpsys meminfo <process>` ("TOTAL PSS:" summary, else the TOTAL row).
total_pss_kb() {
  local text; text="$(cat)"
  local v; v="$(printf '%s\n' "$text" | sed -n 's/.*TOTAL PSS: *\([0-9,]*\).*/\1/p' | head -1 | tr -d ,)"
  [ -n "$v" ] || v="$(printf '%s\n' "$text" | awk '$1 == "TOTAL" && $2 ~ /^[0-9]+$/ { print $2; exit }')"
  echo "${v:-0}"
}
# Median of numbers on stdin, one per line.
median() { sort -n | awk '{ a[NR] = $1 } END { if (NR == 0) print 0; else if (NR % 2) print a[(NR + 1) / 2]; else print int((a[NR / 2] + a[NR / 2 + 1]) / 2) }'; }
# Low-memory kills in a logcat dump: lmkd's lines and the events buffer's killinfo.
lmk_kills() { grep -cE 'lowmemorykiller: Kill|lmkd *: Kill|killinfo: \[' || true; }

if [ "${1:-}" = "--self-test" ]; then
  [ "$(printf 'Status: ok\nTotalTime: 742\nWaitTime: 750\n' | total_time)" = 742 ] || die "total_time"
  [ "$(printf ' App Summary\n           TOTAL PSS:    51,012       TOTAL RSS:  120,000\n' | total_pss_kb)" = 51012 ] || die "pss summary"
  [ "$(printf '        TOTAL    45320    40000\n' | total_pss_kb)" = 45320 ] || die "pss row"
  [ "$(printf 'No process found for: x\n' | total_pss_kb)" = 0 ] || die "pss missing"
  [ "$(printf '5\n1\n3\n' | median)" = 3 ] || die "median odd"
  [ "$(printf '4\n1\n3\n2\n' | median)" = 2 ] || die "median even"
  [ "$(printf 'lowmemorykiller: Kill '"'"'com.x'"'"' (1)\nI killinfo: [1,2]\nother\n' | lmk_kills)" = 2 ] || die "lmk"
  echo "lowend-bench: self-test ok"; exit 0
fi

APK="${1:-}"
[ -n "$APK" ] && [ -f "$APK" ] || die "usage: scripts/lowend-bench.sh APK (or --self-test)"
MEM_MB="${MEM_MB:-1024}"; RUNS="${RUNS:-10}"; PORT="${PORT:-5562}"; AVD="${AVD:-carlink_lowend}"
SDK="${SDK:-$HOME/android-sdk}"; ROOT="$(cd "$(dirname "$0")/.." && pwd)"; CSV="${CSV:-$ROOT/docs/lowend-bench.csv}"
SERIAL="emulator-$PORT"
ADB="$SDK/platform-tools/adb"; EMU="$SDK/emulator/emulator"
AAPT2="$(ls -d "$SDK"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -x "$ADB" ] && [ -x "$EMU" ] && [ -n "$AAPT2" ] || die "adb, emulator or aapt2 not found under $SDK"
[ -d "$HOME/.android/avd/$AVD.avd" ] || die "no AVD $AVD (docs/LOW_END_PERFORMANCE.md, Method)"
"$ADB" devices | grep -q "^$SERIAL" && die "$SERIAL is already running; stop it or set PORT"

# The emulator takes MEM_MB plus ~1.5 GB of its own; a host that swaps would time everything wrong.
avail_mb=$(awk '/^MemAvailable:/ { print int($2 / 1024) }' /proc/meminfo)
need_mb=$((MEM_MB + 1536))
if [ "$avail_mb" -lt "$need_mb" ] && [ "${FORCE:-0}" != 1 ]; then
  die "host has ${avail_mb} MB available, needs about ${need_mb} MB (FORCE=1 to try anyway; stop Gradle first)"
fi

PKG="$("$AAPT2" dump packagename "$APK" 2>/dev/null)" || die "$APK is not an APK"
echo "lowend-bench: $PKG from $APK, ${MEM_MB} MB, $RUNS runs, $SERIAL"

# Below 2 GB -memory alone did not set the guest RAM of this AVD; -qemu -m does
# (it must be the last option).
QEMU=(); [ "$MEM_MB" -lt 2048 ] && QEMU=(-qemu -m "$MEM_MB")
ANDROID_SDK_ROOT="$SDK" "$EMU" -avd "$AVD" -sysdir "$SDK/system-images/android-30/default/x86_64" -port "$PORT" \
  -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory "$MEM_MB" -cores 2 "${QEMU[@]}" \
  > "${TMPDIR:-/tmp}/lowend-bench-emulator.log" 2>&1 &
EMU_PID=$!
trap '"$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || kill "$EMU_PID" 2>/dev/null || true' EXIT

A() { "$ADB" -s "$SERIAL" "$@"; }
A wait-for-device
for _ in $(seq 1 150); do
  [ "$(A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
  kill -0 "$EMU_PID" 2>/dev/null || die "emulator exited (see ${TMPDIR:-/tmp}/lowend-bench-emulator.log)"
  sleep 2
done
[ "$(A shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || die "emulator did not boot in 300 s"
guest_mb=$(A shell cat /proc/meminfo | awk '/^MemTotal:/ { print int($2 / 1024) }')
echo "lowend-bench: booted, guest MemTotal ${guest_mb} MB"
sleep 20 # let boot-time dexopt and services settle

A uninstall "$PKG" >/dev/null 2>&1 || true
A install -g "$APK" >/dev/null || die "install failed"
ACTIVITY="$(A shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tr -d '\r' | tail -1)"
[[ "$ACTIVITY" == */* ]] || die "no launcher activity for $PKG"
# First launch, then the baseline profile as ProfileInstaller would deliver it, compiled.
A shell am start -W -n "$ACTIVITY" >/dev/null; sleep 5
A shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE "$PKG" >/dev/null 2>&1 || true
A shell am force-stop "$PKG"
A shell cmd package compile -m speed-profile -f "$PKG" >/dev/null

A logcat -c
times=()
for i in $(seq 1 "$RUNS"); do
  A shell am force-stop "$PKG"; sleep 2
  t="$(A shell am start -W -S -n "$ACTIVITY" | tr -d '\r' | total_time)"
  echo "  run $i: ${t:-?} ms"; [ -n "$t" ] && times+=("$t")
done
[ "${#times[@]}" -gt 0 ] || die "no TotalTime from am start -W"
sleep 10
pss_main=$(A shell dumpsys meminfo "$PKG" | tr -d '\r' | total_pss_kb)
pss_aa=$(A shell dumpsys meminfo "$PKG:aa" | tr -d '\r' | total_pss_kb)
kills=$(A logcat -d -b main,system,events | tr -d '\r' | lmk_kills)
cold=$(printf '%s\n' "${times[@]}" | median)
min=$(printf '%s\n' "${times[@]}" | sort -n | head -1); max=$(printf '%s\n' "${times[@]}" | sort -n | tail -1)

[ -f "$CSV" ] || echo "date,commit,apk,apk_sha256,avd,mem_mb,guest_mb,runs,cold_median_ms,cold_min_ms,cold_max_ms,pss_main_kb,pss_aa_kb,lmk_kills" > "$CSV"
line="$(date -u +%Y-%m-%dT%H:%MZ),$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo -),$(basename "$APK"),$(sha256sum "$APK" | cut -c1-12),$AVD,$MEM_MB,$guest_mb,${#times[@]},$cold,$min,$max,$pss_main,$pss_aa,$kills"
echo "$line" >> "$CSV"
echo "lowend-bench: $line"
echo "lowend-bench: appended to $CSV"
