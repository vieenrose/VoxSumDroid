#!/usr/bin/env bash
# Build the Linux-desktop native libs (llama.cpp + the voxsum-llm JNI bridge) for the
# :desktop module. Host-arch build (linux-x86_64), not a cross-compile — plain CMake/Ninja,
# no NDK involved. See desktop/src/jvmMain/cpp/CMakeLists.txt for what this produces and
# what it doesn't (yet) cover — sherpa-onnx ASR/VAD/diarization is a separate follow-up.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="${VOXSUM_NATIVE_BUILD_DIR:-$DESKTOP_DIR/build-native}"

mkdir -p "$BUILD_DIR"
cmake -G Ninja -DCMAKE_BUILD_TYPE=Release -S "$DESKTOP_DIR/src/jvmMain/cpp" -B "$BUILD_DIR"
cmake --build "$BUILD_DIR" --target voxsum-llm -- -j "$(nproc)"

echo "Built:"
echo "  $BUILD_DIR/libvoxsum-llm.so"
echo "  $BUILD_DIR/bin/libllama.so* $BUILD_DIR/bin/libggml*.so*"
echo "Run with -Djava.library.path=\"$BUILD_DIR:$BUILD_DIR/bin\" to load them."
