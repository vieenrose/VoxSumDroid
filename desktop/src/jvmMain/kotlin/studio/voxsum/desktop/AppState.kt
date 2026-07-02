package studio.voxsum.desktop

import studio.voxsum.core.config.TranscriptionConfig
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.data.SpeakerNames
import java.io.File

/** Everything the UI renders. A plain immutable data class updated via functional `copy` from
 *  background coroutines — same pattern app/MainActivity.kt uses with Compose state, just without
 *  the Activity lifecycle. */
data class AppState(
    val audioFile: File? = null,
    val fileName: String = "",
    val status: String = "",
    val progress: Float? = null,
    val running: Boolean = false,
    val utterances: List<TranscriptEvent.Utterance> = emptyList(),
    val speakerNames: SpeakerNames = emptyMap(),
    val speakerCount: Int = 0,
    val title: String = "",
    val summary: String = "",
    val actionItems: String = "",
    val error: String? = null,
    val config: TranscriptionConfig = TranscriptionConfig(),
    val summaryStyle: SummaryStyle = SummaryStyle.BULLET,
    val searchQuery: String = "",
    val editingUtteranceIndex: Int? = null,
    val editingSpeakerId: Int? = null,
    val editingTitle: Boolean = false,
    val editingSummary: Boolean = false,
    val editingActions: Boolean = false,
    // Dependency-invalidation tree (mirrors Android): a hand-edit to the transcript marks the
    // summary/action-items stale so the UI can offer a re-summarize; titleEdited is sticky so a
    // re-summarize won't clobber a title the user typed. summaryStale = a summary-shaping setting
    // (target language / style / model / prompt) changed; transcribeStale = a recognition setting
    // (backend / language / ITN / VAD / diarization) changed, so the transcript itself is stale
    // and a re-transcribe refreshes the whole tree.
    val transcriptDirty: Boolean = false,
    val summaryStale: Boolean = false,
    val transcribeStale: Boolean = false,
    val titleEdited: Boolean = false,
) {
    /** True once a transcript exists — re-run/export/detect-names actions key off this rather
     *  than !running, matching Android's transcriptReady flag (summary can still be streaming). */
    val transcriptReady: Boolean get() = utterances.isNotEmpty()
}
