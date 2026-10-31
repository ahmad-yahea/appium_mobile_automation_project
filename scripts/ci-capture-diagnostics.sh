#!/usr/bin/env bash
# CI-only: records what the emulator shows while the tests run, so a failing
# step can be matched to the screen the app was actually on.
#   target/diag-logcat.txt        - full logcat
#   target/diag-screen-NNN.png    - a screenshot every $INTERVAL seconds
#
# Started detached before `mvn test` (output fully redirected so the
# emulator-runner step doesn't wait on it); dies with the emulator.
#
#   ./scripts/ci-capture-diagnostics.sh [device-serial]

set -uo pipefail

SERIAL="${1:-emulator-5554}"
INTERVAL="${DIAG_INTERVAL:-5}"
MAX_SHOTS="${DIAG_MAX_SHOTS:-150}"
OUT="target"

mkdir -p "$OUT"
adb -s "$SERIAL" logcat -c || true
adb -s "$SERIAL" logcat -v time > "$OUT/diag-logcat.txt" 2>&1 &

for ((i = 1; i <= MAX_SHOTS; i++)); do
  adb -s "$SERIAL" exec-out screencap -p > "$OUT/diag-screen-$(printf '%03d' "$i").png" 2>/dev/null || break
  sleep "$INTERVAL"
done
