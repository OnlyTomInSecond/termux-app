#!/bin/sh
# =============================================================================
# perf/stress_cmds.sh - reproducible stress commands to run INSIDE Termux while
# measuring with perf/measure_gfx.sh
#
# Usage inside a Termux session:
#   sh /sdcard/path-or-anywhere/stress_cmds.sh <id>
# available ids: scroll | ansi | yes | find
# =============================================================================

case "${1:-}" in
  scroll)
    # Continuous scrolling text (tests scroll + transcript throughput).
    i=0
    while :; do printf '%07d  the quick brown fox jumps over the lazy dog 0123456789\r\n' "$i"; i=$((i + 1)); done
    ;;
  ansi)
    # Colored log storm (tests SGR parsing + many style runs).
    while :; do
      printf '\033[1;32m%s\033[0m \033[31mERROR\033[0m processing chunk retry=%d payload=%s\n' \
        "$(date +%H:%M:%S.%3N)" "$((RANDOM % 3))" "$(head -c 120 /dev/urandom | base64 | tr -d '\n')"
    done
    ;;
  yes)
    # Max throughput single-char flood.
    while :; do echo "y"; done
    ;;
  find)
    # Transcript-heavy workload (large directory listing).
    while :; do find /data /system 2>/dev/null | head -n 20000; done
    ;;
  *)
    echo "usage: $0 <scroll|ansi|yes|find>"
    exit 1
    ;;
esac
