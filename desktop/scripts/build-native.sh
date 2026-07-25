#!/usr/bin/env bash
# Build the Linux-desktop native libs (llama.cpp + the voxsum-llm JNI bridge, plus sherpa-onnx
# for ASR/VAD/diarization) for the :desktop module, then flatten + relocate them for packaging.
# Host-arch build (linux-x86_64), not a cross-compile — plain CMake/Ninja, no NDK involved.
# See desktop/src/jvmMain/cpp/CMakeLists.txt for exactly what gets built.
#
# Requires: cmake, ninja, a C/C++ toolchain, JAVA_HOME (for sherpa-onnx's JNI headers), and
# patchelf (used by flatten-native-libs.sh; `pip install --user patchelf` if not packaged for
# your distro).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="${VOXSUM_NATIVE_BUILD_DIR:-$DESKTOP_DIR/build-native}"

# NB: no apostrophe in this message — inside ${var:?word} bash treats one as an opening
# quote and the script fails to parse ("unexpected EOF while looking for matching `}'").
: "${JAVA_HOME:?JAVA_HOME must be set: the sherpa-onnx JNI build needs jni.h}"

mkdir -p "$BUILD_DIR"
cmake -G Ninja -DCMAKE_BUILD_TYPE=Release -S "$DESKTOP_DIR/src/jvmMain/cpp" -B "$BUILD_DIR"
cmake --build "$BUILD_DIR" -- -j "$(nproc)"

"$SCRIPT_DIR/flatten-native-libs.sh"

# MOSS-TD subprocess binaries (moss-td-test + rs-speaker-embed) — staged into the same
# appResources dir AFTER flatten (which wipes it). Skipped if the submodule isn't present.
if [ -f "$DESKTOP_DIR/../native/RapidSpeech.cpp/CMakeLists.txt" ]; then
  "$SCRIPT_DIR/build-moss.sh"
else
  echo "note: native/RapidSpeech.cpp not initialized — skipping MOSS-TD binaries." >&2
fi

echo ""
echo "Native libs built and staged for packaging at desktop/appResources/linux-x64."
echo "Run/package normally: ./gradlew :desktop:run   or   ./gradlew :desktop:packageDeb"
