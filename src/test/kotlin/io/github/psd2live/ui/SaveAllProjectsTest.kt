package io.github.psd2live.ui

import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.ui.state.DesktopProjectTabs
import io.github.psd2live.ui.state.DesktopProjectTab
import io.github.psd2live.workflow.syntheticPsd
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SaveAllProjectsTest {
    private fun fixture(action: suspend (DesktopProjectTabs, Path, List<DesktopProjectTab>) -> Unit) = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-save-all-")
        val controller = withContext(Dispatchers.Main) { DesktopProjectTabs() }
        try {
            withTimeout(60000) {
                withContext(Dispatchers.Main) {
                    val projects = (1..2).map { index ->
                        val source = Files.write(root.resolve("model-$index.psd"), syntheticPsd())
                        controller.create(source).also { tab ->
                            tab.viewModel.presentationActive = false
                            tab.viewModel.state.first { !it.isAnalyzing }
                            assertNotNull(tab.viewModel.state.value.analysis)
                        }
                    }
                    action(controller, root, projects)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { controller.close() }
            ProjectArchive.deleteTemporaryDirectory(root)
        }
    }

    private suspend fun finish(controller: DesktopProjectTabs) {
        controller.state.first { !it.savingAll }
    }

    @Test fun savesExistingProjectsAndRestoresSelectionEvenFromEmptyTab() = fixture { controller, root, projects ->
        projects.forEachIndexed { i, tab ->
            tab.viewModel.saveProjectNow(root.resolve("project-$i.psd2live"))
            tab.viewModel.setHistoryView(1f, 0f, 0f, "saved-$i", false)
        }
        val empty = controller.state.value.tabs.first()
        controller.select(empty.id)
        controller.requestSaveAll()
        finish(controller)
        assertEquals(empty.id, controller.state.value.activeId)
        projects.forEachIndexed { i, tab ->
            assertFalse(tab.viewModel.state.value.projectDirty)
            val extracted = ProjectArchive.extract(root.resolve("project-$i.psd2live"))
            try {
                assertEquals("saved-$i", ProjectArchive.readJson(extracted.resolve("workspace.json"))["historySearch"]?.jsonPrimitive?.content)
            } finally { ProjectArchive.deleteTemporaryDirectory(extracted) }
        }
    }

    @Test fun closeSavesNewProjectsSequentiallyThenClosesOnce() = fixture { controller, root, projects ->
        var prompts = 0
        var closes = 0
        controller.confirmCloseAll = { prompts++; 0 }
        controller.requestCloseAll { closes++ }
        for ((i, tab) in projects.withIndex()) {
            tab.viewModel.state.first { it.showProjectLocationDialog }
            assertEquals(tab.id, controller.state.value.activeId)
            assertEquals(3, controller.state.value.tabs.size)
            assertEquals(0, closes)
            tab.viewModel.saveProjectTo(root.resolve("project-$i.psd2live"))
        }
        finish(controller)
        assertEquals(1, prompts)
        assertEquals(1, closes)
        assertTrue(controller.state.value.tabs.isEmpty())
        assertTrue(Files.isRegularFile(root.resolve("project-0.psd2live")))
        assertTrue(Files.isRegularFile(root.resolve("project-1.psd2live")))
    }

    @Test fun cancellingSecondLocationRetainsEveryTabIncludingAlreadySavedOnes() = fixture { controller, root, projects ->
        var closed = false
        controller.confirmCloseAll = { 0 }
        controller.requestCloseAll { closed = true }
        projects[0].viewModel.state.first { it.showProjectLocationDialog }
        projects[0].viewModel.saveProjectTo(root.resolve("first.psd2live"))
        projects[1].viewModel.state.first { it.showProjectLocationDialog }
        controller.requestSaveAll() // Repeated requests must not start another batch.
        controller.requestClose(projects[0].id)
        projects[1].viewModel.cancelProjectLocation()
        finish(controller)
        assertFalse(closed)
        assertEquals(3, controller.state.value.tabs.size)
        assertFalse(projects[0].viewModel.state.value.projectDirty)
        assertTrue(projects[1].viewModel.state.value.projectDirty)
    }

    @Test fun failedWriteStopsBatchAndKeepsAllProjects() = fixture { controller, root, projects ->
        val errors = mutableListOf<String>()
        controller.reportError = { errors += it }
        controller.confirmCloseAll = { 0 }
        var closed = false
        controller.requestCloseAll { closed = true }
        projects[0].viewModel.state.first { it.showProjectLocationDialog }
        val fileAsParent = Files.writeString(root.resolve("not-a-directory"), "blocked")
        projects[0].viewModel.saveProjectTo(fileAsParent.resolve("invalid.psd2live"))
        finish(controller)
        assertFalse(closed)
        assertTrue(errors.isNotEmpty())
        assertEquals(3, controller.state.value.tabs.size)
        assertTrue(projects.all { it.viewModel.state.value.projectDirty })
        assertFalse(projects[1].viewModel.state.value.showProjectLocationDialog)
    }

    @Test fun editsToAlreadySavedProjectPreventWindowClose() = fixture { controller, root, projects ->
        val errors = mutableListOf<String>()
        controller.reportError = { errors += it }
        controller.confirmCloseAll = { 0 }
        var closed = false
        controller.requestCloseAll { closed = true }
        projects[0].viewModel.state.first { it.showProjectLocationDialog }
        projects[0].viewModel.saveProjectTo(root.resolve("first.psd2live"))
        projects[1].viewModel.state.first { it.showProjectLocationDialog }
        projects[0].viewModel.setHistoryView(1f, 0f, 0f, "new edit", false)
        projects[1].viewModel.saveProjectTo(root.resolve("second.psd2live"))
        finish(controller)
        assertFalse(closed)
        assertTrue(errors.isNotEmpty())
        assertEquals(3, controller.state.value.tabs.size)
        assertTrue(projects[0].viewModel.state.value.projectDirty)
    }
}
