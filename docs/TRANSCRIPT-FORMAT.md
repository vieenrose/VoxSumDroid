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

## Fine-tune guidance — the SINGLE-PASS contract

The summarizer fine-tune is **one task**:

    input : the whole formatted transcript (this format, up to ~13k tokens)
    output: the summary (title as a small secondary task)

Rationale: Gemma 4's hybrid attention makes 16k context cost only ~+84 MB of
KV, and a single pass does strictly LESS total compute than map-reduce over
the same transcript (map prefills every token once anyway, then reduce adds
passes). The runtime routes any transcript that fits into a single pass;
map-reduce survives only as the overflow path for multi-hour meetings.

Overflow handling reuses the SAME trained ability recursively: summarize the
halves, then single-pass over the concatenated summaries. No separately
trained map/reduce/shrink heads.

- Generate training inputs with `TranscriptFormat.format()` over real
  `Utterance` lists; do not hand-write examples in a "similar" format.
- Include all three variants (S-tags, names, no-speaker) and both languages.
- Salt ~5-10% of examples whose "transcript" is two concatenated summaries —
  the recursive overflow case.
- Keep summaries free of timestamps/tags unless the task explicitly asks for
  moment references — the runtime prompts say "output only the summary".

Validated 2026-07-29 against Gemma 4 E2B/E4B (transformers, GPU) with VoxSum's
production prompt templates on real 5-minute en/zh transcripts.

Map-reduce lab (same date, E2B, 5-min en transcript, 4 arms): current
char-chunks, line-aware chunks, line-aware + synthesis-style reduce, and
iterative refine all produce comparable finals; refine was fastest (34 s vs
58-72 s for 8-chunk map-reduce) and the only arm that kept every topic. All
arms truncate the final list at the output-token cap — the reduce step, not
the map prompts, is the bottleneck. Conclusion: fine-tune for **single-pass**
(nCtx 16384 covers ~80 min of zh speech at ~195 tok/min); when a transcript
still overflows, prefer iterative refine over map-reduce as the fallback.
