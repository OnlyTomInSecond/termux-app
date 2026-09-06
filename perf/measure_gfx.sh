#!/usr/bin/env bash
# =============================================================================
# perf/measure_gfx.sh - M-frame on-device frame metrics (T0.1 ready, T0.2 run)
#
# Collects gfxinfo frame stats for com.termux while a stress command runs in
# the foreground terminal, then appends aggregate rows to perf/results.csv.
#
# Prerequisites:
#   1. debug build installed and device connected (`adb devices`)
#   2. a stress command is running / will be started inside the foreground
#      Termux session (see perf/stress_cmds.sh)
#   3. perf/measure_gfx.sh <stress-id> [duration-seconds]
#
# Rows written per run:
#   M-frame:janky_pct    (%, janky frames / total frames)
#   M-frame:pct90_frame  (ms)
#   M-frame:pct95_frame  (ms)
#   M-frame:unserviceable (1) - when the app's main thread is so starved by
#      output (the P2 freeze symptom) that dumpsys gfxinfo cannot be serviced.
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

RESULTS_CSV="perf/results.csv"
mkdir -p perf
[ -f "$RESULTS_CSV" ] || echo "timestamp,git_sha,device,phase,task_id,metric,value,unit,notes" > "$RESULTS_CSV"

STRESS_ID="${1:?usage: measure_gfx.sh <stress-id> [duration-seconds]}"
DURATION="${2:-15}"

command -v adb >/dev/null || { echo "adb not found in PATH (run setup_android_env)"; exit 1; }
DEVICE="$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')-api$(adb shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"
GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
PKG="com.termux"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

append_row() { # metric value unit notes
  echo "$TS,$GIT_SHA,$DEVICE,T0.2,M-frame:$1,$2,$3,stress=$STRESS_ID frames=${TOTAL_FRAMES:-0} janky=${JANKY:-0} $4" >> "$RESULTS_CSV"
}

echo "==> device: $DEVICE  stress: $STRESS_ID  duration: ${DURATION}s"
adb shell "dumpsys gfxinfo $PKG reset" >/dev/null || { echo "gfxinfo reset failed - is app installed/running?"; exit 1; }

echo "==> collecting $DURATION seconds ..."
sleep "$DURATION"

STATS="$(adb shell "dumpsys gfxinfo $PKG" 2>/dev/null | tr -d '\r')"

# When the app's main thread is starved by output (the P2 freeze symptom) the
# system cannot even service the dump; record that as a first-class result.
if printf '%s\n' "$STATS" | grep -q "Failure while dumping the app"; then
  append_row "unserviceable" "1" "dump-failed" "dumpsys gfxinfo failed (app main-thread starved)"
  echo "==> !! app main-thread starved: dumpsys gfxinfo could not be serviced"
  echo "    recorded as unserviceable=1 (see perf/results.csv)"
  exit 0
fi

# Aggregate lines look like:
#   Total frames rendered: N / Janky frames: N (P%) / 90th percentile: Xms ...
PCT_90="$(printf '%s\n' "$STATS" | sed -n 's/.*90th percentile: \([0-9]*\)ms.*/\1/p' | head -1)"
PCT_95="$(printf '%s\n' "$STATS" | sed -n 's/.*95th percentile: \([0-9]*\)ms.*/\1/p' | head -1)"
JANKY="$(printf '%s\n' "$STATS" | sed -n 's/.*Janky frames: \([0-9]*\) (.*/\1/p' | head -1)"
TOTAL_FRAMES="$(printf '%s\n' "$STATS" | sed -n 's/Total frames rendered: \([0-9]*\).*/\1/p' | head -1)"

if [ -z "$PCT_90" ]; then
  echo "!! Could not parse gfxinfo. Raw sample:"; printf '%s\n' "$STATS" | grep -iE "percentile|janky|frames|Graphics info" | head -5
  exit 1
fi

JANKY_PCT="0"
if [ -n "$TOTAL_FRAMES" ] && [ "${TOTAL_FRAMES:-0}" -gt 0 ] && [ -n "${JANKY:-0}" ]; then
  JANKY_PCT=$(awk "BEGIN{printf \"%.1f\", 100*$JANKY/$TOTAL_FRAMES}")
fi

# With zero rendered frames the histogram percentiles are the 4950ms sentinel;
# replace with a neutral record instead of a misleading value.
if [ -z "${TOTAL_FRAMES:-}" ] || [ "$TOTAL_FRAMES" -eq 0 ] 2>/dev/null; then
  PCT_90="0"; PCT_95="0"; NO_FRAMES="no-frames-rendered"
fi

append_row "janky_pct"   "$JANKY_PCT" "%" "${NO_FRAMES:-}"
append_row "pct90_frame" "${PCT_90:-0}" "ms" "${NO_FRAMES:-}"
append_row "pct95_frame" "${PCT_95:-0}" "ms" "${NO_FRAMES:-}"

echo "==> done. janky=${JANKY_PCT}% 90th=${PCT_90}ms 95th=${PCT_95}ms (frames=${TOTAL_FRAMES:-0})"
tail -n 3 "$RESULTS_CSV"
