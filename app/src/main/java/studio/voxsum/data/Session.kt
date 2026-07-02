package studio.voxsum.data

import android.net.Uri
import studio.voxsum.core.events.TranscriptEvent

/**
 * In-memory session — the Android equivalent of the single global `state` object in
 * frontend/app.js. Holds the current audio source, decoded utterances, diarization
 * result, and summary. Loading a new audio source resets it (same rule as the web app).
 */
data class Session(
    val audioUri: Uri? = null,
    val title: String? = null,
    val utterances: List<TranscriptEvent.Utterance> = emptyList(),
    val speakerCount: Int? = null,
    val summary: String? = null,
    val asrModelId: String? = null,
    val llmModelId: String? = null,
)

// SpeakerName/SpeakerNames/speakerLabel/speakerColor moved to :shared (data/SpeakerName.kt) —
// pure, no Uri dependency, shared with :desktop. Same package, so no import changes needed here.
