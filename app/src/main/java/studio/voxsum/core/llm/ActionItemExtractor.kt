package studio.voxsum.core.llm

import studio.voxsum.core.models.ChatTemplate

/**
 * On-device action-item + decision extraction. Reuses the summarizer's CJK-safe chunking +
 * hierarchical map-reduce so a long meeting never overflows n_ctx (a single 4k-token prompt would
 * silently return nothing). Returns an editable bulleted draft.
 *
 * Honesty: a 2-4B local model WILL miss real items and occasionally invent owners/deadlines, so the
 * UI presents this as a draft to correct — never an authoritative record / audit trail.
 */
class ActionItemExtractor(
    private val llm: LlmEngine,
    private val template: ChatTemplate = ChatTemplate.CHATML,
    /** Human-readable target language; null = match the transcript. */
    private val targetLanguage: String? = null,
    /** Script post-conversion (OpenCC s2tw for Traditional Chinese); identity when not needed. */
    private val convert: (String) -> String = { it },
) {

    /** Blocking (runs native generate); call on a background dispatcher. [onProgress] reports the
     *  per-chunk map progress (0..1) for the UI bar. */
    fun extract(transcript: String, onProgress: (Float) -> Unit = {}): String {
        // Strengthened like Summarizer's clause: the weak " Write them in X." was ignored cross-lingually
        // (validation: action-items scored 17/35 vs summarize's 24/35 purely from this clause).
        val langClause = if (targetLanguage != null)
            " Write the ENTIRE output in $targetLanguage. The transcript may be in another language —" +
                " translate as you extract. Do not use any language other than $targetLanguage."
            else " Write them in the same language as the transcript."
        // Same CJK-safe char budget as Summarizer (~0.6 chars/token), reserving MAX_TOKENS for output.
        val budget = ((llm.nCtx - MAX_TOKENS - 96) * 3 / 5).coerceIn(512, 3500)
        val chunks = SummaryText.chunk(transcript, size = budget)

        val partials = ArrayList<String>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            val sb = StringBuilder()
            llm.generate(SummaryText.wrap(template, MAP_TEMPLATE.format(langClause, c)), maxTokens = MAX_TOKENS) { sb.append(it) }
            partials += sb.toString().trim()
            onProgress((i + 1f) / chunks.size)
        }

        // Fold in budget-sized groups until one prompt fits — never join all partials into one
        // over-n_ctx reduce prompt (which returns empty on a long meeting).
        var level: List<String> = partials
        while (level.size > 1 && level.joinToString("\n").length > budget) {
            val next = ArrayList<String>()
            for (group in SummaryText.groupPartials(level, budget)) {
                if (group.size == 1) { next += group[0]; continue }
                val sb = StringBuilder()
                llm.generate(SummaryText.wrap(template, REDUCE_TEMPLATE.format(langClause, group.joinToString("\n"))), maxTokens = MAX_TOKENS) { sb.append(it) }
                next += sb.toString().trim()
            }
            level = next
        }

        val finalSb = StringBuilder()
        if (level.size == 1) {
            finalSb.append(level[0])
        } else {
            llm.generate(SummaryText.wrap(template, REDUCE_TEMPLATE.format(langClause, level.joinToString("\n"))), maxTokens = MAX_TOKENS) { finalSb.append(it) }
        }
        return convert(SummaryText.cleanSummary(finalSb.toString()))
    }

    companion object {
        private const val MAX_TOKENS = 384
        // %s = language clause, %s = text.
        const val MAP_TEMPLATE =
            "From the transcript section below, list the concrete ACTION ITEMS (who needs to do what, " +
                "with any deadline) and any key DECISIONS made, as short bullet points.%s Output only the " +
                "bullets — no headings, no preamble. If there are none, output exactly \"-\".\n\n" +
                "Transcript:\n%s\n\nItems:"
        const val REDUCE_TEMPLATE =
            "Combine and de-duplicate the action items and decisions below into one short bullet list, " +
                "keeping who-does-what.%s Output only the bullets — no headings, no preamble.\n\n" +
                "Items:\n%s\n\nItems:"
    }
}
