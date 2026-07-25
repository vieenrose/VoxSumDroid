#!/usr/bin/env bash
# Run the instrumented suite on a real device WITHOUT touching an installed release build.
#
#   scripts/test-on-device.sh [adb-serial] [-- extra am instrument args]
#
# The problem this solves: instrumented tests need a debug-signed studio.voxsum, and Android
# refuses to install that over a release-signed one. Gradle's connectedAndroidTest resolves the
# clash by UNINSTALLING first, which deletes the session library and every downloaded model.
#
# So this installs under its own application id (-PisolatedTestId -> studio.voxsum.androidtest),
# which coexists with the real app. Two details make that work:
#   * androidTestImplementation of compose ui-test-manifest — with a suffixed id the Compose
#     rules launch ComponentActivity from the TEST package, so it must be declared there too.
#   * we install and instrument against ONE serial, so Gradle can't fan out to every device.
#
# The isolated app has its own storage: it re-downloads models (~1.7 GB) on first run and cannot
# see the real app's sessions. That is the point — nothing of the user's is read or written.
set -euo pipefail

SERIAL="${1:-}"
[ $# -gt 0 ] && shift || true
[ "${1:-}" = "--" ] && shift || true

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -z "$SERIAL" ]; then
  mapfile -t devices < <("$ADB" devices | awk '/\tdevice$/{print $1}')
  [ "${#devices[@]}" -eq 1 ] || {
    echo "error: specify a serial — found ${#devices[@]} devices:" >&2
    printf '  %s\n' "${devices[@]:-<none>}" >&2; exit 1; }
  SERIAL="${devices[0]}"
fi

# arm64 is the shipping ABI; emulators need x86_64. Pick from the device itself.
ABI="$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
echo ">> device $SERIAL ($ABI)"

"$ROOT/gradlew" -p "$ROOT" :app:assembleDebug :app:assembleDebugAndroidTest \
  -PisolatedTestId -PvoxsumAbi="$ABI"

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
echo ">> installing (alongside any release build)"
"$ADB" -s "$SERIAL" install -r -t "$APK" >/dev/null
"$ADB" -s "$SERIAL" install -r -t "$TEST_APK" >/dev/null

echo ">> running the suite (first run downloads models; expect it to be slow)"
"$ADB" -s "$SERIAL" shell am instrument -w -r "$@" \
  studio.voxsum.androidtest.test/androidx.test.runner.AndroidJUnitRunner

# Clean up so the isolated copy's models do not sit on the device forever.
echo ">> uninstalling the isolated test build"
"$ADB" -s "$SERIAL" uninstall studio.voxsum.androidtest.test >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" uninstall studio.voxsum.androidtest >/dev/null 2>&1 || true
echo ">> done — the installed release build was never touched"
