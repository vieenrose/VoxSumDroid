# Submitting VoxSum to F-Droid

There are two ways onto F-Droid. **Route B ships today**; **Route A** (official) is prepared
and its hard technical blocker is solved, but the final submission is account/infrastructure
gated and needs you.

---

## Route B — self-hosted F-Droid repo (recommended, ships now)

You build + sign the APK in CI and publish a small F-Droid repo to GitHub Pages; users add the
repo URL in the F-Droid / Droid-ify client. No review queue, **no offline-build constraint**
(your CI has network, so onnxruntime builds normally). Everything is already wired in
`.github/workflows/fdroid.yml`.

**What only you can do (≈5 minutes):**
1. Create a release keystore (keep it forever) — see `RELEASING.md`.
2. Add 4 repo secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
3. `Settings → Pages → Source = GitHub Actions`.
4. `git tag v0.1.0 && git push origin v0.1.0`.

Result: a live repo at `https://vieenrose.github.io/VoxSumDroid/repo` that users add to F-Droid.

---

## Route A — official f-droid.org repository

F-Droid builds from source on their servers (the build VM **has network** — that's how Gradle
resolves dependencies; the gatekeeper is the `scanner`, not an air gap) and publishes under
their own signing key. The recipe is ready (`metadata/studio.voxsum.yml`). The real challenges:

### 1. onnxruntime deps — NOT actually a blocker

ORT fetches ~40 source deps at build time (`cmake/deps.txt`). Since the F-Droid build VM has
network, ORT builds the same way it did in our GitHub CI run (which had network and built in
~40 min). So **no offline mirror is required for inclusion.**

The offline mirror tooling (`scripts/fetch-ort-deps-mirror.sh`, validated to build ORT with zero
network) is kept only for **reproducible builds** — a separate, optional F-Droid status, not a
requirement for getting listed.

Watch instead: ORT downloads a **prebuilt protoc** as a build tool (`protoc_linux_*` in
deps.txt). F-Droid's scanner may flag downloaded binaries used at build time — likely handled
with a `scanignore`/`scandelete` entry, but confirm via `fdroid scanner` / `fdroid build`.

### 2. Build is heavy — the main risk

onnxruntime + sherpa-onnx + llama.cpp from source is tens of minutes and several GB. This may
exceed F-Droid's default buildserver limits. There is no way around verifying this on *their*
infrastructure — so the realistic path is to open the MR and **coordinate with F-Droid
maintainers** early about the build cost (they sometimes grant bigger runners or will say no).

### Steps (what you do)

1. Tag a release: `git tag v0.1.0 && git push origin v0.1.0`.
2. Add onnxruntime as a pinned submodule and vendor the deps mirror (see scripts).
3. Install tooling and test the build exactly as their server will:
   ```bash
   pip install fdroidserver
   fdroid lint studio.voxsum
   fdroid build studio.voxsum      # runs in an F-Droid build VM
   ```
4. Fork `gitlab.com/fdroid/fdroiddata`, add `metadata/studio.voxsum.yml`, open a **Merge
   Request**. Mention the heavy native build up front.

### What I can't do for you
- File the GitLab MR (needs your fdroiddata fork + account).
- Run F-Droid's actual buildserver (only `fdroid build` in their VM or their CI proves it).
- Negotiate build-resource limits with maintainers (a human process).

---

## AntiFeatures / policy

- 100% FOSS deps; native libs built from source (no committed `.so`/`.aar`).
- Models are FOSS (Apache-2.0 / MIT), **SHA-256-pinned**, downloaded at first run from upstream
  release pages (or side-loadable to stay network-free). No non-free models (Llama, Gemma).
- No Google Play Services, analytics, or trackers. Default build earns no AntiFeature.
- License: GPL-3.0-or-later. (Replace the `LICENSE` stub with the full GPL text before submitting.)
