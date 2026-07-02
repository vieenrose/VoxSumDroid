package studio.voxsum.desktop.files

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter

/**
 * Desktop counterpart of MainActivity's SAF launchers (ActivityResultContracts.OpenDocument /
 * CreateDocument). AWT's FileDialog is used over Swing's JFileChooser because it delegates to
 * the native GTK file picker on Linux — matching the look every other app on the desktop uses,
 * the same reasoning SAF gives Android a native system picker instead of an in-app one.
 *
 * Both calls are modal (block the calling thread until the dialog closes) — call from a
 * background dispatcher (e.g. Dispatchers.IO) if invoked from a coroutine so it doesn't block
 * the UI thread other composables run on.
 */
object FilePicker {

    /** Opens a native "pick a file to open" dialog. Returns null if the user cancels. */
    fun openFile(title: String, extensions: List<String> = emptyList(), owner: Frame? = null): File? {
        val dialog = FileDialog(owner, title, FileDialog.LOAD)
        if (extensions.isNotEmpty()) dialog.filenameFilter = extensionFilter(extensions)
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir != null && file != null) File(dir, file) else null
    }

    /** Opens a native "pick a destination to save" dialog, pre-filled with [suggestedName].
     *  Returns null if the user cancels. */
    fun saveFile(title: String, suggestedName: String, owner: Frame? = null): File? {
        val dialog = FileDialog(owner, title, FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        return if (dir != null && file != null) File(dir, file) else null
    }

    private fun extensionFilter(extensions: List<String>): FilenameFilter {
        val lower = extensions.map { it.lowercase().removePrefix(".") }
        return FilenameFilter { _, name -> lower.any { name.lowercase().endsWith(".$it") } }
    }
}
