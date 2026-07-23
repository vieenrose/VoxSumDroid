package studio.voxsum.core.llm

import studio.voxsum.core.models.ChatTemplate

/**
 * Pure text-shaping for summarization, split out of [Summarizer] (which is bound to the native
 * the engine class). These helpers run on raw model output
 * and produce the user-facing title + summary, so their edge cases matter; keeping them here, free of
 * any native reference, lets them be unit-tested on the JVM (see SummarizerTextTest).
 */
internal object SummaryText {

    /**
     * Drop a <think>…</think> reasoning block a thinking-capable model (e.g. Qwen3.5) might emit. Handles
     * BOTH a normal closed block AND an UNTERMINATED <think> (runaway reasoning that hit the token cap
     * before closing) — the latter is dropped along with everything after it, so a half-emitted trace
     * never leaks into the title/summary (the literal "<think" leak seen when reasoning ran away).
     */
    fun stripThink(s: String): String {
        var t = s.replace(Regex("(?s)<think>.*?</think>"), "")   // complete blocks, anywhere
        val open = t.indexOf("<think>")                          // an unterminated opener, if any
        if (open >= 0) t = t.substring(0, open)
        return t.trim()
    }

    /**
     * Extract a single clean title from the model's reply. Verbose models (e.g. Gemma 4) answer
     * with "Here are a few options:" then a numbered list, so skip preamble/header lines, take
     * the first real candidate, and strip list numbering, markdown, quotes, and "Title:".
     */
    fun cleanTitle(raw: String): String {
        val lines = stripThink(raw).lines().map { it.trim() }.filter { it.isNotBlank() }
        val candidate = lines.firstOrNull { line ->
            !line.endsWith(":") &&
                !line.matches(Regex("(?i)^(here|sure|okay|ok|option|options|below|these|certainly).*"))
        } ?: lines.firstOrNull().orEmpty()
        return candidate
            .replace(Regex("^\\s*\\d+[.)]\\s*"), "")   // "1. " / "1) " numbering
            .replace(Regex("^\\s*[-*•]\\s*"), "")        // leading bullet
            .replace(Regex("[*_`#>]"), "")               // markdown emphasis
            .replace(Regex("(?i)^\\s*title\\s*:\\s*"), "")
            .trim()
            .trim('"', '\'', '“', '”', '«', '»', ' ', '.', ':')
    }

    /**
     * A final summary that clearly overran its style's intent (an hour-long meeting's reduce can
     * fill the whole token budget with ~30 bullets). Triggers the Summarizer's one-shot shrink
     * pass. Thresholds sit well above every style's asked-for size (≤7 bullets / ≤6 sentences)
     * in both English and CJK, so a compliant summary never pays for a second pass.
     */
    fun tooLong(summary: String): Boolean =
        summary.lines().count { it.isNotBlank() } > 12 || summary.length > 1200

