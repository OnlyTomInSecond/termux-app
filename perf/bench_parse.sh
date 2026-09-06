#!/usr/bin/env bash
# =============================================================================
# perf/bench_parse.sh - M-parse JVM parse-throughput benchmark (T0.1)
#
# Feeds deterministic corpora (plain/ansi/tui/cjk/mixed) through
# TerminalEmulator.append() and appends median bytes/s rows to perf/results.csv.
#
# Usage:
#   perf/bench_parse.sh                 # uses default SDK location
#   TERMUX_DEVICE_LABEL=pixel6 perf/bench_parse.sh   # custom device label
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# Android SDK is required by the Android Gradle plugin even for JVM unit tests.
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# ---------------------------------------------------------------------------
# Results file layout:
#   timestamp,git_sha,device,phase,task_id,metric,value,unit,notes
# ---------------------------------------------------------------------------
RESULTS_CSV="perf/results.csv"
mkdir -p perf
if [ ! -f "$RESULTS_CSV" ]; then
  echo "timestamp,git_sha,device,phase,task_id,metric,value,unit,notes" > "$RESULTS_CSV"
fi

GIT_SHA="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
HOST="$(hostname 2>/dev/null || uname -n 2>/dev/null || echo host)"
DEVICE_LABEL="${TERMUX_DEVICE_LABEL:-jvm-$HOST}"

echo "==> M-parse benchmark  (git=$GIT_SHA, device=$DEVICE_LABEL)"
echo "==> running: ./gradlew :terminal-emulator:testDebugUnitTest --tests ParseThroughputBench"

./gradlew :terminal-emulator:testDebugUnitTest \
  --tests "com.termux.terminal.perf.ParseThroughputBench" \
  -Ptermux.perf.parse=true \
  -Ptermux.perf.out="$REPO_ROOT/$RESULTS_CSV" \
  -Ptermux.perf.git="$GIT_SHA" \
  -Ptermux.perf.device="$DEVICE_LABEL" \
  --console=plain

echo "==> done. Latest rows in $RESULTS_CSV:"
tail -n 6 "$RESULTS_CSV"
