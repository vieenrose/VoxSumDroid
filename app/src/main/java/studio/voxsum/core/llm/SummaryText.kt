package studio.voxsum.core.llm

import studio.voxsum.core.models.ChatTemplate

/**
 * Pure text-shaping for summarization, split out of [Summarizer] (which is bound to the native
 * [LlmEngine], so touching its class loads the llama.cpp lib). These helpers run on raw model output
 * and produce the user-facing title + summary, so their edge cases matter; keeping them here, free of
 * any native reference, lets them be unit-tested on the JVM (see SummarizerTextTest).
 */
internal object SummaryText {

    /** Drop any <think>…</think> reasoning block a thinking-capable model (e.g. Qwen3.5) might emit. */
    fun stripThink(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

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
     * Plain-text cleanup of a model summary: drop a conversational lead-in ("Here's a
     * summary…:"), unwrap bold/italic/code spans, strip heading marks, and normalize list
     * bullets — Compose renders raw text, so leftover markdown shows as literal asterisks.
     */
    fun cleanSummary(raw: String): String {
        val lines = stripThink(raw).lines().toMutableList()
        // Drop a leading conversational lead-in / header (e.g. "Here's a summary…:",
        // "Key points:"). Any first line ending in a colon is a preamble, not content —
        // robust to curly vs straight apostrophes. Then drop a now-leading blank line.
        if (lines.size > 1 && lines.first().trim().endsWith(":")) {
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
    }

    /** Naive char-window chunker; replace with a sentence-aware splitter in Phase 2. */
    fun chunk(text: String, size: Int = 3500, overlap: Int = 300): List<String> {
        if (text.length <= size) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + size, text.length)
            out += text.substring(start, end)
            if (end == text.length) break
            start = end - overlap
        }
        return out
    }
}
