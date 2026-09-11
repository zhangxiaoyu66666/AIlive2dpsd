package io.github.psd2live.ui.utils

import com.sun.jna.Native
import com.sun.jna.Pointer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil.memUTF8
import org.lwjgl.util.nfd.*
import org.lwjgl.util.nfd.NativeFileDialog.*
import java.awt.Window
import javax.swing.SwingUtilities

/** Native File Dialog Extended uses Windows IFileOpenDialog / IFileSaveDialog. */
internal object WindowsFilePicker : FilePickerBackend {
    override fun show(request: FilePickerRequest, owner: Window?): String? {
        check(SwingUtilities.isEventDispatchThread()) { "File picker must run on the AWT event thread" }
        check(NFD_Init() == NFD_OKAY) { NFD_GetError() ?: "Native file dialog initialization failed" }
        try {
            MemoryStack.stackPush().use { stack ->
                val result = stack.callocPointer(1)
                val parent = NFDWindowHandle.calloc(stack)
                if (owner?.isDisplayable == true) {
                    parent.type(NFD_WINDOW_HANDLE_TYPE_WINDOWS.toLong())
                        .handle(Pointer.nativeValue(Native.getWindowPointer(owner)))
                }
                val filters = request.extension?.let { extension ->
                    NFDFilterItem.calloc(1, stack).also { filter ->
                        filter[0].name(stack.UTF8(request.filterName ?: extension)).spec(stack.UTF8(extension))
                    }
                }
                val directory = request.directory?.let { stack.UTF8(it.toString()) }
                val status = when (request.kind) {
                    FilePickerKind.DIRECTORY -> NFD_PickFolder_With(result,
                        NFDPickFolderArgs.calloc(stack).defaultPath(directory).parentWindow(parent))
                    FilePickerKind.SAVE_PROJECT -> NFD_SaveDialog_With(result,
                        NFDSaveDialogArgs.calloc(stack).filterList(filters).defaultPath(directory)
                            .defaultName(request.defaultName?.let(stack::UTF8)).parentWindow(parent))
                    else -> NFD_OpenDialog_With(result,
                        NFDOpenDialogArgs.calloc(stack).filterList(filters).defaultPath(directory).parentWindow(parent))
                }
                return when (status) {
                    NFD_CANCEL -> null
                    NFD_OKAY -> {
                        val pointer = result[0]
                        check(pointer != 0L) { "Native dialog returned an empty path" }
                        try { memUTF8(pointer) } finally { NFD_FreePath(pointer) }
                    }
                    else -> error(NFD_GetError() ?: "Native file dialog failed")
                }
            }
        } finally { NFD_Quit() }
    }
}