    /**
     * Plain-text cleanup of a model summary: drop a conversational lead-in ("Here's a
     * summary…:"), unwrap bold/italic/code spans, strip heading marks, and normalize list
     * bullets — Compose renders raw text, so leftover markdown shows as literal asterisks.
     */
    fun cleanSummary(raw: String): String {
        val lines = stripThink(raw).lines().toMutableList()
        // Drop a leading conversational lead-in / header (e.g. "Here's a summary…:",
        // "Key points:"). Any first line ending in a colon is a preamble, not content —
        // robust to curly vs straight apostrophes. Both the ASCII ":" and the CJK fullwidth
        // "：" (U+FF1A) count: a CJK-target summary leads with the fullwidth form (Gemma 4
        // emits "繁體中文摘要："), which the ASCII-only check let through into the summary.
        // Then drop a now-leading blank line.
        if (lines.size > 1 && lines.first().trim().let { it.endsWith(":") || it.endsWith("：") }) {
            lines.removeAt(0)
            while (lines.size > 1 && lines.first().isBlank()) lines.removeAt(0)
        }
        return lines.joinToString("\n") { l ->
            l.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")      // **bold** -> bold
                .replace(Regex("(?<![*\\w])\\*(?![*\\s])(.+?)\\*"), "$1") // *italic* -> italic
                .replace("`", "")
                .replace(Regex("^\\s{0,3}#{1,6}\\s*"), "")    // ## heading -> text
                .replace(Regex("^(\\s*)[*\\-–•]\\s+"), "$1• ") // bullets -> "• "
        }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** Wrap a user instruction in the model's chat template so it behaves and stops at its EOG. */
    fun wrap(template: ChatTemplate, user: String): String = when (template) {
        ChatTemplate.CHATML -> "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        ChatTemplate.GEMMA -> "<start_of_turn>user\n$user<end_of_turn>\n<start_of_turn>model\n"
        // Gemma 4 uses a different turn format (per its chat_template.jinja): a plain user
        // turn with no system/thinking block. <bos> is auto-added by the tokenizer.
        ChatTemplate.GEMMA4 -> "<|turn>user\n$user<turn|>\n<|turn>model\n"
        // Qwen3/Qwen3.5 ChatML. Append the empty <think></think> block their template emits for
        // non-thinking mode, so the model answers directly (a summary, not a reasoning trace).
        ChatTemplate.QWEN3 -> "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        // The runtime applies the bundle's own template (LiteRT-LM) — pass the prompt through.
        ChatTemplate.NONE -> user
    }

    /**
     * Greedily pack consecutive partial summaries into groups whose joined length (with "\n\n"
     * separators) stays within [budgetChars], so a hierarchical reduce never builds a prompt that
     * overflows the LLM context window. A single partial larger than the budget still gets its own
     * group (it can't be split here); order and completeness are always preserved.
     *
     * NOTE: this does NOT guarantee the group count strictly shrinks — if every partial is itself
     * near/over budget, every group is a singleton and the count is unchanged. Do NOT fold in a raw
     * loop over this; use [foldToFit], which owns the no-progress break.
     */
    fun groupPartials(partials: List<String>, budgetChars: Int): List<List<String>> {
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var curLen = 0
        for (p in partials) {
            val sep = if (cur.isEmpty()) 0 else 2          // "\n\n"
            if (cur.isNotEmpty() && curLen + sep + p.length > budgetChars) {
                groups.add(cur); cur = ArrayList(); curLen = 0
            }
            curLen += (if (cur.isEmpty()) 0 else 2) + p.length
            cur.add(p)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    /**
     * Hierarchically fold [partials] in budget-sized groups — re-reducing each group of ≥2 via
     * [reduceGroup] — until the whole set joins under [budgetChars] (joined with [separator]) or no
     * further folding is possible. Returns the final level (usually one string; possibly a few
     * unmergeable oversized partials the caller reduces in one final pass).
     *
     * OWNS the termination guarantee that [groupPartials] can't provide: when every partial is
     * itself near/over budget, groupPartials returns all singletons, nothing folds, and this breaks
     * — so callers never spin at 100% CPU (the map-reduce hang this replaced). `inline` so a
     * suspending [reduceGroup] (e.g. emitting progress from a flow) works without forcing suspend.
     */
    inline fun foldToFit(
        partials: List<String>,
        budgetChars: Int,
        separator: String,
        reduceGroup: (List<String>) -> String,
    ): List<String> {
        var level = partials
        while (level.size > 1 && level.joinToString(separator).length > budgetChars) {
            var folded = false
            val next = ArrayList<String>()
            for (group in groupPartials(level, budgetChars)) {
                if (group.size == 1) { next += group[0]; continue }
                folded = true
                next += reduceGroup(group)
            }
            level = next
            if (!folded) break
        }
        return level
    }

    /** Naive char-window chunker; replace with a sentence-aware splitter in Phase 2. */
    fun chunk(text: String, size: Int = 3500, overlap: Int = 300): List<String> {
        // Defensive: clamp the params so the window ALWAYS advances. With overlap >= size (or size <= 0)
        // `start = end - overlap` would stall or move backward — an infinite loop that OOMs. Production
        // calls pass safe values (3500/300); this just makes the function total for any input.
        val sz = size.coerceAtLeast(1)
        val ov = overlap.coerceIn(0, sz - 1)
        if (text.length <= sz) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + sz, text.length)
            out += text.substring(start, end)
            if (end == text.length) break
            start = end - ov
        }
        return out
    }
}
