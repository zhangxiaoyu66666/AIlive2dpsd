package io.github.psd2live.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.ui.state.DesktopProjectTabs
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.views.ProjectTabBar
import io.github.psd2live.ui.views.WorkspaceView
import io.github.psd2live.ui.state.WorkspaceTab
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path

/** Actual Compose scrolling and pointer gestures, with no native windows. */
object ProjectTabsUiCheck {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        val output = Path.of(args[0]); Files.createDirectories(output)
        System.setProperty("psd2live.agent.store", output.resolve("workspace").toString())
        withContext(Dispatchers.Main) {
            val controller = DesktopProjectTabs()
            val scroll = ScrollState(0)
            try {
                repeat(11) { controller.create() }
                val tabs = controller.state.value.tabs
                tabs.forEachIndexed { index, tab ->
                    tab.viewModel.setLanguage(AppLanguage.CHINESE)
                    tab.viewModel.setStateForTest(tab.viewModel.state.value.copy(projectSourceName = "人物工程 ${index + 1}.psd"))
                }
                controller.select(tabs.first().id)
                val scene = ImageComposeScene(900, 100) {
                    CompactToolTheme {
                        val state by controller.state.collectAsState()
                        // Match the real parent: changing the active project recreates keyed content.
                        key(state.activeId) { ProjectTabBar(controller, state, scroll) }
                    }
                }
                try {
                    suspend fun settle() { repeat(40) { scene.render(System.nanoTime()).close(); delay(16) } }
                    fun capture(name: String) { scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(output.resolve(name), it.bytes) } } }
                    settle()
                    check(scroll.maxValue > 0 && scroll.value == 0) { "Tab overflow did not produce a scroll range" }
                    capture("tabs-start.png")
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(120f, 18f), scrollDelta = Offset(0f, 3f))
                    settle()
                    check(scroll.value >= 100) { "A normal vertical mouse wheel did not scroll the tab strip horizontally" }
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(120f, 18f), scrollDelta = Offset(0f, -3f))
                    settle()
                    check(scroll.value == 0) { "Reverse mouse wheel did not scroll back" }
                    // Drag the visible horizontal thumb from the left towards the middle.
                    scene.sendPointerEvent(PointerEventType.Press, Offset(60f, 41f), button = PointerButton.Primary)
                    scene.sendPointerEvent(PointerEventType.Move, Offset(450f, 41f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(450f, 41f), button = PointerButton.Primary)
                    settle()
                    check(scroll.value > 100) { "Dragging the scrollbar did not reveal hidden tabs" }
                    capture("tabs-dragged.png")
                    controller.select(tabs.last().id)
                    settle()
                    check(scroll.maxValue - scroll.value < 5) { "Selected final tab is still outside the viewport: ${scroll.value}/${scroll.maxValue}" }
                    capture("tabs-last.png")
                    controller.select(tabs.first().id)
                    settle()
                    check(scroll.value == 0) { "Selected first tab was not brought back into view" }
                    println("PROJECT_TABS_UI_OK count=${tabs.size} mouse_wheel=true scrollbar_drag=true active_tab_reveal=true keyed_switch=true range=${scroll.maxValue}")
                } finally { scene.close() }
                if (args.size > 1) {
                    controller.open(Path.of(args[1]))
                    val imported = controller.state.value.tabs.last()
                    imported.viewModel.presentationActive = false
                    withTimeout(60000) { imported.viewModel.state.first { !it.isAnalyzing } }
                    check(imported.viewModel.state.value.errorMessage == null) { imported.viewModel.state.value.errorMessage.orEmpty() }
                    check(imported.viewModel.state.value.activeWorkspaceTab == WorkspaceTab.PREVIEW)
                    check(imported.viewModel.state.value.previewModel != null)
                    val previewScene = ImageComposeScene(1280, 720) {
                        CompactToolTheme {
                            val state by imported.viewModel.state.collectAsState()
                            val projectTabs by controller.state.collectAsState()
                            Column(Modifier.fillMaxSize()) {
                                ProjectTabBar(controller, projectTabs, scroll)
                                Box(Modifier.weight(1f)) { WorkspaceView(state, imported.viewModel) }
                            }
                        }
                    }
                    try {
                        repeat(30) { previewScene.render(System.nanoTime()).close(); delay(16) }
                        previewScene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(output.resolve("import-preview.png"), it.bytes) } }
                        val base = imported.viewModel.state.value
                        for (overlay in listOf(false, true)) {
                            val times = mutableListOf<Double>()
                            repeat(30) { index ->
                                imported.viewModel.setStateForTest(base.copy(
                                    parameterValues = base.parameterValues + (org.umamo.runtime.model.ParameterId("ParamAngleX") to (index - 15f)),
                                    hoveredLayerId = if (overlay) base.previewModel!!.rig.layerIdByDrawableId.values.first() else null))
                                delay(1)
                                val started = System.nanoTime()
                                previewScene.render(System.nanoTime()).close()
                                if (index >= 5) times += (System.nanoTime() - started) / 1e6
                            }
                            println("COMPOSE_PREVIEW_FRAME overlay=$overlay median_ms=${times.sorted()[times.size / 2]} p95_ms=${times.sorted()[(times.size * .95).toInt()]}")
                        }
                    } finally { previewScene.close() }
                    println("DIRECT_PSD_PREVIEW_OK layers=${imported.viewModel.state.value.analysis!!.layers.size} sourceWorkflowCandidate=${imported.viewModel.sourceWorkflow.state.value.candidate != null}")
                }
            } finally { controller.close() }
        }
    }
}
