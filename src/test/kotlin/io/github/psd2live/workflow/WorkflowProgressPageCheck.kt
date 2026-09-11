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
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/** Controlled HTTP fixture drives the real controller and UI; never launches a GPU job or a window. */
object WorkflowProgressPageCheck {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @JvmStatic fun main(args: Array<String>) = runBlocking<Unit> {
        val output = Path.of(args[2]); Files.createDirectories(output)
        System.setProperty("psd2live.agent.store", output.resolve("workspace").toString())
        val ready = CompletableDeferred<Unit>()
        val submits = AtomicInteger()
        val psd = Files.readAllBytes(Path.of(args[1]))
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/gradio_api/info") { call.respondText(buildJsonObject {
                    putJsonObject("named_endpoints") { putJsonObject("/decompose") { putJsonArray("parameters") {
                        listOf("image", "resolution", "seed", "split", "offload").forEach { name ->
                            add(buildJsonObject { put("parameter_name", name) })
                        }
                    } } }
                }.toString(), ContentType.Application.Json) }
                post("/gradio_api/upload") {
                    call.receiveMultipart().forEachPart { it.release() }
                    call.respondText("[\"/uploaded/source.png\"]", ContentType.Application.Json)
                }
                post("/gradio_api/call/decompose") {
                    submits.incrementAndGet()
                    call.respondText("{\"event_id\":\"progress-check\"}", ContentType.Application.Json)
                }
                get("/gradio_api/call/decompose/progress-check") {
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        write("event: generating\ndata: [null,[],\"正在拆图，等待图层生成。\"]\n\n"); flush()
                        ready.await()
                        write("event: complete\ndata: [{\"url\":\"/gradio_api/file=result.psd\"},[],\"完成\"]\n\n"); flush()
                    }
                }
                get("/gradio_api/file=result.psd") { call.respondBytes(psd, ContentType.Application.OctetStream) }
            }
        }
        val vm = PSD2LiveViewModel().apply {
            presentationActive = false; setLanguage(AppLanguage.CHINESE); setWorkspaceTab(WorkspaceTab.SEE_THROUGH)
        }
        try {
            server.start(false)
            suspend fun action(name: String, vararg values: Pair<String, String>) {
                vm.sourceWorkflow.execute(name, JsonObject(values.associate { it.first to JsonPrimitive(it.second) }))
                withTimeout(30000) { vm.sourceWorkflow.state.first { !it.busy } }
                check(vm.sourceWorkflow.state.value.error == null) { vm.sourceWorkflow.state.value.error.orEmpty() }
            }
            suspend fun render(name: String) = withContext(Dispatchers.Main) {
                val scene = ImageComposeScene(1280, 720) {
                    CompactToolTheme {
                        val state by vm.state.collectAsState()
                        WorkspaceView(state, vm, sourceWorkflow = { SourceWorkflowPage(vm, null, autoDetect = false) })
                    }
                }
                try {
                    scene.render(System.nanoTime()).close()
                    delay(450)
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { Files.write(output.resolve(name), it.bytes) } }
                } finally { scene.close() }
            }
            action("connect", "endpoint" to "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}")
            action("select_image", "path" to args[0])
            vm.sourceWorkflow.execute("decompose", buildJsonObject { put("image", args[0]) })
            withTimeout(10000) { vm.sourceWorkflow.state.first { it.progress?.phase == WorkflowPhase.GENERATING } }
            val started = vm.sourceWorkflow.state.value.progress!!.startedNanos
            delay(2200)
            render("running.png")
            check(vm.sourceWorkflow.state.value.progress!!.elapsedSeconds() >= 2)
            check(vm.sourceWorkflow.state.value.progress!!.fraction == null)
            // Leaving and returning to the page must not reset the running operation clock.
            vm.setWorkspaceTab(WorkspaceTab.PREVIEW)
            vm.setWorkspaceTab(WorkspaceTab.SEE_THROUGH)
            render("returned.png")
            check(started == vm.sourceWorkflow.state.value.progress!!.startedNanos)
            action("cancel_wait")
            check(vm.sourceWorkflow.state.value.progress!!.outcome == WorkflowOutcome.STOPPED)
            check(vm.sourceWorkflow.record().eventId == "progress-check")
            render("stopped.png")
            ready.complete(Unit)
            action("resume")
            check(vm.sourceWorkflow.state.value.progress!!.outcome == WorkflowOutcome.COMPLETED)
            check(vm.sourceWorkflow.state.value.candidate != null)
            check(submits.get() == 1) { "Resume submitted another job" }
            render("completed.png")
            println("PROGRESS_PAGE_OK running_clock=true tab_return=true cancel=true resume=true submits=${submits.get()} layers=${vm.sourceWorkflow.state.value.candidate!!.layers.size}")
        } finally {
            ready.complete(Unit); vm.close(); server.stop(0, 500)
        }
    }
}
