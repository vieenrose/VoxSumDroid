#!/usr/bin/env bash
# Flattens desktop/build-native's scattered .so outputs into a single directory and rewrites
# each one's RPATH to $ORIGIN, so the whole set is relocatable — required for packaging (jpackage
# copies appResourcesRootDir content into the final app image at a path decided at package/install
# time, which the build-tree's absolute RPATHs know nothing about).
#
# Run after build-native.sh. Output feeds :desktop's appResourcesRootDir (desktop/appResources/
# linux-x64/), from which Main.kt's NativeLibs loader System.load()s the two leaf libraries
# (libvoxsum-llm.so, libsherpa-onnx-jni.so); their own $ORIGIN RPATH pulls in the rest.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="${VOXSUM_NATIVE_BUILD_DIR:-$DESKTOP_DIR/build-native}"
OUT_DIR="${VOXSUM_NATIVE_LIBS_OUT_DIR:-$DESKTOP_DIR/appResources/linux-x64}"

PATCHELF="${PATCHELF:-patchelf}"
if ! command -v "$PATCHELF" >/dev/null 2>&1; then
  echo "error: patchelf not found on PATH (set PATCHELF=/path/to/patchelf, or: pip install --user patchelf)" >&2
  exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Copy real files only (skip the *.so / *.so.N convenience symlinks CMake creates — a fresh
# flat copy of the versioned .so.N.N.N files is enough; JNI code loads by explicit filename).
copy_real() {
  find "$1" -maxdepth 1 -name "$2" -type f -exec cp {} "$OUT_DIR/" \;
}
copy_real "$BUILD_DIR/bin" "libggml-base.so*"
copy_real "$BUILD_DIR/bin" "libggml-cpu.so*"
copy_real "$BUILD_DIR/bin" "libggml.so*"
copy_real "$BUILD_DIR/bin" "libllama.so*"
copy_real "$BUILD_DIR/lib" "libsherpa-onnx-jni.so"
copy_real "$BUILD_DIR/lib" "libsherpa-onnx-c-api.so"
copy_real "$BUILD_DIR/lib" "libsherpa-onnx-cxx-api.so"
copy_real "$BUILD_DIR/_deps/onnxruntime-src/lib" "libonnxruntime.so*"
copy_real "$BUILD_DIR" "libvoxsum-llm.so"

# Recreate the unversioned SONAME symlinks flattened builds still need for dlopen-by-SONAME
# (e.g. libllama.so.0.0.1's own DT_SONAME is usually libllama.so.0).
for f in "$OUT_DIR"/*.so.*.*.*; do
  [ -e "$f" ] || continue
  soname="$("$PATCHELF" --print-soname "$f" 2>/dev/null || true)"
  [ -n "$soname" ] && ln -sf "$(basename "$f")" "$OUT_DIR/$soname"
done

echo ">> rewriting RPATH to \$ORIGIN for relocatability"
for f in "$OUT_DIR"/*.so "$OUT_DIR"/*.so.*.*.*; do
  [ -e "$f" ] && [ ! -L "$f" ] || continue
  "$PATCHELF" --set-rpath '$ORIGIN' "$f"
done

echo "Flattened + patched native libs -> $OUT_DIR"
ls -la "$OUT_DIR"
