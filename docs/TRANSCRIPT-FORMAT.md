# Unified summarizer transcript format (v1)

The single interface between every ASR backend and the LLM summarizer, and the
**target format for summarizer fine-tuning** — training data and runtime input
are produced by the same `TranscriptFormat.format()` call, so they can never
drift apart.

## Shape

    [M:SS] S1: utterance text
    [M:SS] S2: utterance text
    [1:23:45] Alice: utterance text

One utterance per line:

    [<start>] <speaker>: <text>      diarized, name unknown  → S1, S2, …
    [<start>] <name>: <text>         diarized, name known    → real name
    [<start>] <text>                 no diarization          → no speaker field

## Rules

- **Timestamp** — start time only. `M:SS` under one hour, `H:MM:SS` from 1 h.
  Zero-padded seconds/minutes-in-hour, no padding on the leading unit. End
  times are deliberately omitted: they cost tokens and add nothing a summary
  needs.
- **Speaker tags** — `S1…Sn` numbered by order of **first appearance**,
  independent of the diarizer's internal cluster ids, so the same conversation
  always serializes the same way. A known display name replaces the tag
  verbatim (no quoting).
- **One utterance = one line.** The map-reduce chunker splits on line
  boundaries; a record is never cut in half.
- **No header, no footer, no markdown fences.** Small models echo scaffolding
  back into summaries.
- Text is emitted as the ASR produced it (post script-conversion); no escaping.
  `[`, `]`, `:` inside utterance text are allowed — parsers must split on the
  FIRST `] ` and the first `: ` after it, not the last.

## Reference implementation

`shared/src/jvmMain/kotlin/studio/voxsum/core/llm/TranscriptFormat.kt` (Linux)
and `app/src/main/java/studio/voxsum/core/llm/TranscriptFormat.kt` (Android) —
keep byte-identical.

## Fine-tune guidance

- Generate training inputs with `TranscriptFormat.format()` over real
  `Utterance` lists; do not hand-write examples in a "similar" format.
- Include all three variants (S-tags, names, no-speaker) in training data.
- Keep summaries free of timestamps/tags unless the task explicitly asks for
  moment references — the runtime prompts say "output only the summary".

Validated 2026-07-29 against Gemma 4 E2B/E4B (transformers, GPU) with VoxSum's
production prompt templates on real 5-minute en/zh transcripts.
