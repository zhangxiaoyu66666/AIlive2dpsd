package io.github.psd2live.workflow

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerButton
import io.github.psd2live.i18n.AppLanguage
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.state.WorkspaceTab
import io.github.psd2live.ui.theme.CompactToolTheme
import io.github.psd2live.ui.views.SourceWorkflowPage
import io.github.psd2live.ui.views.WorkspaceView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Renders actual Compose content to images without creating any desktop window. */
object SourceWorkflowPageCheck {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        val output = Path.of(args[2]); Files.createDirectories(output)
        System.setProperty("psd2live.agent.store", output.resolve("workspace").toString())
        val vm = PSD2LiveViewModel().apply { presentationActive = false; setLanguage(AppLanguage.CHINESE); setWorkspaceTab(WorkspaceTab.SEE_THROUGH) }
        try {
            suspend fun render(name: String) = withContext(Dispatchers.Main) {
                val scene = ImageComposeScene(1280, 720) {
                    CompactToolTheme {
                        val state by vm.state.collectAsState()
                        WorkspaceView(state, vm, sourceWorkflow = { SourceWorkflowPage(vm, null, autoDetect = false) })
                    }
                }
                try {
                    fun capture(path: String): ByteArray = scene.render().use { image -> image.encodeToData()!!.use { data ->
                        data.bytes.also { Files.write(output.resolve(path), it) }
                    } }
                    val original = capture(name)
                    if (name == "result.png") {
                        // First layer thumbnail in the inspected 1280 x 720 layout. No native UI involved.
                        scene.sendPointerEvent(PointerEventType.Press, Offset(684f, 495f), button = PointerButton.Primary)
                        scene.sendPointerEvent(PointerEventType.Release, Offset(684f, 495f), button = PointerButton.Primary)
                        delay(30)
                        check(!original.contentEquals(capture("layer.png"))) { "Layer selection did not update the preview" }
                    }
                }
                finally { scene.close() }
            }
            vm.sourceWorkflow.detectService()
            check(vm.sourceWorkflow.state.value.connected) { "Local API unavailable" }
            render("empty.png")
            suspend fun action(name: String, path: String) {
                vm.sourceWorkflow.execute(name, buildJsonObject { put("path", path) })
                withTimeout(30000) { vm.sourceWorkflow.state.first { !it.busy } }
                check(vm.sourceWorkflow.state.value.error == null) { vm.sourceWorkflow.state.value.error.orEmpty() }
            }
            action("select_image", args[0])
            action("stage_result", args[1])
            render("result.png")
            println("WORKFLOW_PAGE_RENDERED input=${vm.sourceWorkflow.state.value.input!!.sha256} layers=${vm.sourceWorkflow.state.value.candidate!!.layers.size}")
        } finally { vm.close() }
    }
}
