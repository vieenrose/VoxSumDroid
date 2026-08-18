<p align="center">
  <img src="docs/screenshots/app-icon.png" width="96" alt="VoxSum" />
</p>

<h1 align="center">VoxSum for Android</h1>

<p align="center">
  <b>Turn any audio into a clean, speaker-labelled transcript and a short summary —<br>entirely on your phone, fully offline.</b>
</p>

<p align="center">
  <a href="https://github.com/vieenrose/VoxSumDroid/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/vieenrose/VoxSumDroid?sort=semver"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue">
  <img alt="Offline" src="https://img.shields.io/badge/network-not%20required-success">
</p>

<p align="center"><a href="README.zh-TW.md">繁體中文說明 →</a> · <a href="README.fr.md">Français →</a></p>

---

Record a meeting, open a voice memo, drop in a podcast or a YouTube link — VoxSum writes out **who said
what**, then gives you a **concise summary** in the language you choose. Everything happens **on the
device**: no account, no cloud, no subscription, and nothing ever leaves your phone.

VoxSum is a **recording studio**: the home screen is your **session list**, every recording is
**auto-saved the moment you stop** (a crash or a stray tap can never lose one), and **recording never
waits for processing** — capture talks back-to-back all day, then let the app transcribe and summarize
them one by one while you watch each session's live status.

> New here? The **[5-minute Quick Start →](docs/QUICKSTART.md)** walks through every feature.

<p align="center"><img src="docs/screenshots/demo.gif" width="300" alt="VoxSum demo — open a session, read the summary, tap a transcript line to play from there"></p>
<p align="center"><i>Open a finished session: the summary, the speaker-tagged transcript, and tap-to-play with the current line highlighted.</i></p>

## Why VoxSum

- 🛡️ **Private by design** — your audio never leaves the phone, so confidential recordings can't leak to a cloud.
- ✈️ **Works offline** — once set up, no network is needed: on a plane, a train, or off the grid.
- 💰 **No subscription** — own it outright. No metered minutes, no monthly fee.

## Screenshots

<p align="center">
  <img src="docs/screenshots/01-home.png" width="190" alt="Home">
  <img src="docs/screenshots/03-transcript.png" width="190" alt="Transcript">
  <img src="docs/screenshots/04-summary.png" width="190" alt="Summary">
  <img src="docs/screenshots/05-summary-language.png" width="190" alt="Summary language">
</p>
<p align="center"><i>The studio home (session list with live statuses) · live transcript with speakers · summary · summary-language picker</i></p>

## What you can do

**🎛️ Work like a studio**
- **The home screen is your session list** — every recording, with its live status: *Not processed · Queued · Processing (with phase and %) · Done*.
- **Record talks back-to-back** — a full-screen recording booth with a big timer, mic level bars, and two giant buttons: **⏭ Next talk** ends one session and instantly starts the next (its processing is deferred); **⏹ Stop & save** saves and processes in the background while you're free to record again.
- **Never lose a recording** — audio is saved to the library the moment the mic stops, even on a crash or an accidental stop; finished sessions embed their transcript + summary into a self-contained `.m4a` automatically.
- **Process on your schedule** — *Process pending (n)* transcribes, diarizes, summarizes and titles every saved recording in the background. Batches are processed **efficiently**: everything is transcribed first, then the summarizer loads **once** for the whole batch — and the queue survives app kills, resuming without redoing any finished work.
- **Manage your files** — tap or long-press any session: *Process now · Rename · Share audio · Delete* — plus *Remove from queue* on a queued session and *Stop processing* on the one being processed. Name a session while recording — your name always outranks the AI-generated title.

**🎙️ Bring in audio from anywhere**
- **A file** on your device — most common audio and video formats work.
- **Share from another app** — send a voice note or an audio/video file straight to VoxSum (from LINE, a recorder, your browser…) and it starts transcribing.
- **Record live** — watch the transcript appear sentence by sentence as you speak (a collapsible strip in the recording booth), with **mic level bars** so you know it hears you.
- **A podcast** — search, pick an episode, and transcribe it.
- **A YouTube link** — paste a URL, or search by keyword.
- **Reopen a saved session** — tap any *Done* session in the list and pick up exactly where you left off.

