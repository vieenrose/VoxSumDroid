package studio.voxsum.core.events

/**
 * On-device equivalent of VoxSum's NDJSON streaming contract.
 *
 * The Python server emits typed JSON lines (ready / status / utterance / progress /
 * complete for transcription; title / partial / complete for summarization) over a
 * StreamingResponse. Here there is no HTTP — the pipeline runs in a foreground service
 * and emits these as a Kotlin Flow that Compose collects, rendering utterances
 * incrementally exactly like frontend/app.js::handleTranscriptionEvent.
 */
sealed interface TranscriptEvent {
    /** Models loaded, pipeline ready (mirrors "ready"). */
    data object Ready : TranscriptEvent

    /** Human-readable stage label (mirrors "status"). */
    data class Status(val message: String) : TranscriptEvent

    /** One newly decoded utterance — appended, never a full rebuild (mirrors "utterance"). */
    data class Utterance(
        val index: Int,
        val text: String,
        val startSec: Double,
        val endSec: Double,
        val speaker: Int? = null,   // filled by diarization pass
        // Per-token text + timestamps (seconds, relative to startSec) from the ASR result.
        // Used by diarization to split an utterance that bundles two speakers at the token
        // whose time crosses the speaker-change boundary. Null when the backend omits them.
        val tokens: List<String>? = null,
        val tokenTimes: List<Double>? = null,
    ) : TranscriptEvent

    /** 0.0..1.0 progress over the audio (mirrors "progress"). */
    data class Progress(val fraction: Float) : TranscriptEvent

    /** Terminal transcription event: all utterances + optional diarization stats. */
    data class Complete(
        val utterances: List<Utterance>,
        val speakerCount: Int? = null,
    ) : TranscriptEvent

    // --- summarization side of the contract ---
    data class Title(val title: String) : TranscriptEvent
    data class Partial(val chunk: String) : TranscriptEvent
    data class SummaryComplete(val summary: String) : TranscriptEvent

    /** Live recording finished and the captured WAV was written — the UI loads it for playback. */
    data class RecordingSaved(val uri: String) : TranscriptEvent

    /**
     * A session export (run in the foreground service so it survives the app closing) finished.
     * [share] = built for sharing ([sharePath] set) vs saved to a SAF target; [outcome] is
     * FULL / PARTIAL / FAILED (matches SaveOutcome).
     */
    data class ExportDone(val share: Boolean, val outcome: String, val sharePath: String = "") : TranscriptEvent

    data class Failed(val error: String) : TranscriptEvent
}
