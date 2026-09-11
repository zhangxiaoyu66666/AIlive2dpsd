package io.github.psd2live.ui.utils

import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import javax.swing.JFileChooser

/** Keep macOS/Linux support without changing the global Swing look and feel. */
internal object PortableFilePicker : FilePickerBackend {
    override fun show(request: FilePickerRequest, owner: Window?): String? {
        if (request.kind == FilePickerKind.DIRECTORY) {
            val chooser = JFileChooser(request.directory?.toFile()).apply {
                dialogTitle = request.title
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
            return if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
        }
        val mode = if (request.kind in setOf(FilePickerKind.SAVE_PROJECT, FilePickerKind.SAVE_PSD)) FileDialog.SAVE else FileDialog.LOAD
        val dialog = if (owner is Dialog) FileDialog(owner, request.title, mode)
            else FileDialog(owner as? Frame, request.title, mode)
        return try {
            dialog.directory = request.directory?.toString()
            dialog.file = request.defaultName
            dialog.setFilenameFilter { _, name -> request.extension == null || request.extension.split(',').any { name.endsWith(".$it", true) } }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it).absolutePath }
        } finally { dialog.dispose() }
    }
}
