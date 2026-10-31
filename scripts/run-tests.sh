#!/usr/bin/env bash
#
# Runs the test suite and always prints the parsed pass/fail report
# afterward, so you don't have to separately run
# parse-surefire-results.sh - the same report CI posts to Slack.
#
# Usage: scripts/run-tests.sh [-n|--repeat <count>] [-p|--parallel] [TestClassName] [extra mvn test args]
#   -n, --repeat <count>   How many times to run the whole suite in a row
#                          (e.g. to check for flakiness). Defaults to 3.
#   -p, --parallel         Run across two emulators in parallel: adds
#                          -Dtest.forkCount=2 and, unless you pass your own
#                          -Dandroid.devices=..., -Dandroid.devices=emulator-5554,emulator-5556.
#                          Boot the emulators first with scripts/start-emulators.sh.
#   TestClassName          A bare test class name (no dashes) is shorthand for
#                          -Dtest=TestClassName, e.g.
#                            scripts/run-tests.sh JourneyHighschoolTest
#   -h, --help             Show this help and exit.
#   Any other args (e.g. -Dretry.maxAttempts=1, -Dretry.skip=true, -Dtest=...)
#   are forwarded to mvn test as-is.
#
# Before the first run this does a best-effort check that the connected Android
# emulator has enough free space on /data to (re)install the staging APK, and
# aborts with a clear message if not - Appium's own failure for this is a
# 40-line SessionNotCreatedException. Set RUN_TESTS_SKIP_PREFLIGHT=1 to skip it.

set -uo pipefail

# Load local settings (see .env.example) so the preflight below and the Java
# tests agree on paths/devices. Real environment variables win over .env.
if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

REPORTS_DIR="target/surefire-reports"

usage() {
    cat <<'EOF'
Usage: scripts/run-tests.sh [-n|--repeat <count>] [-p|--parallel] [TestClassName] [extra mvn test args]

  -n, --repeat <count>   How many times to run the whole suite in a row
                         (e.g. to check for flakiness). Defaults to 3.
  -p, --parallel         Run across two emulators in parallel: adds
                         -Dtest.forkCount=2 and, unless you pass your own
                         -Dandroid.devices=..., -Dandroid.devices=emulator-5554,emulator-5556.
                         Boot the emulators first with scripts/start-emulators.sh.
  TestClassName          A bare test class name (no dashes) is shorthand for
                         -Dtest=TestClassName, e.g.
                           scripts/run-tests.sh JourneyHighschoolTest
  -h, --help             Show this help and exit.

Any other args (e.g. -Dretry.skip=true, -Dtest=..., -Dplatform=ios) are
forwarded to mvn test as-is.
EOF
}

APK_PATH="${APP_APK_PATH:-src/test/resources/app-staging-release.apk}"
APP_PACKAGE="${APP_PACKAGE:-<your.app.package>}"

