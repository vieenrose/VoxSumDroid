# Releasing — self-hosted F-Droid repo (Route B)

On every `v*` tag, `.github/workflows/fdroid.yml` builds onnxruntime from source, builds a
signed release APK, generates an F-Droid repo, and publishes it to GitHub Pages. Users add
the repo URL in the F-Droid / Droid-ify client — no Play Store, no review gate.

This is the fast delivery path while the app is in development. The official f-droid.org
repository (Route A) is a later step and additionally requires vendoring onnxruntime's
build-time dependencies for offline builds — see [`SPIKE.md`](SPIKE.md).

## One-time setup

### 1. Create a release keystore (keep it forever — it's your app identity)

```bash
keytool -genkey -v -keystore voxsum-release.keystore \
  -alias voxsum -keyalg RSA -keysize 4096 -validity 10000
```

Answer the prompts; remember the store + key passwords.

### 2. Add repository secrets

`Settings → Secrets and variables → Actions → New repository secret`:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 voxsum-release.keystore` (the whole file) |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | `voxsum` |
| `KEY_PASSWORD` | key password |

### 3. Enable Pages

`Settings → Pages → Build and deployment → Source = GitHub Actions`.

## Step 0 — DRY RUN first (do this before anything else)

The first build compiles onnxruntime from source on a GitHub runner — heavy, and unproven on
their hardware. Validate it cheaply, with **no keystore needed**:

1. Actions tab → **Build & publish F-Droid repo** → **Run workflow**.
2. It builds the app and uploads a **`voxsum-apk`** artifact. If that artifact appears, the
   native build fits a runner and you're clear to set up signing. If it fails (usually disk or
   time), that's the thing to fix before tagging — not after.

(The ORT build is cached after the first success, so later runs are much faster.)

## Cut a release

```bash
# bump versionCode / versionName in app/build.gradle.kts first
git tag v0.1.0
git push origin v0.1.0
```

The workflow runs (first run is slow — it builds onnxruntime; later runs hit the cache).
When it finishes, the repo is live at:

```
https://<owner>.github.io/<repo>/repo
```

Add that URL in F-Droid: **Settings → Repositories → +**. The app then installs and
auto-updates like any F-Droid app.

> **Two update paths.** F-Droid-client users auto-update through the client (above). Users who
> sideload the raw GitHub APK instead get an **in-app updater**: it checks GitHub Releases once/day
> and offers a one-tap download + system-installer update (needs the `REQUEST_INSTALL_PACKAGES`
> grant). That permission is an anti-feature on official f-droid.org — see the note in
> `metadata/studio.voxsum.yml`; a future Route A build must strip the updater via an `fdroid` flavor.

## Local dry-run

```bash
./scripts/build-onnxruntime-android.sh
export SHERPA_ONNXRUNTIME_LIB_DIR="$HOME/ort-build/Release"
export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$HOME/ort-headers"
export VOXSUM_KEYSTORE=$PWD/voxsum-release.keystore
export VOXSUM_KEYSTORE_PASSWORD=… VOXSUM_KEY_ALIAS=voxsum VOXSUM_KEY_PASSWORD=…
./gradlew :app:assembleRelease
pip install fdroidserver
FDROID_REPO_URL=http://localhost/repo ./scripts/make-fdroid-repo.sh \
  app/build/outputs/apk/release/app-release.apk
```
