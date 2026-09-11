package io.github.psd2live.workflow

import io.github.psd2live.project.ProjectArchive
import io.ktor.client.plugins.ResponseException
import io.ktor.http.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import kotlin.test.*

class SeeThroughDownloadResumeTest {
    @Test fun recoversSavedWindowsResultAfterFailedDownloadWithoutReplayingOrSubmitting() = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-download-resume-")
        val bytes = syntheticPsd()
        val path = "M:\\人物 文件\\原图#1.psd"
        var requests = 0
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing { get("/{path...}") {
                assertEquals("/gradio_api/file=$path", URI(call.request.uri).path)
                assertNull(URI(call.request.uri).query)
                requests++
                if (requests == 1) call.respondText("temporary error", status = HttpStatusCode.ServiceUnavailable)
                else call.respondBytes(bytes, ContentType.Application.OctetStream)
            } }
        }
        val client = SeeThroughClient()
        try {
            server.start(false)
            val endpoint = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            val metadata = root.resolve("result-saved-event.json")
            val saved = buildJsonObject {
                put("endpoint", endpoint)
                putJsonObject("file") { put("url", "$endpoint/gradio_api/file=$path") }
            }.toString()
            Files.writeString(metadata, saved)
            assertFailsWith<ResponseException> { client.receive(endpoint, "saved-event", root) { } }
            assertEquals(saved, Files.readString(metadata))
            assertFalse(Files.exists(root.resolve("see-through-saved-event.psd")))
            val progress = mutableListOf<WorkflowProgressUpdate>()
            val result = client.receive(endpoint, "saved-event", root, { progress.add(it) }) { error("Must not replay SSE") }
            assertContentEquals(bytes, Files.readAllBytes(result))
            assertEquals(bytes.size.toLong(), progress.last().bytes)
            assertTrue(progress.all { it.phase == WorkflowPhase.DOWNLOADING })
            assertEquals(2, requests)
        } finally { client.close(); server.stop(0, 500); ProjectArchive.deleteTemporaryDirectory(root) }
    }
}
