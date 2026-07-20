package studio.voxsum.desktop

// Desktop-local counterpart of app/core/config/SummaryStyle.kt, which references
// studio.voxsum.R (Android string resources) and can't be shared as-is. Same three styles,
// same map/reduce instructions and token budgets, English-only labels (no localization yet).
enum class SummaryStyle(
    val id: String,
    val label: String,
    val mapInstruction: String,
    val reduceInstruction: String,
    val mapTokens: Int,
    val reduceTokens: Int,
) {
    // Explicit counts, not "a few": on an hour-long meeting the reduce prompt holds 10+ dense
    // partial summaries and a small model reads "combine" as KEEP EVERYTHING, filling whatever
    // token budget it gets (~30 bullets). Hard counts are followed far better, and the budgets
    // are a backstop, not a target (Summarizer adds a shrink pass as the guaranteed bound).
    BULLET(
        "bullet", "Bullets",
        "as 3-5 short bullet points (each under 20 words)",
        "into ONE concise summary of AT MOST 7 short bullet points — keep only the most " +
            "important points, merge overlapping ones, drop minor detail (each bullet under 20 words)",
        224, 288,
    ),
    EXECUTIVE(
        "executive", "Executive",
        "as a 2-3 sentence executive summary",
        "into ONE tight executive summary of 2-3 sentences (at most 60 words) — keep only what matters most",
        200, 224,
    ),
    NARRATIVE(
        "narrative", "Narrative",
        "as a short, flowing paragraph",
        "into ONE cohesive paragraph of at most 6 sentences — keep only the most important " +
            "points, do not try to include everything",
        288, 384,
    );

    companion object {
        fun fromId(id: String?): SummaryStyle = entries.firstOrNull { it.id == id } ?: EXECUTIVE
    }
}
