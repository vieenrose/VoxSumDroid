package studio.voxsum.core.config

import studio.voxsum.R

/**
 * Format of the generated summary — the "Summary style" picker. Orthogonal to [TargetLanguage]
 * (which language) and the user's [TranscriptionConfig.summaryPrompt] (what to focus on): this is
 * HOW the summary reads. Each style supplies the map + reduce format instruction and a token budget
 * (flowing prose needs more output room than terse bullets).
 *
 * Previously the map/reduce templates hard-coded "a few short bullet points", which silently
 * overrode any format the user asked for — so narrative/executive output was impossible. Making the
 * style a first-class enum fixes that. On a small on-device model the reliably-distinct trio is
 * bullets / executive / narrative.
 */
enum class SummaryStyle(
    val id: String,
    val labelRes: Int,
    /** Inserted into the per-chunk (map) prompt: "Write the summary … <mapInstruction>." */
    val mapInstruction: String,
    /** Inserted into the combine (reduce) prompt: "Combine the partial summaries … <reduceInstruction>." */
    val reduceInstruction: String,
    val mapTokens: Int,
    val reduceTokens: Int,
) {
    BULLET(
        "bullet", R.string.summary_style_bullet,
        "as a few short bullet points",
        "into ONE concise summary of a few short bullet points",
        256, 400,
    ),
    EXECUTIVE(
        "executive", R.string.summary_style_executive,
        "as a 2-3 sentence executive summary",
        "into ONE tight executive summary of 2-3 sentences",
        220, 288,
    ),
    NARRATIVE(
        "narrative", R.string.summary_style_narrative,
        "as a short, flowing paragraph",
        "into ONE cohesive short paragraph",
        320, 512,
    );

    companion object {
        fun fromId(id: String?): SummaryStyle = entries.firstOrNull { it.id == id } ?: BULLET
    }
}
