package io.github.psd2live.workflow

import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import io.github.psd2live.project.ProjectArchive
import java.nio.file.Files
import kotlin.test.*

class SeeThroughClientTest {
    @Test fun supportsRealGradioUploadEventDownloadContractWithoutResubmission() = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-see-through-client-test")
        var submits = 0
        var multipartSeen = false
        val psd = syntheticPsd()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/gradio_api/info") { call.respondText(buildJsonObject {
                    putJsonObject("named_endpoints") { putJsonObject("/decompose") { putJsonArray("parameters") {
                        listOf("image", "resolution", "seed", "split", "offload").forEach { name -> add(buildJsonObject {
                            put("parameter_name", name)
                            when (name) { "resolution" -> put("parameter_default", 896); "seed" -> put("parameter_default", 73); "offload" -> put("parameter_default", false) }
                        }) }
                    } } }
                }.toString(), ContentType.Application.Json) }
                post("/gradio_api/upload") {
                    call.receiveMultipart().forEachPart { part -> if (part is PartData.FileItem) multipartSeen = part.name == "files"; part.release() }
                    call.respondText("[\"/uploaded/source.png\"]", ContentType.Application.Json)
                }
                post("/gradio_api/call/decompose") {
                    val values = Json.parseToJsonElement(call.receiveText()).jsonObject.getValue("data").jsonArray
                    assertEquals("/uploaded/source.png", values[0].jsonObject.getValue("path").jsonPrimitive.content)
                    assertEquals(1024, values[1].jsonPrimitive.int)
                    submits++
                    call.respondText("{\"event_id\":\"event-1\"}", ContentType.Application.Json)
                }
                get("/gradio_api/call/decompose/event-1") {
                    call.respondText("event: generating\ndata: [null,[],\"working\"]\n\nevent: generating\ndata: [{\"url\":\"/gradio_api/file=result.psd\"},[],\"done\"]\n\nevent: complete\ndata: [null,null,null]\n\n", ContentType.Text.EventStream)
                }
                get("/gradio_api/file=result.psd") { call.respondBytes(psd, ContentType.Application.OctetStream) }
            }
        }
        val client = SeeThroughClient()
        try {
            server.start(false)
            val port = server.engine.resolvedConnectors().single().port
            val endpoint = client.connect("http://127.0.0.1:$port")
            val defaults = client.describe(endpoint).defaults
            assertEquals(896, defaults.resolution); assertEquals(73, defaults.seed); assertFalse(defaults.offload)
            val vm = io.github.psd2live.ui.state.PSD2LiveViewModel().apply { presentationActive = false }
            try {
                vm.sourceWorkflow.execute("connect", buildJsonObject { put("endpoint", endpoint) })
                withTimeout(5000) { vm.sourceWorkflow.state.first { !it.busy } }
                assertEquals(73, vm.sourceWorkflow.state.value.options.seed)
                vm.sourceWorkflow.setOptions(defaults.copy(seed = 19))
                vm.sourceWorkflow.detectService()
                assertTrue(vm.sourceWorkflow.state.value.connected)
                assertEquals(19, vm.sourceWorkflow.state.value.options.seed)
                assertEquals(0, submits, "Detection must never submit a GPU job")
            } finally { vm.close() }
            val image = Files.write(root.resolve("source.png"), byteArrayOf(1, 2, 3))
            val event = client.submit(endpoint, image, DecomposeOptions())
            val messages = mutableListOf<String>()
            val result = client.receive(endpoint, event, root.resolve("first"), messages::add)
            assertContentEquals(psd, Files.readAllBytes(result))
            // Gradio consumes events. Resume from durable result metadata, never require replay.
            client.receive(endpoint, event, root.resolve("first")) { }
            assertTrue(multipartSeen)
            assertEquals(1, submits)
            assertEquals(listOf("working", "done"), messages)
        } finally { client.close(); server.stop(0, 500); ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun requiresLocalEndpointAndExactApiPath() {
        assertEquals("http://127.0.0.1:7866", SeeThroughClient.endpoint("http://127.0.0.1:7866/"))
        for (url in listOf("https://example.com", "http://127.0.0.1:7866/other", "http://user@localhost:7866", "http://localhost:7866?x=1")) {
            assertFailsWith<IllegalArgumentException> { SeeThroughClient.endpoint(url) }
        }
    }
}
