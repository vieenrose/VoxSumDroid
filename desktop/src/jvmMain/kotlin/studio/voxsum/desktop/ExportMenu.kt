package studio.voxsum.desktop

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import studio.voxsum.core.export.ExportFormat
import studio.voxsum.core.export.ExportGroup
import studio.voxsum.core.export.TranscriptExport
import studio.voxsum.data.speakerLabel
import studio.voxsum.desktop.files.FilePicker
import studio.voxsum.desktop.ui.Strings
import java.io.File
import java.io.FileOutputStream

/**
 * Export the transcript/summary/actions to a portable text or PDF format — the desktop counterpart
 * of Android's export sheet (minus the session .m4a round-trip, tracked separately).
 *
 * Grouped by WHAT YOU GET, matching Android: a document to read, or timed lines for a player. A
 * desktop menu has no e-ink repaint cost and only six entries, so the grouping is section headers
 * in the existing dropdown rather than a bottom sheet.
 */
@Composable
fun ExportMenu(expanded: Boolean, onDismiss: () -> Unit, state: AppState) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SectionHeader(Strings.exportGroupDocument())
        ExportFormat.entries.filter { it.group == ExportGroup.DOCUMENT }.forEach { f ->
            DropdownMenuItem(
                text = { Text(Strings.saveAs("${f.ext.uppercase()} (.${f.ext})")) },
                onClick = { onDismiss(); export(state, f) },
            )
        }
        HorizontalDivider()
        SectionHeader(Strings.exportGroupSubtitles())
        ExportFormat.entries.filter { it.group == ExportGroup.SUBTITLES }.forEach { f ->
            DropdownMenuItem(
                text = { Text(Strings.saveAs("${f.ext.uppercase()} (.${f.ext})")) },
                onClick = { onDismiss(); export(state, f) },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

private fun export(state: AppState, format: ExportFormat) {
    val label: (Int) -> String = { id -> speakerLabel(id, state.speakerNames) ?: "Speaker ${id + 1}" }
    val base = state.fileName.substringBeforeLast('.').ifBlank { "transcript" }
    val dest = FilePicker.saveFile("Save as .${format.ext}", "$base.${format.ext}") ?: return
    val actions = state.actionItems.ifBlank { null }
    val actionsHeading = Strings.exportHeadingActions()

    if (format == ExportFormat.PDF) {
        // Binary: streams straight to the file instead of building a String.
        FileOutputStream(dest).use { out ->
            PdfExport.write(
                out, state.utterances, label, state.title, state.summary,
                Strings.exportHeadingSummary(), Strings.exportHeadingTranscript(), actions, actionsHeading,
            )
        }
        return
    }
    val text = when (format) {
        ExportFormat.TEXT -> TranscriptExport.plainText(
            state.utterances, label, state.title, state.summary, actions, actionsHeading,
        )
        ExportFormat.MARKDOWN -> TranscriptExport.markdown(
            state.utterances, label, state.title, state.summary,
            Strings.exportHeadingSummary(), Strings.exportHeadingTranscript(), actions, actionsHeading,
        )
        ExportFormat.SRT -> TranscriptExport.srt(state.utterances, label)
        ExportFormat.VTT -> TranscriptExport.vtt(state.utterances, label)
        ExportFormat.LRC -> TranscriptExport.lrc(state.utterances, label, state.title)
        // PDF returns above; the session archive is Android-only for now (no .m4a round-trip here),
        // and the menu never offers it because it renders only the DOCUMENT and SUBTITLES groups.
        ExportFormat.PDF, ExportFormat.M4A -> return
    }
    File(dest.absolutePath).writeText(text)
}
