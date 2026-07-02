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
    BULLET(
        "bullet", "Bullets",
        "as a few short bullet points",
        "into ONE concise summary of a few short bullet points",
        256, 400,
    ),
    EXECUTIVE(
        "executive", "Executive",
        "as a 2-3 sentence executive summary",
        "into ONE tight executive summary of 2-3 sentences",
        220, 288,
    ),
    NARRATIVE(
        "narrative", "Narrative",
        "as a short, flowing paragraph",
        "into ONE cohesive short paragraph",
        320, 512,
    );

    companion object {
        fun fromId(id: String?): SummaryStyle = entries.firstOrNull { it.id == id } ?: BULLET
    }
}
