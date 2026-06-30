package studio.voxsum.core.llm

import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ChatTemplate
import studio.voxsum.data.SpeakerName

/**
 * LLM-based speaker-name detection — port of src/summarization.py::detect_speaker_names.
 * Groups utterances per speaker, asks the on-device LLM to infer a name, parses
 * NAME/CONFIDENCE/REASON, and (by default) keeps only high-confidence, non-"Unknown" names.
 * Returns overrides keyed by speaker id, reusing [SpeakerName].
 */
class SpeakerNamer(private val llm: LlmEngine, private val template: ChatTemplate) {

    fun detect(
        utterances: List<TranscriptEvent.Utterance>,
        keepOnlyHighConfidence: Boolean = true,
        onSpeakerDone: ((speaker: Int) -> Unit)? = null,
    ): Map<Int, SpeakerName> {
        val grouped = LinkedHashMap<Int, MutableList<String>>()
        for (u in utterances) {
            val s = u.speaker ?: continue
            if (u.text.isBlank()) continue
            grouped.getOrPut(s) { mutableListOf() }.add(u.text)
        }
        if (grouped.isEmpty()) return emptyMap()

        val out = LinkedHashMap<Int, SpeakerName>()
        for (speaker in grouped.keys.sorted()) {
            var combined = grouped.getValue(speaker).joinToString(" ")
            if (combined.length > 4000) combined = combined.substring(0, 4000) + "..."
            val sb = StringBuilder()
            // Use the model's template (QWEN3 appends the empty <think></think> non-thinking block) — a raw
            // CHATML open turn would put Qwen3.5 into REASONING mode and let a <think> trace eat the budget.
            llm.generate(SummaryText.wrap(template, SYSTEM_PROMPT + "\n\n" + USER_TEMPLATE.format(combined)), maxTokens = 100) {
                sb.append(it)
            }
            val parsed = parseSpeakerResponse(sb.toString())
            onSpeakerDone?.invoke(speaker)
            val accept = if (keepOnlyHighConfidence) {
                parsed.confidence == "high" && parsed.name != "Unknown"
            } else {
                parsed.name.isNotBlank() && parsed.name != "Unknown"
            }
            if (accept) out[speaker] = parsed
        }
        return out
    }

    /** Robust parser (port of L452-464, hardened for small on-device models). */
    internal fun parseSpeakerResponse(raw: String): SpeakerName {
        var name = "Unknown"
        var confidence = "low"
        var reason = "No clear identification found"
        val cleaned = SummaryText.stripThink(raw).replace("<|im_end|>", "").replace("<|im_start|>", "").trim()
        for (line in cleaned.lineSequence()) {
            val l = line.trim().trim('*').trim()
            when {
                l.startsWith("NAME:", true) ->
                    name = l.substring(5).trim().trim('*', '"', '\'', '[', ']').trim().ifBlank { "Unknown" }
                l.startsWith("CONFIDENCE:", true) ->
                    confidence = l.substring(11).trim().trim('*', '"', '\'').lowercase()
                        .substringBefore(' ').ifBlank { "low" }
                l.startsWith("REASON:", true) ->
                    reason = l.substring(7).trim().trim('*', '"', '\'').ifBlank { "No clear identification found" }
            }
        }
        if (confidence !in setOf("high", "medium", "low")) confidence = "low"
        return SpeakerName(name, confidence, reason)
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You are an expert at analyzing speech patterns and identifying speaker identities " +
                "from transcripts. Be precise and only suggest names when you have clear evidence. " +
                "IMPORTANT: You MUST respond in the EXACT SAME LANGUAGE as the input text. " +
                "Do not translate to English. Maintain the original language throughout."

        const val USER_TEMPLATE =
            "Analyze the following utterances from a single speaker and suggest a name for this " +
                "speaker. Look for:\n\n" +
                "1. Self-introductions or self-references\n" +
                "2. Names mentioned in context\n" +
                "3. Speech patterns, vocabulary, and topics that might indicate identity\n" +
                "4. Professional titles, roles, or relationships mentioned\n\n" +
                "Utterances from this speaker:\n%s\n\n" +
                "Provide your answer in this exact format:\n" +
                "NAME: [suggested name]\n" +
                "CONFIDENCE: [high/medium/low]\n" +
                "REASON: [brief explanation]\n\n" +
                "If confidence is \"low\", the name should not be used."
    }
}
