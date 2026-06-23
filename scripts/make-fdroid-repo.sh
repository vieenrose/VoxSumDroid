#!/usr/bin/env bash
# Generate a self-hosted F-Droid repo from a signed release APK.
# Usage: scripts/make-fdroid-repo.sh <path-to-signed-apk>
#
# Env (same keystore that signed the APK is reused to sign the repo index):
#   VOXSUM_KEYSTORE, VOXSUM_KEYSTORE_PASSWORD, VOXSUM_KEY_ALIAS, VOXSUM_KEY_PASSWORD
#   FDROID_REPO_URL  (e.g. https://owner.github.io/VoxSumDroid/repo)
#   FDROID_OUT       (output dir; default ./fdroid)
set -euo pipefail

APK="${1:?usage: make-fdroid-repo.sh <signed-apk>}"
OUT="${FDROID_OUT:-$PWD/fdroid}"
REPO_URL="${FDROID_REPO_URL:-https://example.github.io/VoxSumDroid/repo}"

rm -rf "$OUT" && mkdir -p "$OUT/repo"
cp "$APK" "$OUT/repo/"

cat > "$OUT/config.yml" <<EOF
repo_url: "$REPO_URL"
repo_name: "VoxSum"
repo_description: "Offline, on-device audio transcription and summarization."
archive_older: 0
keystore: "$VOXSUM_KEYSTORE"
repo_keyalias: "$VOXSUM_KEY_ALIAS"
keystorepass: "$VOXSUM_KEYSTORE_PASSWORD"
keypass: "$VOXSUM_KEY_PASSWORD"
keydname: "CN=VoxSum"
EOF

cd "$OUT"
# --create-metadata auto-generates per-app metadata from the APK manifest.
fdroid update --create-metadata --pretty
echo ">> F-Droid repo written to $OUT/repo  (index URL: $REPO_URL)"
