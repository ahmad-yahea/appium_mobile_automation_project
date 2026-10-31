#!/usr/bin/env bash
# Boots one or more AVDs on consecutive console ports (5554, 5556, ...) for a
# parallel test run, waits for each to finish booting, then applies the English
# UI / Egypt-region locale + SIM config to every one via
# scripts/setup-emulator-egypt.sh.
#
# Usage: scripts/start-emulators.sh <avd-name> [<avd-name> ...]
#   e.g. scripts/start-emulators.sh Meh Meh2
#
# The emulators are left running in the background. Then run the suite with:
#   ./scripts/run-tests.sh -p
#   mvn test -Dtest.forkCount=2 -Dandroid.devices=emulator-5554,emulator-5556
#
# Drop -no-window below if you want to watch the runs.

set -euo pipefail

[[ $# -ge 1 ]] || { echo "usage: $0 <avd-name> [<avd-name> ...]" >&2; exit 2; }

command -v emulator >/dev/null 2>&1 || {
    echo "start-emulators.sh: 'emulator' not on PATH (add \$ANDROID_HOME/emulator)." >&2
    exit 1
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

port=5554
serials=()
for avd in "$@"; do
    serial="emulator-$port"
    echo "Booting $avd on $serial ..."
    nohup emulator -avd "$avd" -port "$port" \
        -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect \
        >/dev/null 2>&1 &
    serials+=("$serial")
    port=$((port + 2))
done

for serial in "${serials[@]}"; do
    "$script_dir/setup-emulator-egypt.sh" "$serial"
done

echo
echo "Ready: ${serials[*]}"
