package io.github.psd2live.ui

import io.github.psd2live.ui.state.DesktopProjectTabs
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopProjectTabsTest {
    @Test fun closeAllCancelAndConcurrentEditsPreserveEveryTab() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            val controller = DesktopProjectTabs()
            try {
                val blank = controller.state.value.tabs.single()
                val dirty = controller.create()
                dirty.viewModel.setStateForTest(dirty.viewModel.state.value.copy(projectDirty = true, projectEditVersion = 1))
                var closed = false
                controller.confirmCloseAll = { 2 }
                controller.requestCloseAll { closed = true }
                assertFalse(closed)
                assertEquals(listOf(blank, dirty), controller.state.value.tabs)
                controller.confirmCloseAll = {
                    dirty.viewModel.setStateForTest(dirty.viewModel.state.value.copy(projectEditVersion = 2))
                    1
                }
                controller.requestCloseAll { closed = true }
                assertFalse(closed)
                assertEquals(listOf(blank, dirty), controller.state.value.tabs)
                controller.confirmCloseAll = { 1 }
                controller.requestCloseAll { closed = true }
                assertTrue(closed)
                assertTrue(controller.state.value.tabs.isEmpty())
            } finally { controller.close() }
        }
    }

    @Test fun switchingPreservesPerTabStateAndPausesBackgroundPresentation() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            val controller = DesktopProjectTabs()
            try {
                val a = controller.state.value.tabs.single()
                assertEquals(io.github.psd2live.ui.state.WorkspaceTab.PREVIEW, a.viewModel.state.value.activeWorkspaceTab)
                a.viewModel.setStateForTest(a.viewModel.state.value.copy(inputPath = "a.psd", outputPath = "a-export", projectDirty = true, projectEditVersion = 4))
                val saved = a.viewModel.state.value
                val b = controller.create()
                assertEquals(io.github.psd2live.ui.state.WorkspaceTab.PREVIEW, b.viewModel.state.value.activeWorkspaceTab)
                b.viewModel.setStateForTest(b.viewModel.state.value.copy(inputPath = "b.psd", outputPath = "b-export"))
                assertFalse(a.viewModel.presentationActive)
                assertTrue(b.viewModel.presentationActive)
                controller.select(a.id)
                assertEquals(saved, a.viewModel.state.value)
                assertEquals("b-export", b.viewModel.state.value.outputPath)
                assertTrue(a.viewModel.presentationActive)
                assertFalse(b.viewModel.presentationActive)
                assertNotSame(a.workspace, b.workspace)
                val c = controller.create()
                controller.select(a.id)
                controller.requestClose(b.id)
                assertEquals(a.id, controller.state.value.activeId)
                assertTrue(controller.state.value.tabs.any { it.id == c.id })
            } finally { controller.close() }
        }
    }

    @Test fun cancelAndEditsDuringConfirmationPreventClose() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            val controller = DesktopProjectTabs()
            try {
                val tab = controller.state.value.tabs.single()
                tab.viewModel.setStateForTest(tab.viewModel.state.value.copy(projectDirty = true, projectEditVersion = 1))
                controller.confirmUnsaved = { 2 }
                controller.requestClose(tab.id)
                assertEquals(tab.id, controller.state.value.tabs.single().id)
                controller.confirmUnsaved = {
                    tab.viewModel.setStateForTest(tab.viewModel.state.value.copy(projectEditVersion = 2))
                    1
                }
                controller.requestClose(tab.id)
                assertEquals(tab.id, controller.state.value.tabs.single().id)
                controller.confirmUnsaved = { 1 }
                controller.requestClose(tab.id)
                assertNotEquals(tab.id, controller.state.value.tabs.single().id)
                assertFalse(controller.agents.manifest().toString().contains(tab.id))
            } finally { controller.close() }
        }
    }

    @Test fun closingBackgroundTabKeepsOtherProjectAndStopsAtBusyTab() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            val controller = DesktopProjectTabs()
            try {
                val a = controller.state.value.tabs.single()
                val b = controller.create()
                b.viewModel.setStateForTest(b.viewModel.state.value.copy(isAnalyzing = true))
                controller.requestClose(a.id)
                assertEquals(b.id, controller.state.value.tabs.single().id)
                var finished = false
                controller.requestCloseAll { finished = true }
                assertFalse(finished)
                b.viewModel.setStateForTest(b.viewModel.state.value.copy(isAnalyzing = false))
                controller.requestCloseAll { finished = true }
                assertTrue(finished)
                assertTrue(controller.state.value.tabs.isEmpty())
            } finally { controller.close() }
        }
    }
}
