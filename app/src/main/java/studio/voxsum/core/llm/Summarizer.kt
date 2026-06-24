package studio.voxsum.core.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import studio.voxsum.core.events.TranscriptEvent
import studio.voxsum.core.models.ChatTemplate

/**
 * Map-reduce summarization — Android port of src/summarization.py::summarize_transcript.
 *
 * Python uses LangChain only for chunking + prompt templates and llama_cpp directly for
 * inference. On-device we drop LangChain entirely: chunking is a few lines of Kotlin and
 * prompts are string templates. Inference goes through [LlmEngine].
 *
 * Flow:
 *   1. split transcript into ~chunk-sized windows with overlap (RecursiveCharacterTextSplitter equiv)
 *   2. summarize each chunk -> emit Partial as tokens stream
 *   3. reduce the chunk summaries into one final summary -> emit SummaryComplete
 *   4. generate_title equivalent -> emit Title
 */
class Summarizer(
    private val llm: LlmEngine,
    private val template: ChatTemplate = ChatTemplate.CHATML,
    private val traditionalChinese: Boolean = false,
    /** Injected OpenCcConverter::convert when Traditional-Chinese output is on. */
    private val toTraditional: (String) -> String = { it },
) {

    fun summarize(transcript: String, userPrompt: String): Flow<TranscriptEvent> = flow {
        val chunks = chunk(transcript)
        emit(TranscriptEvent.Status("Summarizing ${chunks.size} chunk(s)…"))

        val partials = ArrayList<String>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            val sb = StringBuilder()
            llm.generate(wrap(MAP_TEMPLATE.format(userPrompt, c)), maxTokens = 256) { sb.append(it) }
            partials += sb.toString().trim()
            emit(TranscriptEvent.Partial(sb.toString().trim()))   // partials stay raw (intermediate)
            emit(TranscriptEvent.Progress((i + 1f) / chunks.size))
        }

        // If there was only one chunk, its summary IS the final summary — skip a redundant pass.
        val finalSb = StringBuilder()
        if (partials.size == 1) {
            finalSb.append(partials[0])
        } else {
            llm.generate(
                wrap(REDUCE_TEMPLATE.format(userPrompt, partials.joinToString("\n\n"))),
                maxTokens = 400,
            ) { finalSb.append(it) }
        }
        val finalSummary = maybeTw(cleanSummary(finalSb.toString()))
        emit(TranscriptEvent.SummaryComplete(finalSummary))

        val title = StringBuilder()
        llm.generate(wrap(TITLE_TEMPLATE.format(finalSummary)), maxTokens = 24) { title.append(it) }
        emit(TranscriptEvent.Title(maybeTw(cleanTitle(title.toString()))))
    }

    private fun maybeTw(s: String) = if (traditionalChinese) toTraditional(s) else s

    /**
     * Extract a single clean title from the model's reply. Verbose models (e.g. Gemma 4) answer
     * with "Here are a few options:" then a numbered list, so skip preamble/header lines, take
     * the first real candidate, and strip list numbering, markdown, quotes, and "Title:".
     */
    private fun cleanTitle(raw: String): String {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
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
    private fun cleanSummary(raw: String): String {
        val lines = raw.trim().lines().toMutableList()
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
    private fun wrap(user: String): String = when (template) {
        ChatTemplate.CHATML -> "<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        ChatTemplate.GEMMA -> "<start_of_turn>user\n$user<end_of_turn>\n<start_of_turn>model\n"
        // Gemma 4 uses a different turn format (per its chat_template.jinja): a plain user
        // turn with no system/thinking block. <bos> is auto-added by the tokenizer.
        ChatTemplate.GEMMA4 -> "<|turn>user\n$user<turn|>\n<|turn>model\n"
    }

    /** Naive char-window chunker; replace with a sentence-aware splitter in Phase 2. */
    private fun chunk(text: String, size: Int = 3500, overlap: Int = 300): List<String> {
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

    private companion object {
        // Directive prompts: one concise bullet-point summary, no multiple versions / section
        // headers / preamble (verbose models like Gemma 4 otherwise emit "Short Summary:",
        // "Detailed Summary:", etc.). First %s = the user's instruction, second %s = the text.
        const val MAP_TEMPLATE =
            "%s\nWrite the summary of the transcript section below as a few short bullet points. " +
                "Output only the bullet points — no headings, no multiple versions, no preamble.\n\n" +
                "Transcript:\n%s\n\nSummary:"
        const val REDUCE_TEMPLATE =
            "%s\nCombine the partial summaries below into ONE concise summary of a few short bullet " +
                "points. Output only the bullet points — no headings, no multiple versions, no preamble.\n\n" +
                "Partial summaries:\n%s\n\nSummary:"
        const val TITLE_TEMPLATE =
            "Write ONE short title (at most 8 words) for the summary below. " +
                "Output only the title text — no quotes, no list, no preamble.\n\nSummary:\n%s\n\nTitle:"
    }
}
