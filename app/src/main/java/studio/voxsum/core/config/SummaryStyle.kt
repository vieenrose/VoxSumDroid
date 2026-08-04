package studio.voxsum.core.config

import studio.voxsum.R

/**
 * Format of the generated summary — the "Summary style" picker. Orthogonal to the user's
 * [TranscriptionConfig.summaryPrompt] (what to focus on): this is
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
    // Explicit counts, not "a few": on an hour-long meeting the reduce prompt holds 10+ dense
    // partial summaries and a small model reads "combine" as KEEP EVERYTHING, filling whatever
    // token budget it gets (~30 bullets). Hard counts are followed far better, and the budgets
    // are a backstop, not a target (Summarizer adds a shrink pass as the guaranteed bound).
    BULLET(
        "bullet", R.string.summary_style_bullet,
        "as 3-5 short bullet points (each under 20 words)",
        "into ONE concise summary of AT MOST 7 short bullet points — keep only the most " +
            "important points, merge overlapping ones, drop minor detail (each bullet under 20 words)",
        224, 288,
    ),
    EXECUTIVE(
        "executive", R.string.summary_style_executive,
        "as a 2-3 sentence executive summary",
        "into ONE tight executive summary of 2-3 sentences (at most 60 words) — keep only what matters most",
        200, 224,
    ),
    NARRATIVE(
        "narrative", R.string.summary_style_narrative,
        "as a short, flowing paragraph",
        "into ONE cohesive paragraph of at most 6 sentences — keep only the most important " +
            "points, do not try to include everything",
        288, 384,
    );

    companion object {
        fun fromId(id: String?): SummaryStyle = entries.firstOrNull { it.id == id } ?: EXECUTIVE
    }
}
