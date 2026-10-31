#!/usr/bin/env bash
# Configures an already-running Android emulator with an English UI and Egypt as the region
# (system locale + simulated SIM/carrier), so apps that auto-detect the phone country code from
# either the system locale or TelephonyManager's SIM country pick up Egypt without a manual
# picker tap, while the UI stays in English (the test script relies on English visible text).
#
# Run once per emulator boot, before `mvn test`:
#   ./scripts/setup-emulator-egypt.sh [device-serial]
# Defaults to `emulator-5554` (the device this repo's tests target by default).
#
# The locale defaults to en-EG (English UI, Egypt region). Override it for a one-off run with
# EMULATOR_LOCALE, e.g. `EMULATOR_LOCALE=ar-EG ./scripts/setup-emulator-egypt.sh` to restore the
# Arabic UI.
#
# Boot-time alternative: passing `-prop persist.sys.locale=en-EG -prop persist.sys.country=EG`
# to the `emulator` launch command is more reliable than post-boot `setprop` on some Android
# versions, but requires `-wipe-data` on an existing AVD to take effect on first boot. This repo
# doesn't launch AVDs itself, so this script instead configures a device that's already running.

set -euo pipefail

SERIAL="${1:-emulator-5554}"
LOCALE="${EMULATOR_LOCALE:-en-EG}"

# Egypt MCC 602, Etisalat MNC 03 as a representative carrier.
MCC_MNC="60203"
ISO_COUNTRY="eg"
CARRIER_ALPHA="Etisalat"

adb -s "$SERIAL" wait-for-device

echo "Waiting for boot to complete..."
until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
  sleep 1
done

echo "Setting system locale to $LOCALE..."
adb -s "$SERIAL" shell settings put system system_locales "$LOCALE"
# Nudge already-running apps to re-read the locale. Newer emulator images reject
# `am broadcast` of this protected intent from the shell UID — harmless here: the
# app under test is (re)installed and cold-started every session, so it reads the
# new locale on launch regardless.
adb -s "$SERIAL" shell am broadcast -a android.intent.action.LOCALE_CHANGED >/dev/null 2>&1 \
  || echo "  Note: LOCALE_CHANGED broadcast blocked for shell — locale still applies on next app launch."

echo "Simulating an Egyptian SIM/carrier..."
if adb -s "$SERIAL" root >/dev/null 2>&1; then
  adb -s "$SERIAL" wait-for-device
  adb -s "$SERIAL" shell setprop gsm.sim.operator.numeric "$MCC_MNC"
  adb -s "$SERIAL" shell setprop gsm.operator.numeric "$MCC_MNC"
  adb -s "$SERIAL" shell setprop gsm.sim.operator.iso-country "$ISO_COUNTRY"
  adb -s "$SERIAL" shell setprop gsm.operator.iso-country "$ISO_COUNTRY"
  adb -s "$SERIAL" shell setprop gsm.sim.operator.alpha "$CARRIER_ALPHA"
  adb -s "$SERIAL" shell setprop gsm.operator.alpha "$CARRIER_ALPHA"
  adb -s "$SERIAL" shell su 0 killall com.android.phone 2>/dev/null || \
    echo "  Note: couldn't restart the phone process — SIM props may not take effect until the emulator is restarted."
else
  echo "  Note: 'adb root' failed (device may not support it) — skipping SIM/carrier props." \
       "Locale was still set above."
fi

echo "Done. Verify with:"
echo "  adb -s $SERIAL shell settings get system system_locales"
echo "  adb -s $SERIAL shell dumpsys telephony.registry | grep -i countryIso"
