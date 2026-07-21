package studio.voxsum.core.asr.moss

/**
 * Data model for the MOSS-TD windowed pipeline. Port of the validated reference
 * implementation (`windowing.py` in the HF Space `Luigi/moss-transcribe-diarize-cpp`,
 * itself extracted from `scripts/85_window_sweep.py` in vieenrose/distil-vibevoice-asr),
 * so Android (JNI) and desktop share identical windowing / speaker-linking logic —
 * only the per-window "decode this PCM" and "embed this pooled audio" calls differ
 * per platform and are injected as lambdas into [MossPipeline].
 *
 * The model emits, per window, a stream of `[start][Sxx]text` markers (`[Sxx]` may be
 * omitted — the previous tag carries forward). All times here are in SECONDS; a
 * segment's `win` is the index of the window it came from, used as the cannot-link
 * key in speaker linking (two tags co-occurring in one window are different people).
 */

const val MOSS_SR = 16000

/** One segment parsed out of a single window's decode output (window-local times,
 *  effective tag — the last seen `[Sxx]` carried forward). */
data class MossRawSeg(
    val start: Double,
    val rawEnd: Double?,   // the model's own closing timestamp, when present
    val spk: String,       // effective local tag "S01".."Sxx"
    val text: String,
)

/** A segment mapped to absolute (whole-recording) time. `win`+`spk` key its linking unit. */
data class MossWindowSeg(
    val win: Int,
    val start: Double,
    val end: Double,
    val rawEnd: Double?,
    val spk: String,       // window-local tag
    val text: String,
)

/** One (window, local-tag) linking unit: pooled-audio embedding + pooled duration. */
data class MossUnit(
    val win: Int,
    val tag: String,
    val emb: FloatArray?,   // 192-d CAM++ embedding of up to 30 s pooled audio, or null
    val durS: Double,       // total pooled duration in seconds
)

/** Final linked segment: absolute times, a 0-based speaker cluster id, post-processed text. */
data class MossLinkedSeg(
    val start: Double,
    val end: Double,
    /** 0-based cluster id, canonical by first appearance across the whole recording. */
    val speaker: Int,
    val text: String,
)
