#!/usr/bin/env bash
# Build onnxruntime from source for Android arm64 — the F-Droid-compatible ORT that
# sherpa-onnx links against (NO prebuilt binary). This is the proven SPIKE 0.2 recipe.
#
# Pinned to v1.24.3 to match the ORT headers/ABI sherpa-onnx expects
# (sherpa default SHERPA_ONNX_ONNXRUNTIME_VERSION). Verified building with NDK 27.2,
# host cmake >= 3.28, producing Release/libonnxruntime.so (~19 MB).
#
# Outputs (consumed by the app's CMake via env vars — see scripts/build-app.sh):
#   $ORT_BUILD/Release/libonnxruntime.so
#   $ORT_HEADERS/ (flattened public headers)
set -euo pipefail

ORT_VERSION="v1.24.3"
ORT_SRC="${ORT_SRC:-$HOME/ort-src}"
ORT_BUILD="${ORT_BUILD:-$HOME/ort-build}"
ORT_HEADERS="${ORT_HEADERS:-$HOME/ort-headers}"
ANDROID_SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
NDK="${ANDROID_NDK:-$ANDROID_SDK/ndk/27.2.12479018}"
ABI="${ABI:-arm64-v8a}"
API="${API:-26}"

if [ ! -d "$ORT_SRC" ]; then
  echo ">> cloning onnxruntime $ORT_VERSION (shallow, +submodules)"
  git clone --depth 1 --branch "$ORT_VERSION" --recursive --shallow-submodules \
    https://github.com/microsoft/onnxruntime.git "$ORT_SRC"
fi

echo ">> building onnxruntime for Android $ABI (this is the slow gate)"
cd "$ORT_SRC"
python3 tools/ci_build/build.py \
  --build_dir "$ORT_BUILD" \
  --config Release \
  --android \
  --android_sdk_path "$ANDROID_SDK" \
  --android_ndk_path "$NDK" \
  --android_abi "$ABI" \
  --android_api "$API" \
  --build_shared_lib \
  --parallel \
  --skip_tests \
  --cmake_generator Ninja \
  --compile_no_warning_as_error \
  --allow_running_as_root

echo ">> flattening public headers into $ORT_HEADERS"
rm -rf "$ORT_HEADERS" && mkdir -p "$ORT_HEADERS"
cp "$ORT_SRC"/include/onnxruntime/core/session/*.h "$ORT_HEADERS"/
find "$ORT_SRC"/include -name cpu_provider_factory.h -o -name provider_options.h \
  | xargs -I{} cp {} "$ORT_HEADERS"/

echo ">> done."
echo "   export SHERPA_ONNXRUNTIME_LIB_DIR=$ORT_BUILD/Release"
echo "   export SHERPA_ONNXRUNTIME_INCLUDE_DIR=$ORT_HEADERS"
