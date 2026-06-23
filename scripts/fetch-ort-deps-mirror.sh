#!/usr/bin/env bash
# Pre-download every onnxruntime build-time dependency (cmake/deps.txt) into a local mirror
# so ORT can be built with NO network — required for F-Droid's offline build server.
#
# ORT rewrites each dep URL "https://X" -> "$MIRROR/X" (onnxruntime_external_deps.cmake), so
# the mirror must preserve the URL path. Pass the mirror to the ORT build via
#   build.py --cmake_deps_mirror_dir "$MIRROR"
# (scripts/build-onnxruntime-android.sh does this when VOXSUM_ORT_MIRROR is set).
set -euo pipefail

ORT_SRC="${ORT_SRC:-$HOME/ort-src}"
MIRROR="${VOXSUM_ORT_MIRROR:-$HOME/ort-deps-mirror}"
DEPS="$ORT_SRC/cmake/deps.txt"

[ -f "$DEPS" ] || { echo "deps.txt not found at $DEPS (clone onnxruntime first)"; exit 1; }
mkdir -p "$MIRROR"

# deps.txt is CSV: name;url;sha1  (comments start with #)
grep -vE '^\s*#' "$DEPS" | grep ';' | while IFS=';' read -r name url sha1; do
  url="$(echo "$url" | tr -d '[:space:]')"
  case "$url" in https://*) ;; *) continue ;; esac
  rel="${url#https://}"
  dest="$MIRROR/$rel"
  if [ -f "$dest" ]; then echo "have: $name"; continue; fi
  mkdir -p "$(dirname "$dest")"
  echo "fetch: $name -> $rel"
  curl -sL -o "$dest" "$url"
done

echo ">> mirror ready at $MIRROR ($(du -sh "$MIRROR" | cut -f1))"
