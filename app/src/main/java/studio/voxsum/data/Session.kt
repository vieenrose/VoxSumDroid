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

/** Per-speaker color — mirrors src/diarization.py::get_speaker_color (and app.js). */
fun speakerColor(speaker: Int?): Long {
    if (speaker == null) return 0xFF607D8B
    val palette = longArrayOf(
        0xFF1E88E5, 0xFFE53935, 0xFF43A047, 0xFFFB8C00,
        0xFF8E24AA, 0xFF00ACC1, 0xFFFDD835, 0xFF6D4C41,
    )
    return palette[(speaker % palette.size + palette.size) % palette.size]
}
