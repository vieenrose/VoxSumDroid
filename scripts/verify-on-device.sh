#!/usr/bin/env bash
# Validate VoxSum on a connected physical device (e.g. Pixel 6, arm64): build + install the
# arm64 app and test APKs, push pre-staged models if the device is rootable (else the tests
# download them), run the on-device instrumented suite, dump per-stage logs, and screenshot
# the launched app. Run when a device is connected and free.
#
# Needs an arm64 onnxruntime build at $SHERPA_ONNXRUNTIME_LIB_DIR (default ~/ort-build/Release;
# produced by scripts/build-onnxruntime-android.sh).
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
ADB="$SDK/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="${VOXSUM_MODELS_STAGE:-$HOME/voxsum-models-stage}"
PKG=studio.voxsum
SV=sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17

DEV=$("$ADB" devices | awk '/\tdevice$/{print $1; exit}')
[ -n "$DEV" ] || { echo "No device connected (adb devices shows none)"; exit 1; }
export ANDROID_SERIAL="$DEV"
echo ">> device: $DEV  model=$("$ADB" shell getprop ro.product.model | tr -d '\r')  abi=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')"

export SHERPA_ONNXRUNTIME_LIB_DIR="${SHERPA_ONNXRUNTIME_LIB_DIR:-$HOME/ort-build/Release}"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="${SHERPA_ONNXRUNTIME_INCLUDE_DIR:-$HOME/ort-headers}"
[ -f "$SHERPA_ONNXRUNTIME_LIB_DIR/libonnxruntime.so" ] || {
  echo "arm64 onnxruntime not found at $SHERPA_ONNXRUNTIME_LIB_DIR — run scripts/build-onnxruntime-android.sh"; exit 1; }
cd "$ROOT"

echo ">> building + installing arm64 app + test APKs (default ABI = arm64-v8a)"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Push staged models if the device is rootable (skips the ~750 MB in-test download).
if "$ADB" root 2>&1 | grep -qiE "running as root|already running as root"; then
  sleep 3; "$ADB" wait-for-device
  D=/data/data/$PKG/files/models
  "$ADB" shell mkdir -p "$D/$SV"
  [ -f "$STAGE/silero_vad.onnx" ]        && "$ADB" push "$STAGE/silero_vad.onnx" "$D/"
  [ -f "$STAGE/$SV/model.int8.onnx" ]    && "$ADB" push "$STAGE/$SV/model.int8.onnx" "$D/$SV/"
  [ -f "$STAGE/$SV/tokens.txt" ]         && "$ADB" push "$STAGE/$SV/tokens.txt" "$D/$SV/"
  [ -f "$STAGE/llm.gguf" ]               && "$ADB" push "$STAGE/llm.gguf" "$D/llm.gguf"
  U=$("$ADB" shell stat -c %u /data/data/$PKG | tr -d '\r')
  "$ADB" shell chown -R "$U:$U" /data/data/$PKG/files
  "$ADB" shell restorecon -R /data/data/$PKG/files
  echo ">> pushed staged models"
else
  echo ">> device not rootable; the tests will download models (needs network)"
fi

echo ">> running on-device instrumented suite"
"$ADB" logcat -c
./gradlew :app:connectedDebugAndroidTest --no-daemon || echo "(some tests failed — see logs)"
echo ">> per-stage logs:"
"$ADB" logcat -d -s AsrEngineTest:* LlmEngineTest:* DiarizationTest:* PipelineE2ETest:* | tail -40

echo ">> launching app + screenshot"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
sleep 4
"$ADB" exec-out screencap -p > /tmp/voxsum-device.png 2>/dev/null && echo ">> screenshot: /tmp/voxsum-device.png"
echo ">> done."
