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
) {
    /** True once a transcript exists — re-run/export/detect-names actions key off this rather
     *  than !running, matching Android's transcriptReady flag (summary can still be streaming). */
    val transcriptReady: Boolean get() = utterances.isNotEmpty()
}