# Best-effort: bail out early (instead of mid-Appium-session) when an attached
# Android emulator can't fit a reinstall of the staging APK on /data. Checks
# every attached device (so a two-emulator parallel run is covered too). Any
# uncertainty - no adb, no devices, unparseable df, iOS run,
# RUN_TESTS_SKIP_PREFLIGHT set - just skips silently and lets the run proceed.
preflight_emulator_storage() {
    [[ -n "${RUN_TESTS_SKIP_PREFLIGHT:-}" ]] && return 0

    local arg
    for arg in "$@"; do
        [[ "$arg" == "-Dplatform=ios" ]] && return 0
    done

    [[ -f "$APK_PATH" ]] || return 0
    command -v adb >/dev/null 2>&1 || return 0

    local serials=()
    while read -r serial state _; do
        [[ "$state" == "device" ]] && serials+=("$serial")
    done < <(adb devices 2>/dev/null | tail -n +2)
    [[ ${#serials[@]} -ge 1 ]] || return 0

    local apk_bytes
    apk_bytes=$(wc -c < "$APK_PATH" | tr -d ' ')
    # Install stages a copy, then dexopt/oat + extracted native libs on top.
    local need_bytes=$((apk_bytes * 3))

    for serial in "${serials[@]}"; do
        # "/dev/block/dm-5  6082144  4926876  1013056  83% /data/user/0" -> col 4, in KiB
        local avail_kib
        avail_kib=$(adb -s "$serial" shell df /data 2>/dev/null \
            | awk '/\/data/ {print $4; exit}' | tr -d '\r')
        [[ "$avail_kib" =~ ^[0-9]+$ ]] || continue

        local free_bytes=$((avail_kib * 1024))
        [[ $free_bytes -ge $need_bytes ]] && continue

        local avd
        avd=$(adb -s "$serial" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r')
        [[ -z "$avd" ]] && avd=$(adb -s "$serial" shell getprop ro.kernel.qemu.avd_name 2>/dev/null | tr -d '\r')
        [[ -z "$avd" ]] && avd="<your-avd>"

        cat >&2 <<EOF

run-tests.sh: not enough free space on $serial to install the staging APK.
  APK size:        $((apk_bytes / 1024 / 1024)) MB   ($APK_PATH)
  Free on /data:   $((free_bytes / 1024 / 1024)) MB
  Needed (~3x):    $((need_bytes / 1024 / 1024)) MB

The APK is reinstalled every session (appium:enforceAppInstall=true), so /data
needs real headroom. Grow the emulator's data partition:

  1. Quit the emulator.
  2. Edit ~/.android/avd/${avd}.avd/config.ini:  disk.dataPartition.size=16G
  3. Cold boot with a wipe:  emulator -avd ${avd} -wipe-data -no-snapshot-load
  4. Re-apply locale/SIM:     ./scripts/setup-emulator-egypt.sh $serial

Stopgap without a wipe (may not free enough on a 6G AVD):
  adb -s $serial uninstall $APP_PACKAGE
  adb -s $serial shell pm trim-caches 9999999999

Set RUN_TESTS_SKIP_PREFLIGHT=1 to bypass this check.
EOF
        exit 1
    done
}

REPEAT=3
PARALLEL=""
BARE_TEST=""
MVN_ARGS=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--repeat)
            if [[ -z "${2:-}" || ! "$2" =~ ^[0-9]+$ ]]; then
                echo "run-tests.sh: $1 needs a numeric count" >&2
                exit 2
            fi
            REPEAT="$2"
            shift 2
            ;;
        -p|--parallel)
            PARALLEL=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            MVN_ARGS+=("$1")
            shift
            ;;
        *)
            if [[ -n "$BARE_TEST" ]]; then
                echo "run-tests.sh: more than one bare test name given ('$BARE_TEST', '$1')" >&2
                exit 2
            fi
            BARE_TEST="$1"
            shift
            ;;
    esac
done

if [[ -n "$BARE_TEST" ]]; then
    if [[ ${#MVN_ARGS[@]} -gt 0 ]]; then
        for arg in "${MVN_ARGS[@]}"; do
            if [[ "$arg" == -Dtest=* ]]; then
                echo "run-tests.sh: can't combine bare test name '$BARE_TEST' with '$arg'" >&2
                exit 2
            fi
        done
    fi
    MVN_ARGS+=("-Dtest=$BARE_TEST")
fi

if [[ -n "$PARALLEL" ]]; then
    MVN_ARGS+=("-Dtest.forkCount=2")
    has_devices=""
    for arg in "${MVN_ARGS[@]}"; do
        [[ "$arg" == -Dandroid.devices=* ]] && has_devices=1
    done
    [[ -n "$has_devices" ]] || MVN_ARGS+=("-Dandroid.devices=emulator-5554,emulator-5556")
fi

if [[ ${#MVN_ARGS[@]} -gt 0 ]]; then
    preflight_emulator_storage "${MVN_ARGS[@]}"
else
    preflight_emulator_storage
fi

OVERALL_EXIT=0
for ((i = 1; i <= REPEAT; i++)); do
    echo
    echo "===== Run $i/$REPEAT ====="
    # Clear last run's reports so the post-run parse below only ever sees
    # this run's output (mvn test alone doesn't purge stale TEST-*.xml, so
    # parse-surefire-results.sh would otherwise report classes that didn't
    # run this time).
    rm -rf "$REPORTS_DIR"
    if [[ ${#MVN_ARGS[@]} -gt 0 ]]; then
        mvn test "${MVN_ARGS[@]}"
    else
        mvn test
    fi
    RUN_EXIT=$?
    if [[ $RUN_EXIT -ne 0 ]]; then
        OVERALL_EXIT=1
    fi

    echo
    echo "===== Test report (run $i/$REPEAT) ====="
    ./scripts/parse-surefire-results.sh "$REPORTS_DIR"
done

exit "$OVERALL_EXIT"
