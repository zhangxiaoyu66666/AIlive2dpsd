package io.github.psd2live.ui

import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.ui.state.DesktopProjectTabs
import io.github.psd2live.ui.state.WorkspaceTab
import io.github.psd2live.ui.utils.ProjectFileDrop
import io.github.psd2live.workflow.syntheticPsd
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.datatransfer.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ProjectFileImportTest {
    @Test fun workflowImportUsesPreviewAndKeepsBackgroundAgentTabInactive() = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-workflow-tab-")
        val controller = withContext(Dispatchers.Main) { DesktopProjectTabs() }
        try {
            val owner = controller.state.value.tabs.single()
            val active = withContext(Dispatchers.Main) { controller.create() }
            val file = Files.write(root.resolve("model.psd"), syntheticPsd())
            val version = io.github.psd2live.workflow.SourceVersions.capture(file, root.resolve("versions"))
            val lineage = io.github.psd2live.workflow.SourceWorkflowRecord(confirmed = version, imported = version)
            withContext(Dispatchers.Main) { owner.viewModel.sourceWorkflow.importVersion!!.invoke(version, lineage, false) }
            withTimeout(30000) { owner.viewModel.state.first { !it.isAnalyzing } }
            assertNull(owner.viewModel.state.value.errorMessage)
            assertNotNull(owner.viewModel.state.value.analysis)
            assertEquals(WorkspaceTab.PREVIEW, owner.viewModel.state.value.activeWorkspaceTab)
            assertEquals(active.id, controller.state.value.activeId)
            withContext(Dispatchers.Main) { assertTrue(owner.viewModel.sourceWorkflow.openImportedVersion!!.invoke(owner.id)) }
            assertEquals(owner.id, controller.state.value.activeId)
            assertEquals(WorkspaceTab.PREVIEW, owner.viewModel.state.value.activeWorkspaceTab)
        } finally { controller.close(); ProjectArchive.deleteTemporaryDirectory(root) }
    }

    private fun transfer(files: List<File>) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any = if (isDataFlavorSupported(flavor)) files else throw UnsupportedFlavorException(flavor)
    }

    @Test fun explorerDropAndMenuOpenLoadPsdDirectlyIntoPreview() = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-file-import-")
        val controller = withContext(Dispatchers.Main) { DesktopProjectTabs() }
        try {
            val file = Files.write(root.resolve("人物 空格.PSD"), syntheticPsd())
            val drop = transfer(listOf(file.toFile(), file.toFile()))
            assertTrue(ProjectFileDrop.supports(drop))
            val paths = ProjectFileDrop.read(drop)
            assertEquals(listOf(file), paths)
            withContext(Dispatchers.Main) { paths.forEach(controller::open) }
            val tab = controller.state.value.tabs.last()
            withTimeout(30000) { tab.viewModel.state.first { !it.isAnalyzing } }
            assertNull(tab.viewModel.state.value.errorMessage)
            assertNotNull(tab.viewModel.state.value.previewModel)
            assertNotNull(tab.viewModel.state.value.historySnapshot)
            assertEquals(WorkspaceTab.PREVIEW, tab.viewModel.state.value.activeWorkspaceTab)
            assertEquals(file.toString(), tab.viewModel.state.value.loadedInputPath)
            assertNull(tab.viewModel.sourceWorkflow.state.value.candidate)
            withContext(Dispatchers.Main) { controller.open(file) }
            assertEquals(tab.id, controller.state.value.activeId)
            assertEquals(2, controller.state.value.tabs.size)
        } finally { controller.close(); ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun dropRejectsTextMissingFilesAndDirectoriesAndKeepsAllSupportedFiles() {
        val root = Files.createTempDirectory("psd2live-project-file-drop-")
        try {
            val psd = Files.write(root.resolve("model.psd"), byteArrayOf(1))
            val project = Files.write(root.resolve("model.psd2live"), byteArrayOf(1))
            val image = Files.write(root.resolve("image.png"), byteArrayOf(1))
            val folder = Files.createDirectory(root.resolve("folder.psd"))
            assertEquals(listOf(psd, project), ProjectFileDrop.read(transfer(listOf(psd, project, image, folder, root.resolve("missing.psd")).map { it.toFile() })))
            assertFalse(ProjectFileDrop.supports(StringSelection(psd.toString())))
            assertTrue(ProjectFileDrop.read(StringSelection(psd.toString())).isEmpty())
        } finally { ProjectArchive.deleteTemporaryDirectory(root) }
    }
}