**📝 Read and understand**
- **Live transcript** — lines show up as soon as you speak; you can start reading (and playing) before it finishes.
- **Who spoke when** — each line is tagged and colour-coded by speaker, with an automatic speaker count. Precise speaker boundaries come from a neural segmenter (benchmarked at **95.6% / 92.1%** time-weighted attribution on the AMI and AISHELL-4 meeting corpora). VoxSum can even **guess speakers' real names** from what they say, and long passes show a live **time-to-finish estimate**.
- **A summary in your language, your way** — a short title and a **concise** summary (a handful of points, never a wall of text) as **bullets, an executive brief, or a narrative**. Keep it in the transcript's language, or pick **English · Français · 繁體中文 · 简体中文 · 日本語 · 한국어**. (It defaults to your phone's language.)
- **Action items & decisions** — pull a draft checklist of who-does-what and the key decisions out of a meeting, ready to edit.
- **Search the transcript** — find any word in a long recording; matches highlight and you can step through them.
- **A built-in player, in sync** — docked at the bottom like a music app: tap any line to jump there, and the current line highlights as it plays.
- **Easy on the eyes** — **Light**, **Dark**, or a flat high-contrast **E-ink** theme built for e-paper readers (Boox and the like). **Auto** — the default — follows your system's light/dark setting. Switch any time in **Settings → Appearance**.

