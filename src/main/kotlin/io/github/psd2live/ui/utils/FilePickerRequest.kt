package io.github.psd2live.ui.utils

import io.github.psd2live.i18n.tr
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path

internal enum class FilePickerKind { PSD, PROJECT, SAVE_PROJECT, DIRECTORY }

internal data class FilePickerRequest(
    val kind: FilePickerKind,
    val title: String,
    val extension: String? = null,
    val filterName: String? = null,
    val directory: Path? = null,
    val defaultName: String? = null,
)

internal fun interface FilePickerBackend {
    /** Null is cancellation; errors must not fall through to another dialog. */
    fun show(request: FilePickerRequest, owner: Window?): String?
}

/** Shared path policy; the OS backend owns navigation and dialogs. */
internal class FilePickerController(private val backend: FilePickerBackend) {
    private val lastDirectories = mutableMapOf<FilePickerKind, Path>()

    fun choose(request: FilePickerRequest, owner: Window?, confirmOverwrite: (Path) -> Boolean): String? {
        val resolved = request.copy(directory = existingDirectory(request.directory)
            ?: existingDirectory(lastDirectories[request.kind]))
        val selected = backend.show(resolved, owner) ?: return null
        val path = Path.of(selected).toAbsolutePath().normalize()
        val result = when (request.kind) {
            FilePickerKind.DIRECTORY -> {
                require(Files.isDirectory(path)) { tr("dialog.pickerInvalid", path) }
                path
            }
            FilePickerKind.SAVE_PROJECT -> {
                val target = path.resolveSibling(projectFileName(path.fileName.toString()))
                require(Files.isDirectory(target.parent) && !Files.isDirectory(target)) { tr("dialog.pickerInvalid", target) }
                // Extension normalization can change the file that the OS confirmed.
                if (target != path && Files.exists(target) && !confirmOverwrite(target)) return null
                target
            }
            else -> {
                require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".${request.extension}", true)) {
                    tr("dialog.pickerInvalid", path)
                }
                path
            }
        }
        lastDirectories[request.kind] = if (request.kind == FilePickerKind.DIRECTORY) result else result.parent
        return result.toString()
    }
}

internal fun existingDirectory(path: Path?): Path? {
    var candidate = path?.toAbsolutePath()?.normalize()
    while (candidate != null && !Files.isDirectory(candidate)) candidate = candidate.parent
    return candidate
}

internal fun projectFileName(name: String?): String {
    val file = name?.takeIf { it.isNotBlank() }?.let { Path.of(it).fileName.toString() } ?: "project.psd2live"
    return if (file.endsWith(".psd2live", true)) file else "$file.psd2live"
}
