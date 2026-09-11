package io.github.psd2live.ui

import io.github.psd2live.ui.utils.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.ResourceBundle
import javax.swing.SwingUtilities
import org.lwjgl.util.nfd.NativeFileDialog.*
import kotlin.test.*

class FilePickerTest {
    private fun project(directory: Path? = null) = FilePickerRequest(FilePickerKind.PROJECT, "Open", "psd2live", "PSD2Live", directory)

    @Test fun cancelEndsTheRequestWithoutRetryOrOverwriteConfirmation() {
        var calls = 0
        val controller = FilePickerController { _, _ -> calls++; null }
        assertNull(controller.choose(project(), null) { fail("Cancel must not confirm a save") })
        assertEquals(1, calls)
    }

    @Test fun backendFailureIsNotTreatedAsCancelOrRetried() {
        var calls = 0
        val controller = FilePickerController { _, _ -> calls++; error("native initialization failed") }
        assertFailsWith<IllegalStateException> { controller.choose(project(), null) { true } }
        assertEquals(1, calls)
    }

    @Test fun unicodePathsAndLastDirectorySurviveOpeningAndCancellation() = withDirectory { root ->
        val folder = Files.createDirectory(root.resolve("人物 素材 [修复]"))
        val file = Files.writeString(folder.resolve("赵雅思.PSD2LIVE"), "fixture")
        val requests = mutableListOf<FilePickerRequest>()
        val answers = ArrayDeque(listOf(file.toString(), "cancel", file.toString()))
        val controller = FilePickerController { request, _ ->
            requests += request
            answers.removeFirst().takeUnless { it == "cancel" }
        }
        assertEquals(file.toString(), controller.choose(project(root), null) { false })
        assertNull(controller.choose(project(), null) { false })
        assertEquals(file.toString(), controller.choose(project(), null) { false })
        assertEquals(folder, requests[1].directory)
        assertEquals(folder, requests[2].directory)
        assertEquals("psd2live", requests[0].extension)
        assertNull(requests[0].defaultName, "Open must not put a wildcard in the filename box")
    }

    @Test fun invalidOpenTypesAndDirectorySelectionsAreRejected() = withDirectory { root ->
        val text = Files.writeString(root.resolve("not-a-project.txt"), "fixture")
        val controller = FilePickerController { _, _ -> text.toString() }
        assertFailsWith<IllegalArgumentException> { controller.choose(project(), null) { false } }
        assertFailsWith<IllegalArgumentException> {
            controller.choose(FilePickerRequest(FilePickerKind.DIRECTORY, "Folder"), null) { false }
        }
        assertEquals(root, existingDirectory(root.resolve("new/subfolder/export")))
        assertEquals(root, existingDirectory(text))
    }

    @Test fun appendingSaveExtensionCannotOverwriteAnUnconfirmedFile() = withDirectory { root ->
        val target = Files.writeString(root.resolve("新工程.psd2live"), "keep original")
        val controller = FilePickerController { _, _ -> root.resolve("新工程").toString() }
        val request = FilePickerRequest(FilePickerKind.SAVE_PROJECT, "Save", "psd2live")
        var confirmations = 0
        assertNull(controller.choose(request, null) { confirmations++; assertEquals(target, it); false })
        assertEquals("keep original", Files.readString(target))
        assertEquals(1, confirmations)
        assertEquals(target.toString(), controller.choose(request, null) { true })
        assertEquals("keep original", Files.readString(target), "Picker selects a path; it must never write the project")
        assertEquals("角色.PSD2LIVE", projectFileName("角色.PSD2LIVE"))
        assertEquals("角色.psd2live", projectFileName("角色"))
    }

    @Test fun pickerMessagesExistInEverySupportedLanguage() {
        for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE, Locale.JAPANESE)) {
            val bundle = ResourceBundle.getBundle("i18n.Messages", locale)
            for (key in listOf("dialog.projectFilter", "dialog.pickerFailed", "dialog.pickerInvalid")) assertTrue(bundle.containsKey(key))
        }
    }

    @Test fun windowsNativeLibraryInitializesWithoutOpeningAWindow() {
        if (!System.getProperty("os.name").startsWith("Windows", true)) return
        SwingUtilities.invokeAndWait {
            assertEquals(NFD_OKAY, NFD_Init(), "The packaged native dependency must load and initialize COM")
            NFD_Quit()
        }
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("psd2live-file-picker-test")
        try { block(root) } finally { root.toFile().deleteRecursively() }
    }
}
