package io.github.psd2live.ui.utils

import io.github.psd2live.i18n.tr
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/** One entry point for every file dialog in both desktop frontends. */
object NativeFilePicker {
    private val controller by lazy {
        FilePickerController(if (System.getProperty("os.name").startsWith("Windows", true)) WindowsFilePicker else PortableFilePicker)
    }

    fun chooseSavePsdFile(window: Window? = null, initialPath: String? = null): String? = choose(window) {
        FilePickerRequest(FilePickerKind.SAVE_PSD, tr("flow.savePsd"), "psd", tr("dialog.psdFilter"),
            path(initialPath)?.parent, path(initialPath)?.fileName?.toString() ?: "model.psd")
    }

    fun chooseImageFile(window: Window? = null, initialPath: String? = null): String? = choose(window) {
        FilePickerRequest(FilePickerKind.IMAGE, tr("flow.chooseImage"), "png,jpg,jpeg,webp", tr("flow.imageFilter"), path(initialPath))
    }

    fun choosePsdFile(window: Window? = null, initialPath: String? = null): String? = choose(window) {
        FilePickerRequest(FilePickerKind.PSD, tr("dialog.choosePsd"), "psd", tr("dialog.psdFilter"), path(initialPath))
    }

    fun chooseProjectFile(window: Window? = null, initialPath: String? = null): String? = choose(window) {
        FilePickerRequest(FilePickerKind.PROJECT, tr("project.open"), "psd2live", tr("dialog.projectFilter"), path(initialPath))
    }

    fun chooseSaveProjectFile(window: Window? = null, defaultName: String? = null, initialDir: String? = null): String? = choose(window) {
        FilePickerRequest(FilePickerKind.SAVE_PROJECT, tr("project.saveAs"), "psd2live", tr("dialog.projectFilter"),
            path(initialDir), projectFileName(defaultName))
    }

    fun chooseDirectory(window: Window? = null, initialPath: String? = null): String? = choose(window) {
        FilePickerRequest(FilePickerKind.DIRECTORY, tr("dialog.chooseOutput"), directory = path(initialPath))
    }

    private fun path(value: String?): Path? = value?.takeIf { it.isNotBlank() }?.let(Path::of)

    private fun choose(window: Window?, request: () -> FilePickerRequest): String? {
        val task = FutureTask<String?> {
            val owner = window ?: KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
            try {
                controller.choose(request(), owner) { target ->
                    JOptionPane.showConfirmDialog(owner, tr("project.overwrite", target), tr("project.save"),
                        JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
                }
            } catch (failure: Exception) {
                showFailure(owner, failure)
                null
            } catch (failure: LinkageError) {
                showFailure(owner, failure)
                null
            }
        }
        if (SwingUtilities.isEventDispatchThread()) task.run() else SwingUtilities.invokeAndWait(task)
        return task.get()
    }

    private fun showFailure(owner: Window?, failure: Throwable) {
        JOptionPane.showMessageDialog(owner, tr("dialog.pickerFailed", failure.message ?: failure.javaClass.simpleName),
            tr("dialog.failure.title"), JOptionPane.ERROR_MESSAGE)
    }
}
