package io.github.psd2live.workflow

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** Explicit opt-in only. Uses the real local service and the production connector, no desktop window. */
object SourceWorkflowLiveCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val image = Path.of(args[0]); val root = Path.of(args[1]).toAbsolutePath()
        Files.createDirectories(root)
        val client = SeeThroughClient()
        try {
            val endpoint = client.connect("http://127.0.0.1:7866")
            println("CONNECTED $endpoint")
            val eventFile = root.resolve("event.txt")
            val event = if (Files.isRegularFile(eventFile)) Files.readString(eventFile).trim() else
                client.submit(endpoint, image, DecomposeOptions()).also { Files.writeString(eventFile, it) }
            println("EVENT $event")
            // An explicitly supplied result URL recovers an already-consumed Gradio event.
            val psd = if (args.size > 2 && args[2].isNotBlank()) client.download(endpoint, event,
                buildJsonObject { put("url", args[2]) }, root) else
                client.receive(endpoint, event, root) { println(it) }
            val confirmed = SourceVersions.capture(psd, root.resolve("versions"))
            SourceVersions.verify(confirmed)
            val imported = SourceVersions.capture(psd, root.resolve("versions"))
            val lineage = SourceWorkflowRecord(endpoint, event, confirmed, imported)
            val output = root.resolve("export")
            val result = PSD2LivePipeline().run(Path.of(imported.snapshot), output, PipelineConfig())
            val receipt = SourceVersions.receipt(lineage, "live-acceptance", null, result, output)
            SourceVersions.writeReceipt(output, receipt)
            val restored = SourceWorkflowRecord.fromJson(lineage.copy(generation = receipt).toJson())
            check(restored == lineage.copy(generation = receipt))
            check(confirmed.sha256 == imported.sha256)
            println("SOURCE_WORKFLOW_OK layers=${result.analysis.layers.size} files=${result.exportedFiles.size} sha256=${imported.sha256}")
        } finally { client.close() }
    }
}