**✏️ Make it yours**
- **Edit anything** — fix a word, rename a speaker, tweak the title or summary, right in place.
- **Fix the speakers** — move a misattributed line to the right person, or merge two speakers into one.
- **Copy** the whole summary with one tap.
- **Export the words** — one **Export & share…** sheet, grouped by what you get: the **VoxSum session** (`.m4a`), a **document** (**PDF**, **Markdown**, plain text) carrying the title, summary, action items and the timestamped transcript, or **subtitles** (`.srt`/`.vtt`/`.lrc`) with speaker labels. Everything can be **saved or shared**, and the transcript copies to the clipboard in one tap.
- **Re-run** the transcription, the summary, **just the speaker detection** (*Re-detect speakers*), or the speaker-name detection whenever you like — and VoxSum keeps everything consistent: change the summary language or style (or edit the transcript) and it offers a one-tap **re-summarize**, which also refreshes the title (unless you wrote your own). Switching just between **繁體中文 ↔ 简体中文** converts the title, summary, and transcript **instantly**, no re-run needed.
- **Save or share as one file** — the whole session (audio + transcript + summary + speakers + a cover) packs into a single **`.m4a`**. It **plays in any music app** — showing the title, cover, summary, and the **time-synced transcript** as [scrolling lyrics](#synced-lyrics-in-android-music-players) — and **reopens in VoxSum** with everything intact. `.m4a` reaches the widest set of players (iPhones, cars, every app); older `.ogg` sessions still open too.

## Languages

- **Transcription** handles English and Chinese out of the box; a multilingual engine (Chinese · English · Japanese · Korean · Cantonese) is one tap away in **Settings**.
- **Summaries** can be written in any of seven languages, or matched to the transcript.
- **The app itself** is available in **English, 繁體中文, and Français**.

## Install

The small AI models are **not** bundled — they download once on first use, then the app runs fully
offline. Two ways to install:

**Via F-Droid (recommended — automatic updates).** In your F-Droid client, add this repository
(**Settings → Repositories → ➕**), then install VoxSum from it:

```
https://vieenrose.github.io/VoxSumDroid/repo?fingerprint=c9fe46eb7d87d4fa4e2340a73f78a602eafbab655cbe7c7cb4ead5ab7a00b088
```

<img src="docs/screenshots/fdroid-repo-qr.png" width="150" alt="F-Droid repo QR"> &nbsp; *(or scan to add the repo)*

It's a self-hosted repository (not the official f-droid.org store), so adding it is a one-time step —
after that, updates arrive automatically.

**Sideload the APK.** Download the latest signed APK from the
[**Releases page**](https://github.com/vieenrose/VoxSumDroid/releases/latest) and open it to install
(Android may ask for permission to install from your browser or file manager).

## Good to know

- **First run downloads models.** The first time you use a feature, VoxSum fetches the model it needs
  from **Hugging Face** (with a GitHub fallback), verifies its integrity, and caches it. After that you
  can go fully offline. Downloads **resume where they left off** on flaky Wi-Fi, and a corrupt file is
  cleaned up automatically with a one-tap **Retry**.
- **Quiet audio just works.** Far-field or low-volume recordings get an automatic, clip-safe volume
  boost — for transcription, speaker detection, and playback alike.
- **While a transcription is running,** exports and settings are briefly locked so the session can't be
  saved half-finished — they unlock the moment it completes.
- **The only thing it ever sends** is an optional, once-a-day check to GitHub for a new version — no
  tracking, and skipped when you're offline. (F-Droid users get updates through their client instead.)
- **Runs on Android 8.0+.** A recent phone with a few GB of free storage is comfortable. The
  summarizer downloads two models totalling ~880 MiB the first time you summarize, and both stay
  resident while it runs — a full transcribe + summarize pass peaks around 2 GB of memory on a
  mid-range phone.

## Project status

**Current focus: the summarizer.** As of v0.42.0 VoxSum summarizes with an *agentic* pipeline
rather than a single prompt. The transcript streams past the model in ~2000-token chunks, and the
model edits **one evolving set of notes** through typed operations (add a bullet, revise a bullet,
delete one) instead of writing independent per-chunk digests that are merged at the end. The
practical difference: when a meeting reverses itself — a plan rejected at 12:00 and approved at
48:00 — the notes now *update* the earlier bullet instead of listing both.

Two models work together on-device, both Apache-2.0:

| model | role | size |
|---|---|---|
| MiniCPM5-1B-CURSOR | proposes the note edits | ~656 MiB |
| Granite-4.0-350m-verifier | checks every proposed decision/action against the transcript before it is accepted | ~226 MiB |

Everything the model proposes passes through deterministic checks written in Kotlin: timestamps
must point at a line the model actually saw, a bullet contradicting a later one is dropped,
duplicates are rejected, and section limits are applied by spreading across the meeting rather
than truncating it. The model never writes the notes file directly — the app renders it.

**What is verified.** Op-format correctness (zero malformed operations across English, Chinese and
a 62-minute real meeting, on x86 and on-device), timestamps always resolving to real transcript
lines, and the full pipeline running on a mid-range phone (Dimensity 900) inside ~2 GB peak memory.

**What is not, and you should know it.** Summary *coverage* is unmeasured: on real meetings the
model tends to write few notes, and may miss action items that were clearly stated. We also cannot
yet publish a trustworthy faithfulness figure — our own inversion detector ships in this release,
but the meetings we have are not a fair test set (they overlap the model's training data). Treat
the summary as a helpful first pass, not a record: the app says as much next to every summary, and
every bullet carries a timestamp so you can check it against the transcript in one tap.

## Synced lyrics in Android music players

An exported `.m4a` stores the title, cover, **summary** (in the *comment* tag) and the
**time-synced transcript** (in the *lyrics* tag, as LRC `[mm:ss.xx]` lines). Android players that parse
**synced lyrics** scroll the transcript **in real time** as it plays — read straight from the file,
**no sidecar and no permission**. (Players without sync support just show the text, with the `[mm:ss]`
timestamps visible.)

| App | Open the lyrics here | Real-time sync |
|---|---|---|
| **Retro Music** *(free)* | now-playing lyrics (歌詞) | ✅ |
| **Gramophone** *(free)* | the Lyrics view | ✅ |
| **Musicolet** *(free)* | tap the album cover → lyrics | ✅ |

<p align="center">
<img src="docs/screenshots/synced-retromusic.png" width="232" alt="Synced lyrics in Retro Music">
&nbsp;<img src="docs/screenshots/synced-gramophone.png" width="232" alt="Synced lyrics in Gramophone">
&nbsp;<img src="docs/screenshots/synced-musicolet.png" width="232" alt="Synced lyrics in Musicolet">
</p>

*Synced transcript scrolling on a Pixel — **Retro Music**, **Gramophone**, and **Musicolet** (the current line highlights as the audio plays).*

> **Notes.** All three verified on a Pixel — the current line highlights as it plays. The **summary**
> also lives in the standard **comment** tag (song info). A standalone **`.lrc` sidecar** export
> (**Export & share… → Subtitles → LRC**) is also available for players that prefer one.

## For developers

VoxSum is an on-device port of [VoxSum Studio](https://huggingface.co/spaces/Luigi/VoxSum-bak).
Every model runs locally, on one of two runtimes:

- **ASR, VAD and speaker diarization** — [LiteRT](https://ai.google.dev/edge/litert). The runtime
  ships as the official prebuilt from Google's Maven AAR; the engine around it
  (`app/src/main/cpp/mosslite`) is built from source (see `mosslite/PROVENANCE.md`).
- **Summarization** — [llama.cpp](https://github.com/ggml-org/llama.cpp), built from source out of
  the `native/llama.cpp` submodule, over a single GGUF.

sherpa-onnx and ONNX Runtime were removed in 2026-07. LiteRT-LM was removed in 2026-07 too: it had
been adopted for Gemma 4's speed, and once Gemma 4 was dropped nothing was left to pay its costs —
a context length baked into the bundle (so one export could not serve two window sizes), no
runtime KV quantization, no 3-bit weights, no SSM support, and published arm64 prebuilts that
SIGILL on ARMv8.0. llama.cpp answers each of those with a flag.

**The summarizer.** [Qwen3.5-0.8B Q4_K_M](https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF)
(~533 MB, commit-pinned and sha256-verified) — the same artifact the desktop build uses. It is a
hybrid-attention model (18 gated-delta linear-attention layers, 6 full-attention), so its KV cache
is small for its size; with a `q8_0` K/V cache the context can run up to 32768 tokens, about
160 minutes of speech in a single pass. `n_ctx` is sized per transcript
(`Summarizer.contextFor`), so a short meeting allocates a short window and decodes faster —
something the baked-in LiteRT bundles could not do.

The arm64 build is deliberately pinned to `-march=armv8-a`: the Cortex-A73/A72 targets have no
dotprod and no fp16 arithmetic extension, and anything above the baseline SIGILLs there. The
engine also pins itself to the big CPU cluster before the ggml thread pool is created —
placement, not clock, is what makes throughput on these devices bimodal.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the module map.

### Build from source

Requires Android Studio (Ladybug+), SDK 35, NDK 27.2. No submodules, no ONNX Runtime build:

```bash
git clone https://github.com/vieenrose/VoxSumDroid.git
cd VoxSumDroid
./gradlew :app:assembleDebug          # arm64-v8a by default
./gradlew :app:testDebugUnitTest      # JVM unit tests
scripts/test-on-device.sh             # instrumented suite on a connected device
```

`test-on-device.sh` installs under its own application id, so an installed release build — and its
sessions and models — is left untouched. Set `VOXSUM_SEED_MODELS` to a directory laid out like the
app's `files/models` to skip the multi-GB on-device download.

See [`SPIKE.md`](SPIKE.md) for the proven recipe and [`RELEASING.md`](RELEASING.md) for how tagging
`v*` produces a signed release APK via CI.

### ASR backend performance

**Nemotron was removed 2026-08-18** — this table is what justified it. It ran ~2× worse CER
than X-ASR on zh-TW and the app targets zh-TW meetings only, not the 25-language coverage
Nemotron traded accuracy for.

**MOSS-TD was also removed 2026-08-18 — from this Android app only.** A real transcription
on the OPPO CPH2371 reference device (Dimensity 900, 2 big + 6 little cores, all engaged —
there was no unused core to add) measured **RTF 4.43** on a 150 s zh-TW clip: decode ran at
2.10 tok/s, matching the engine's own "already tuned" figure, so this was the ceiling, not
an undertuned default. A 60-minute meeting at that rate takes ~4.4 hours — impractical
regardless of MOSS-TD's accuracy edge. Root cause: autoregressive decode reads the full
LM-head weight matrix every token, which is memory-bandwidth-bound, not compute-bound — more
cores cannot fix it. **MOSS-TD stays on the Linux desktop app**, where the same weights run
well under realtime (see that repo's README) with the best accuracy of any backend; this is a
phone-hardware limit, not a verdict on the model. Diarization survives via the separate
pyannote+CAM++ pipeline. The rows below predate removal and are kept as the record.

Measured on a **Boox Tab Mini C** (Snapdragon 662, 4×Cortex-A73 2.0 GHz + 4×A53 1.8 GHz, 3.7 GB
RAM, Android 11) over two **5-minute** clips — English and Taiwan-accented Mandarin — against
human references, CPU only, 4 threads, full production pipeline (`AsrFullBenchTest`, the same
engine classes the app runs). Error rates are normalized the standard way offline (Whisper
`EnglishTextNormalizer` for en; OpenCC script fold + digit→漢字 + CJK-only for zh). Reproduce with:

```bash
scripts/test-on-device.sh <serial> -- -e class studio.voxsum.AsrFullBenchTest -e bench 1
```

| backend | en WER | zh-TW CER | RTF en / zh | peak RssAnon |
|---|---:|---:|---:|---:|
| **MOSS-TD** | **4.2%** | **6.7%** | 8.7 / 10.5 | 1072 MB |
| **X-ASR** (Zipformer) | 8.6% | 11.5% | 0.29 / 2.27 | ~1.0 GB |
| **Nemotron** (q8) | 12.2% | 22.7% | 0.86 / 1.60 | 381 MB |

**The zh-TW column is held-out**, measured on FormosaSpeech clips from a 9-clip / 16.6-minute set
and scored as character CER after OpenCC s2t, a per-digit 漢字 fold via cn2an, and a CJK-only
filter. Across all 9 clips of that set the same run gives MOSS-TD 7.7, X-ASR 12.3, Nemotron 21.4.
Earlier versions of this table published zh numbers (MOSS-TD 10.6, X-ASR 14.5, Nemotron 17.5)
measured on 120 concatenated Common Voice 19.0 **test** utterances — in-domain for Nemotron, which
is itself a Common-Voice-zh-TW fine-tune, and so flattering to it. Those numbers are withdrawn;
**do not cite the old 17.5**. The en column is unchanged — it was measured on independent audio.

**RTF** is wall-clock ÷ audio duration; below 1.0 is faster than real time. **Peak RssAnon, not
total RSS**: model weights are mmap'd, so anonymous memory is what the app must keep resident and
what gets it killed — total RSS would overstate every row by roughly a gigabyte. X-ASR's ~1 GB
reflects running its bucketed encoder with the XNNPACK weight cache **off**, which is mandatory
for correctness: the cache keys packed weights by tensor data, so the four shared-weight encoder
signatures collide and the larger buckets decode to nothing.

**Accuracy vs speed:** MOSS-TD was in a different accuracy class (and the only backend that
diarized while it transcribed) but ran ~9–10× slower than real time on this SoC — later
confirmed at RTF 4.4-5.7× on the current OPPO reference device, hence its removal above.
X-ASR is the fast default. **Nemotron's case was language coverage — 25 languages — not
accuracy**: on Taiwanese Mandarin it was roughly 2× worse than X-ASR and 3× worse than
MOSS-TD, and it collapsed on classical text (44.8 CER on 三國演義 against MOSS-TD's 2.2). That
trade wasn't worth it for an app targeting zh-TW meetings, so it's gone; the row above uses
the q8 encoder that replaced the original q4-mix at the same 599 MB before removal.

**Prefill and generation apply only to MOSS-TD**, the one autoregressive backend
(per ~90 s window, zh clip):

| MOSS-TD phase | rate |
|---|---:|
| encoder | ~0.7× audio duration |
| prefill | ~18.8 tok/s |
| **generation** | **0.96 tok/s** |

Generation dominates (~75% of wall time) because autoregressive decode is a sequence of batch-1
matmuls, which XNNPACK — tuned for feed-forward throughput — handles poorly. Session peak
`rss_hwm` for MOSS stays ≈1.4 GB, safely inside this device's budget.

### Sample output (start of each clip)

**English** — reference: *“When you call someone who is thousands of miles away, you are using a
satellite. Now widely available throughout the archipelago, …”*

| backend | output |
|---|---|
| MOSS-TD | When you call someone who is thousands of miles away, you're using a satellite. Now widely available throughout the archipelago, … |
| X-ASR | When you call someone who is thousands of miles away. You're using a satellite. Now widely available throughout the Archipelago. |
| Nemotron | when you call someone who is thousands of miles away you're using a satellite now widely available throughout the archipelago |

**zh-TW** — reference: *「在家也可以刷卡 外交與全球性議題 我們的人口結構急速老化 新店端 則正確…」*

| backend | output |
|---|---|
| MOSS-TD | 在家也可以刷卡。外交與全球性議題。我們的人口結構急速老化。新店端。則正確的說明了… |
| X-ASR | 大家也可以刷卡。外交與全球性議題。我們的人口結構急速老化。心電端。則正確的說明了… |
| Nemotron | 這家也可以刷卡 外交與全球信議題 我們的人口結構急速老化 新電端 則正確的說明了… |

> Numbers are one clip per language on one device and move with thermal state; treat them as
> relative, not absolute. Two quiet-speech bugs were found with exactly this bench: one
> silently dropped MOSS-TD's final window (en WER 26.7% → 4.2% after the fix), and a stale
> GainNormalizer left the −29.5 dBFS en clip un-boosted so VAD-segmented backends lost
> utterance-initial words (x-asr en 14.7% → 8.6% once the adaptive gain landed). Reproduce
> this bench before trusting changes to the windowing, gain, or VAD code.

## License

[GPL-3.0-or-later](LICENSE). Bundled source dependencies retain their own licenses; the summarization
model is distributed under the [Gemma Terms](https://ai.google.dev/gemma/terms).
