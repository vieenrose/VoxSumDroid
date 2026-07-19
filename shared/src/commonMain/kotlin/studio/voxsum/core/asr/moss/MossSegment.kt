package studio.voxsum.core.asr.moss

/**
 * Data model for the MOSS-TD windowed pipeline. Ported from the reference
 * implementation in RapidSpeech.cpp (`wasm-examples/moss/app-wasm.js` +
 * `moss-worker.js`, branch `integrate-upstream`) so Android (JNI) and desktop
 * (subprocess) share identical windowing / loop-collapse / speaker-linking
 * logic — only the per-window "decode this PCM" and "embed these ranges" calls
 * differ per platform and are injected as lambdas into [MossPipeline].
 *
 * The model emits, per window, lines of the shape `[start][Sxx]text[end]`
 * (speaker-tagged, timestamped, zh-TW/en code-switched). All times here are in
 * SECONDS; a segment's `win` carries the window-start offset it came from, used
 * as the cannot-link key in [linkSpeakers].
 */

const val MOSS_SR = 16000

/** One raw segment parsed out of a single window's decode output (window-local times). */
data class MossRawSeg(
    val start: Double,
    val rawEnd: Double?,   // the model's own closing timestamp, when present
    val spk: String,       // "S01".."Sxx" or "" when the segment omitted its tag
    val text: String,
)

/** Result of parsing one window: raw segments + their computed ends and sample ranges. */
data class MossParsedWindow(
    val segs: List<MossRawSeg>,
    /** end time (s) per segment — rawEnd if valid, else next.start, else start+3 (clamped to durS). */
    val ends: List<Double>,
    /** [startSample, endSample) per segment for CAM++ speaker embedding. */
    val ranges: List<IntRange>,
    /** true when the window returned no usable text over >2 s of audio (triggers a retry/skip upstream). */
    val failed: Boolean,
)

/**
 * A segment mapped to absolute (whole-recording) time with its speaker embedding
 * attached. Mutable because the boundary-re-advance and marker-less-fallback
 * stages rewrite start/end/rawEnd in place, matching the JS reference.
 *
 * Not a `data class`: it carries a FloatArray (`emb`) and is mutated after
 * construction, both of which make value semantics misleading.
 */
class MossWindowSeg(
    val win: Double,
    var start: Double,
    var end: Double?,
    var rawEnd: Double?,
    val spk: String,
    val text: String,
    val emb: FloatArray?,   // 192-d CAM++ embedding, or null when the span was too short to embed
    var tsEstimated: Boolean = false,
)

/** Final linked segment: absolute times, a 0-based speaker cluster id, post-processed text. */
data class MossLinkedSeg(
    val start: Double,
    val end: Double,
    /** 0-based cluster id, canonical by first appearance across the whole recording. */
    val speaker: Int,
    val text: String,
)
