package io.github.psd2live.workflow

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
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
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Opt-in recovery of an existing result. No uploads, inference submissions, or native windows. */
object SourceWorkflowResumeCheck {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        val original = Path.of(args[0]); val output = Path.of(args[1]); Files.createDirectories(output)
        System.setProperty("psd2live.agent.store", output.resolve("workspace").toString())
        val metadata = Json.parseToJsonElement(Files.readString(original)).jsonObject
        val endpoint = metadata.getValue("endpoint").jsonPrimitive.content
        val event = original.fileName.toString().removePrefix("result-").removeSuffix(".json")
        Files.copy(original, output.resolve(original.fileName), REPLACE_EXISTING)
        val source = Path.of(metadata.getValue("file").jsonObject.getValue("path").jsonPrimitive.content)
        val client = SeeThroughClient()
        val vm = PSD2LiveViewModel().apply {
            presentationActive = false; setLanguage(AppLanguage.CHINESE); setWorkspaceTab(WorkspaceTab.SEE_THROUGH)
        }
        try {
            val progress = java.util.concurrent.CopyOnWriteArrayList<WorkflowProgressUpdate>()
            val result = client.receive(endpoint, event, output, { progress.add(it) }) { error("Saved result must not replay SSE") }
            check(SourceVersions.sha256(source) == SourceVersions.sha256(result)) { "Downloaded bytes differ from the original PSD" }
            check(progress.last().bytes == Files.size(source))
            suspend fun action(name: String, vararg values: Pair<String, String>) {
                vm.sourceWorkflow.execute(name, JsonObject(values.associate { it.first to JsonPrimitive(it.second) }))
                withTimeout(30000) { vm.sourceWorkflow.state.first { !it.busy } }
                check(vm.sourceWorkflow.state.value.error == null) { vm.sourceWorkflow.state.value.error.orEmpty() }
            }
            action("connect", "endpoint" to endpoint)
            action("select_image", "path" to args[2])
            action("stage_result", "path" to result.toString())
            val candidate = checkNotNull(vm.sourceWorkflow.state.value.candidate)
            withContext(Dispatchers.Main) {
                val scene = ImageComposeScene(1280, 720) {
                    CompactToolTheme {
                        val state by vm.state.collectAsState()
                        WorkspaceView(state, vm, sourceWorkflow = { SourceWorkflowPage(vm, null, autoDetect = false) })
                    }
                }
                try {
                    scene.render(System.nanoTime()).close(); delay(100)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(output.resolve("recovered.png"), it.bytes) } }
                } finally { scene.close() }
            }
            println("WINDOWS_RESUME_OK event=$event bytes=${Files.size(result)} layers=${candidate.layers.size} sha256=${candidate.version.sha256} preview=true")
        } finally { client.close(); vm.close() }
    }
}
