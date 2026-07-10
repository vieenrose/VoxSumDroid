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

/**
 * Speaker-name override — keyed by speaker id, kept separate from the per-utterance `speaker`
 * field (mirrors the web app's `state.speakerNames`). Survives the Complete re-render and is
 * the single source for display labels. confidence: "user" (edited) | "high"/"medium" (LLM).
 */
data class SpeakerName(
    val name: String,
    val confidence: String = "user",
    val reason: String = "User edited",
)

typealias SpeakerNames = Map<Int, SpeakerName>

/** The one label resolver used by transcript rows, timeline, and stats. */
fun speakerLabel(speakerId: Int?, names: SpeakerNames): String? =
    speakerId?.let { names[it]?.name ?: "Speaker ${it + 1}" }

/**
 * Per-speaker color palette. The first 10 entries are the EXACT port of
 * src/diarization.py::SPEAKER_COLORS (get_speaker_color wraps speaker_id % 10); entries
 * 10–29 extend it so >10 speakers never collide (the web app reuses colors past 10 — this
 * is an intentional improvement). ARGB Long, opaque.
 */
private val SPEAKER_PALETTE = longArrayOf(
    0xFFFF6B6B, 0xFF4ECDC4, 0xFF45B7D1, 0xFF96CEB4, 0xFFFFEAA7,
    0xFFDDA0DD, 0xFFFFB347, 0xFF87CEEB, 0xFFF0E68C, 0xFFFF69B4,
    0xFFB39DDB, 0xFF80CBC4, 0xFFFFAB91, 0xFFA5D6A7, 0xFF9FA8DA,
    0xFFFFCC80, 0xFF90CAF9, 0xFFCE93D8, 0xFFEF9A9A, 0xFFC5E1A5,
    0xFFFFE082, 0xFF80DEEA, 0xFFBCAAA4, 0xFFE6EE9C, 0xFFF48FB1,
    0xFF81D4FA, 0xFFDCE775, 0xFFFFD54F, 0xFF4DD0E1, 0xFFAED581,
)

/** Per-speaker color — mirrors src/diarization.py::get_speaker_color. Canonical palette; used as-is
 *  for the cover-art fingerprint so it stays stable across themes. UI should prefer [speakerColorOn]. */
fun speakerColor(speaker: Int?): Long {
    if (speaker == null) return 0xFF607D8B
    val n = SPEAKER_PALETTE.size
    return SPEAKER_PALETTE[((speaker % n) + n) % n]
}

/**
 * Speaker color adjusted for the current theme. The base palette is bright pastels tuned for the
 * DARK theme; on light or e-ink (white) grounds those wash out — pale-on-white is illegible. Darken
 * them to ~55% for the light themes; since e-ink renders color as grey levels, a darker color is a
 * darker, higher-contrast grey, so the same transform serves both non-dark cases.
 */
fun speakerColorOn(speaker: Int?, darkTheme: Boolean): Long {
    val c = speakerColor(speaker)
    if (darkTheme) return c
    val r = (((c ushr 16) and 0xFF) * 55 / 100)
    val g = (((c ushr 8) and 0xFF) * 55 / 100)
    val b = ((c and 0xFF) * 55 / 100)
    return 0xFF000000L or (r shl 16) or (g shl 8) or b
}
