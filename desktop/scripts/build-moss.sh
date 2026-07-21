#!/usr/bin/env bash
# Build the MOSS-TD subprocess binaries (rs-moss-td + rs-speaker-embed) from the
# native/RapidSpeech.cpp submodule and stage them into desktop/appResources/linux-x64/moss/.
#
# rs-moss-td is the vendored MIT port's CLI (rapidspeech/src/arch/moss_td, links only ggml);
# rs-speaker-embed still needs librapidspeech-core.so for the CAM++ rs_speaker_* API.
#
# These are standalone CLI executables the desktop app spawns per audio window (see
# MossSubprocessEngine.kt) — NOT libraries the JVM System.load()s — so they live outside
# desktop/src/jvmMain/cpp/CMakeLists.txt's JNI build graph.
#
# Built as a normal SHARED build (NOT RS_STATIC_EXE): each ASR architecture self-registers via a
# static initializer, and a static archive drops the MossTD registrar object because nothing
# references it directly ("Unsupported architecture: MossTD" at load) — a shared librapidspeech-core.so
# keeps every translation unit, so the registrar runs. To avoid a libggml.so SONAME clash with the
# llama.cpp libggml.so.0 that lives in appResources/linux-x64/, the binaries AND their RapidSpeech
# .so deps are isolated in an appResources/linux-x64/moss/ subdir with an $ORIGIN RPATH — each is a
# separate subprocess resolving its own libs, so the two ggml copies never meet.
#
# Requires: cmake, ninja, a C++17 toolchain, patchelf. Run standalone, or it is invoked at the end
# of build-native.sh so the canonical packaging flow includes the MOSS binaries.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP_DIR="$(dirname "$SCRIPT_DIR")"
REPO_DIR="$(dirname "$DESKTOP_DIR")"
RS_SRC="$REPO_DIR/native/RapidSpeech.cpp"
BUILD_DIR="${VOXSUM_MOSS_BUILD_DIR:-$DESKTOP_DIR/build-moss}"
OUT_ROOT="${VOXSUM_NATIVE_LIBS_OUT_DIR:-$DESKTOP_DIR/appResources/linux-x64}"
OUT_DIR="$OUT_ROOT/moss"

PATCHELF="${PATCHELF:-patchelf}"
command -v "$PATCHELF" >/dev/null 2>&1 || { echo "error: patchelf not found (pip install --user patchelf)" >&2; exit 1; }

if [ ! -f "$RS_SRC/CMakeLists.txt" ]; then
  echo "error: native/RapidSpeech.cpp submodule not initialized." >&2
  echo "  git submodule update --init --recursive native/RapidSpeech.cpp" >&2
  exit 1
fi

# RapidSpeech.cpp vendors ggml + cppjieba as its own submodules — init them if missing.
if [ ! -f "$RS_SRC/ggml/CMakeLists.txt" ] || [ ! -d "$RS_SRC/third_party/cppjieba/include" ]; then
  git -C "$RS_SRC" submodule update --init --recursive
fi

echo ">> configuring RapidSpeech.cpp (shared libs, CPU-only)"
cmake -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DRS_STATIC_EXE=OFF -DRS_BUILD_TESTS=OFF -DRS_CUDA=OFF \
  -S "$RS_SRC" -B "$BUILD_DIR"

echo ">> building rs-moss-td + rs-speaker-embed"
cmake --build "$BUILD_DIR" --target rs-moss-td rs-speaker-embed -- -j "$(nproc)"

rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"

# Stage the two executables.
declare -a BINS=()
for bin in rs-moss-td rs-speaker-embed; do
  path="$(find "$BUILD_DIR" -maxdepth 2 -name "$bin" -type f -perm -u+x | head -1)"
  [ -n "$path" ] || { echo "error: built binary '$bin' not found under $BUILD_DIR" >&2; exit 1; }
  cp "$path" "$OUT_DIR/$bin"; chmod +x "$OUT_DIR/$bin"
  BINS+=("$OUT_DIR/$bin")
done

# Stage the RapidSpeech/ggml .so deps the binaries need (resolve via ldd, skip system libs so we
# only carry RapidSpeech's own ggml — the system libstdc++/libc/libgomp stay system-provided).
echo ">> staging RapidSpeech .so dependencies"
for b in "${BINS[@]}"; do
  ldd "$b" 2>/dev/null | awk '/=>/ {print $3}' | while read -r so; do
    [ -f "$so" ] || continue
    case "$so" in
      *"$BUILD_DIR"*|*/librapidspeech*|*/libggml*|*/libwetext*) cp -u "$so" "$OUT_DIR/" ;;
    esac
  done
done

# Relocatable: every staged .so AND executable resolves its siblings from its own dir.
for f in "$OUT_DIR"/*; do
  [ -f "$f" ] || continue
  "$PATCHELF" --set-rpath '$ORIGIN' "$f" 2>/dev/null || true
done

echo "MOSS-TD binaries + libs staged -> $OUT_DIR"
ls -la "$OUT_DIR"
