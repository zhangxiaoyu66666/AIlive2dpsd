package io.github.psd2live.ui.utils

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.unit.dp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.theme.LocalToolColors
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Explorer's standard file-list transfer; opening policy belongs to the tab controller. */
internal object ProjectFileDrop {
    fun supports(transfer: Transferable) = transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

    fun read(transfer: Transferable): List<Path> {
        if (!supports(transfer)) return emptyList()
        val files = transfer.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return emptyList()
        return files.filterIsInstance<File>().map { it.toPath().toAbsolutePath().normalize() }
            .filter { it.fileName.toString().substringAfterLast('.').lowercase() in setOf("psd", "psd2live") && Files.isRegularFile(it) }
            .distinct()
    }
}

/** Bind to the Compose content, whose native child surface receives drops, not the outer JFrame. */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.projectFileDrop(onOpen: (Path) -> Unit, onError: (String) -> Unit, onDragStateChanged: (Boolean) -> Unit = {}): Modifier {
    val open by rememberUpdatedState(onOpen)
    val error by rememberUpdatedState(onError)
    val dragStateChanged by rememberUpdatedState(onDragStateChanged)
    var hovering by remember { mutableStateOf(false) }
    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { hovering = true; dragStateChanged(true) }
            override fun onExited(event: DragAndDropEvent) { hovering = false; dragStateChanged(false) }
            override fun onEnded(event: DragAndDropEvent) { hovering = false; dragStateChanged(false) }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                dragStateChanged(false)
                return try {
                    val paths = ProjectFileDrop.read(event.awtTransferable)
                    if (paths.isEmpty()) { error(tr("drop.unsupported")); false }
                    else if (paths.size != 1) { error(tr("drop.singleFile")); false }
                    else { open(paths.single()); true }
                } catch (failure: Exception) { error(tr("drop.failed", failure.message.orEmpty())); false }
            }
        }
    }
    return then(if (hovering) Modifier.border(2.dp, LocalToolColors.current.accent) else Modifier)
        .dragAndDropTarget(shouldStartDragAndDrop = { ProjectFileDrop.supports(it.awtTransferable) }, target = target)
}
