package studio.voxsum.core.llm

import studio.voxsum.core.events.TranscriptEvent

/**
 * The ONE transcript format every ASR backend feeds the LLM summarizer — and the
 * target format for summarizer fine-tuning, so a tuned model sees at inference
 * exactly what it saw in training:
 *
 *     [M:SS] S1: utterance text
 *     [M:SS] Alice: utterance text     (when the speaker's name is known)
 *     [M:SS] utterance text            (when diarization did not run)
 *
 * Start time only (end times cost tokens and add nothing a summary needs),
 * speakers by order of first appearance, one utterance per line so the
 * map-reduce chunker never splits inside a record, no header or fencing —
 * small models echo scaffolding back.
 */
object TranscriptFormat {

    fun format(
        utterances: List<TranscriptEvent.Utterance>,
        speakerNames: Map<Int, String> = emptyMap(),
    ): String {
        // Stable S-numbers by order of first appearance, independent of the
        // diarizer's internal cluster ids.
        val order = LinkedHashMap<Int, Int>()
        for (u in utterances) {
            val s = u.speaker ?: continue
            order.getOrPut(s) { order.size + 1 }
        }
        return utterances.joinToString("\n") { u ->
            val t = stamp(u.startSec)
            val who = u.speaker?.let { speakerNames[it]?.takeIf(String::isNotBlank) ?: "S${order[it]}" }
            if (who != null) "[$t] $who: ${u.text}" else "[$t] ${u.text}"
        }
    }

    private fun stamp(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }
}
