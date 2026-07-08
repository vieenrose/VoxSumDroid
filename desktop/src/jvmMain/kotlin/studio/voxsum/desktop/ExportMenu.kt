package studio.voxsum.desktop

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import studio.voxsum.desktop.ui.Strings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import studio.voxsum.core.export.TranscriptExport
import studio.voxsum.data.speakerLabel
import studio.voxsum.desktop.files.FilePicker
import java.io.File
import java.io.FileOutputStream

/** Export the transcript/summary to a portable text or PDF format — the desktop counterpart of
 *  Android's ⋮ export menu (minus session .m4a/.ogg round-trip, tracked separately). */
@Composable
fun ExportMenu(expanded: Boolean, onDismiss: () -> Unit, state: AppState) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val label: (Int) -> String = { id -> speakerLabel(id, state.speakerNames) ?: "Speaker ${id + 1}" }
        DropdownMenuItem(text = { Text(Strings.saveAs(".txt")) }, onClick = {
            onDismiss()
            saveExport(state, "txt") { TranscriptExport.plainText(state.utterances, label, state.title, state.summary) }
        })
        DropdownMenuItem(text = { Text(Strings.saveAs("Markdown (.md)")) }, onClick = {
            onDismiss()
            saveExport(state, "md") { TranscriptExport.markdown(state.utterances, label, state.title, state.summary, "Summary", "Transcript") }
        })
        DropdownMenuItem(text = { Text(Strings.saveAs("SRT (.srt)")) }, onClick = {
            onDismiss()
            saveExport(state, "srt") { TranscriptExport.srt(state.utterances, label) }
        })
        DropdownMenuItem(text = { Text(Strings.saveAs("VTT (.vtt)")) }, onClick = {
            onDismiss()
            saveExport(state, "vtt") { TranscriptExport.vtt(state.utterances, label) }
        })
        DropdownMenuItem(text = { Text(Strings.saveAs("PDF (.pdf)")) }, onClick = {
            onDismiss()
            val base = state.fileName.substringBeforeLast('.').ifBlank { "transcript" }
            val dest = FilePicker.saveFile("Save as .pdf", "$base.pdf") ?: return@DropdownMenuItem
            FileOutputStream(dest).use { out ->
                PdfExport.write(out, state.utterances, label, state.title, state.summary, "Summary", "Transcript")
            }
        })
    }
}

private fun saveExport(state: AppState, ext: String, build: () -> String) {
    val base = state.fileName.substringBeforeLast('.').ifBlank { "transcript" }
    val dest = FilePicker.saveFile("Save as .$ext", "$base.$ext") ?: return
    File(dest.absolutePath).writeText(build())
}
