package studio.voxsum.core.export

/**
 * Every way a session can leave the app, described once so the menu, the file picker and the
 * writers all agree on extension and MIME type.
 *
 * Grouped by WHAT YOU GET rather than by how it is written, because that is the choice the user is
 * actually making: an archive that reopens here, a document to read, or timed lines for a player.
 */
enum class ExportFormat(
    val group: ExportGroup,
    val ext: String,
    val mime: String,
) {
    /** The self-describing archive: audio + transcript + speakers + summary + actions. */
    M4A(ExportGroup.SESSION, "m4a", "audio/mp4"),

    PDF(ExportGroup.DOCUMENT, "pdf", "application/pdf"),
    MARKDOWN(ExportGroup.DOCUMENT, "md", "text/markdown"),
    TEXT(ExportGroup.DOCUMENT, "txt", "text/plain"),

    SRT(ExportGroup.SUBTITLES, "srt", "application/x-subrip"),
    VTT(ExportGroup.SUBTITLES, "vtt", "text/vtt"),

    // .lrc has no registered MIME; octet-stream keeps the extension intact (players match by name).
    LRC(ExportGroup.SUBTITLES, "lrc", "application/octet-stream"),
    ;

    /** PDF and the session archive are binary — they stream instead of writing a String. */
    val isBinary: Boolean get() = this == PDF || this == M4A
}

enum class ExportGroup { SESSION, DOCUMENT, SUBTITLES }
