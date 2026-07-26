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
#
# "Unable to resolve activity …" — two DIFFERENT causes, do not confuse them:
#
#   1. On the x86_64 EMULATOR, createComposeRule() tests (AddSourceSheetTest, SourceSheetsTest,
#      UiComponentsTest, SettingsContentTest …) fail under the suffixed id looking for
#      <testpkg>/androidx.activity.ComponentActivity. Adding ui-test-manifest as an androidTest
#      dependency and declaring the activity in an androidTest manifest both failed to fix it.
#      This does NOT reproduce on a real device: all four classes pass here on a Boox (API 30).
#
#   2. After ANY class crashes, Android marks the app package "stopped" and excludes it from
#      intent and provider resolution, so every later class that launches an activity or touches
#      the FileProvider fails the same way — a cascade with nothing wrong in those classes. The
#      loop below clears the flag by launching the app after a crash.
set -euo pipefail

SERIAL="${1:-}"
[ $# -gt 0 ] && shift || true
[ "${1:-}" = "--" ] && shift || true

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
APP_ID=studio.voxsum.androidtest   # -PisolatedTestId suffixes the real id with .androidtest
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

# Seed models the tests would otherwise DOWNLOAD onto the device. Worth doing: several classes
# provision what they need rather than skipping (ActionItemExtractorTest calls ensureLlmModel), and
# a 2.6 GB summarizer over a tablet's wifi does not finish inside the 20-minute test timeout — the
# class then fails on TestTimedOutException, which looks like a product bug and is not one.
# Point VOXSUM_SEED_MODELS at a directory laid out like the app's files/models:
#   xasr-litert/{xasr_q8_octav.tflite,tokens.txt}
#   nemotron-litert/{nemotron_*.tflite,tokenizer.json}
#   silero-vad.tflite  moss_td_*.tflite  moss_td_vocab.json  gemma-4-e2b-it.litertlm
if [ -n "${VOXSUM_SEED_MODELS:-}" ] && [ -d "$VOXSUM_SEED_MODELS" ]; then
  echo ">> seeding models from $VOXSUM_SEED_MODELS (skips the on-device download)"
  "$ADB" -s "$SERIAL" shell "rm -rf /data/local/tmp/voxsum-seed && mkdir -p /data/local/tmp/voxsum-seed"
  "$ADB" -s "$SERIAL" push "$VOXSUM_SEED_MODELS/." /data/local/tmp/voxsum-seed >/dev/null
  "$ADB" -s "$SERIAL" shell "run-as ${APP_ID} sh -c 'mkdir -p files/models && cp -r /data/local/tmp/voxsum-seed/. files/models/'"
  "$ADB" -s "$SERIAL" shell "rm -rf /data/local/tmp/voxsum-seed"
fi

# Boox (and other aggressively-managed devices) DISABLE a package after it crashes: the state shows
# up as `enabled=3` in `dumpsys package <pkg> | grep 'User 0:'`. A disabled package's components
# cannot be launched, so ActivityScenario fails with "Unable to find explicit activity class …
# InstrumentationActivityInvoker$EmptyActivity" or "Unable to resolve activity", and EVERY
# activity-based class fails for a reason that has nothing to do with the code. Re-enable both
# packages before each run — cheap, and it turns a full red suite back green.
"$ADB" -s "$SERIAL" shell pm enable "$APP_ID" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell pm enable "$APP_ID.test" >/dev/null 2>&1 || true

echo ">> running the suite (models not seeded above are downloaded on first use — slow)"
# Per class, not one big run: a test that gets OOM-killed takes the whole instrumentation with it
# (the runner dies, and every class after it silently never reports). Isolating them means one
# crash costs one class. Pass -e class ... yourself to run a single one.
RUNNER=studio.voxsum.androidtest.test/androidx.test.runner.AndroidJUnitRunner
if [ $# -gt 0 ]; then
  "$ADB" -s "$SERIAL" shell am instrument -w -r "$@" "$RUNNER"
else
  # Enumerate DECLARED class names, not filenames: LlmEngineTest.kt declares TextGenTest, and a
  # filename-derived list silently never ran it while reporting a phantom ClassNotFoundException
  # failure for the file's name. Only files that actually contain @Test are considered.
  CLASSES=$(grep -l '@Test' "$ROOT"/app/src/androidTest/java/studio/voxsum/*.kt \
    | xargs -r grep -hoP '^\s*class\s+\K\w+' | sort -u)
  pass=0; fail=0; crash=0
  for c in $CLASSES; do
    printf '\n=== %s ===\n' "$c"
    out=$("$ADB" -s "$SERIAL" shell am instrument -w -r -e class "studio.voxsum.$c" "$RUNNER" 2>&1) || true
    echo "$out" | grep -E '^(OK|FAILURES|Tests run|INSTRUMENTATION_RESULT: shortMsg)' || true
    if echo "$out" | grep -q 'Process crashed'; then
      crash=$((crash+1))
      # A crash leaves the app package FORCE-STOPPED, and Android excludes stopped packages from
      # intent/provider resolution — every later class that launches an activity or touches the
      # FileProvider then fails with "Unable to resolve activity", which looks like a real failure
      # and is not. Launching the app clears the stopped flag so the next class starts clean.
      # A crash can leave the package DISABLED (Boox) or merely stopped. Re-enable both and wake
      # the app; do NOT force-stop afterwards, that would re-set the flag we are clearing.
      "$ADB" -s "$SERIAL" shell pm enable "$APP_ID" >/dev/null 2>&1 || true
      "$ADB" -s "$SERIAL" shell pm enable "$APP_ID.test" >/dev/null 2>&1 || true
      "$ADB" -s "$SERIAL" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
      sleep 3
    elif echo "$out" | grep -q '^FAILURES'; then fail=$((fail+1))
    else pass=$((pass+1)); fi
  done
  printf '\n>> classes: %d clean, %d with failures, %d crashed\n' "$pass" "$fail" "$crash"
fi

# Clean up so the isolated copy's models do not sit on the device forever.
echo ">> uninstalling the isolated test build"
"$ADB" -s "$SERIAL" uninstall studio.voxsum.androidtest.test >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" uninstall studio.voxsum.androidtest >/dev/null 2>&1 || true
echo ">> done — the installed release build was never touched"
