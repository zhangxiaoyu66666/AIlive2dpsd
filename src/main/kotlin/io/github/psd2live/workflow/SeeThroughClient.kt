package io.github.psd2live.workflow

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.timeout
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

data class DecomposeOptions(val resolution: Int = 1024, val seed: Int = 42, val split: Boolean = true, val offload: Boolean = true)

/** The existing Gradio HTTP API; no fork of See-Through or model process management. */
class SeeThroughClient(private val http: HttpClient = HttpClient(CIO) {
    followRedirects = false
    expectSuccess = true
    install(SSE)
    install(HttpTimeout) { connectTimeoutMillis = 5000; socketTimeoutMillis = 90000 }
}) : AutoCloseable {
    suspend fun connect(endpoint: String): String {
        val base = endpoint(endpoint)
        val info = Json.parseToJsonElement(http.get("$base/gradio_api/info").bodyAsText()).jsonObject
        val function = info["named_endpoints"]?.jsonObject?.get("/decompose")?.jsonObject
            ?: error("This Gradio service does not expose /decompose")
        val names = function["parameters"]?.jsonArray?.map { it.jsonObject["parameter_name"]?.jsonPrimitive?.content }
        require(names == listOf("image", "resolution", "seed", "split", "offload")) { "Unsupported See-Through /decompose input contract" }
        return base
    }

    suspend fun submit(endpoint: String, image: Path, options: DecomposeOptions, expectedImageHash: String? = null): String = withContext(Dispatchers.IO) {
        val base = endpoint(endpoint)
        require(options.resolution in 768..1280 && options.resolution % 64 == 0 && options.seed in 0..9999) { "Invalid decomposition options" }
        require(Files.isRegularFile(image) && Files.size(image) in 1..(32L * 1024 * 1024)) { "Select an image up to 32 MiB" }
        val suffix = image.fileName.toString().substringAfterLast('.').lowercase()
        require(suffix in setOf("png", "jpg", "jpeg", "webp")) { "Expected PNG, JPEG or WebP" }
        val imageBytes = Files.readAllBytes(image)
        if (expectedImageHash != null) check(java.security.MessageDigest.getInstance("SHA-256").digest(imageBytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) } == expectedImageHash) { "Image changed before upload; select it again" }
        val uploaded = Json.parseToJsonElement(http.post("$base/gradio_api/upload") {
            setBody(MultiPartFormDataContent(formData {
                append("files", imageBytes, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"source.$suffix\"")
                    append(HttpHeaders.ContentType, if (suffix == "jpg") "image/jpeg" else "image/$suffix")
                })
            }))
        }.bodyAsText()).jsonArray.single().jsonPrimitive.content
        val payload = buildJsonObject { putJsonArray("data") {
            add(buildJsonObject { put("path", uploaded); putJsonObject("meta") { put("_type", "gradio.FileData") } })
            add(JsonPrimitive(options.resolution)); add(JsonPrimitive(options.seed)); add(JsonPrimitive(options.split)); add(JsonPrimitive(options.offload))
        } }
        val response = http.post("$base/gradio_api/call/decompose") { contentType(ContentType.Application.Json); setBody(payload.toString()) }
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("event_id").jsonPrimitive.content.also(::validateEvent)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    suspend fun receive(endpoint: String, eventId: String, destination: Path, status: (String) -> Unit): Path {
        val base = endpoint(endpoint); validateEvent(eventId)
        val metadata = destination.resolve("result-$eventId.json")
        if (Files.isRegularFile(metadata)) {
            val saved = withContext(Dispatchers.IO) { Json.parseToJsonElement(Files.readString(metadata)).jsonObject }
            require(saved["endpoint"]?.jsonPrimitive?.content == base) { "Result belongs to another service" }
            return download(base, eventId, saved.getValue("file").jsonObject, destination)
        }
        var completed: JsonObject? = null
        try { http.sse("$base/gradio_api/call/decompose/$eventId") {
            incoming.filter { it.event != "heartbeat" }.timeout(90.seconds).takeWhile { event ->
                when (event.event) {
                    "error" -> {
                        withContext(Dispatchers.IO) { Files.deleteIfExists(metadata) }
                        error(event.data?.take(1000) ?: "See-Through failed")
                    }
                    "generating", "complete" -> {
                        val data = Json.parseToJsonElement(event.data ?: "[]").jsonArray
                        (data.getOrNull(2) as? JsonPrimitive)?.contentOrNull?.let(status)
                        // Gradio generators end with null/skip outputs. Keep the last actual file.
                        (data.firstOrNull() as? JsonObject)?.takeIf { it["url"] is JsonPrimitive }?.let { file ->
                            completed = file
                            withContext(Dispatchers.IO) {
                                Files.createDirectories(destination)
                                SourceVersions.writeJsonAtomically(metadata, buildJsonObject { put("endpoint", base); put("file", file) })
                            }
                        }
                        if (event.event == "complete") {
                            checkNotNull(completed) { "See-Through completed without a PSD" }
                            false
                        } else true
                    }
                    else -> true
                }
            }.collect { }
        } } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            throw java.io.IOException("No result updates for 90 seconds. Resume the known event or select its generated PSD; do not submit again.", timeout)
        }
        val file = completed ?: error("Connection ended before completion. Resume this event; do not submit again.")
        return download(base, eventId, file, destination)
    }

    internal suspend fun download(endpoint: String, eventId: String, file: JsonObject, destination: Path): Path {
        val base = endpoint(endpoint); validateEvent(eventId)
        val url = file["url"]?.jsonPrimitive?.content ?: error("Result has no download URL")
        val download = URI(base).resolve(url)
        require(download.scheme == URI(base).scheme && download.authority == URI(base).authority && download.path.startsWith("/gradio_api/file=")) {
            "See-Through returned an unexpected download location"
        }
        return withContext(Dispatchers.IO) {
            Files.createDirectories(destination)
            val target = destination.resolve("see-through-$eventId.psd")
            val partial = Files.createTempFile(destination, ".download-", ".psd")
            try {
                http.prepareGet(download.toString()).execute { response ->
                    val input = response.bodyAsChannel(); val buffer = ByteArray(128 * 1024)
                    var total = 0L
                    Files.newOutputStream(partial).use { output ->
                        while (true) {
                            val count = input.readAvailable(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= SourceVersions.MAX_PSD_BYTES) { "PSD exceeds import budget" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                target
            } finally { Files.deleteIfExists(partial) }
        }
    }

    override fun close() { http.close() }

    companion object {
        fun endpoint(value: String): String {
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "[::1]") &&
                uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty()) {
                "Use a local See-Through URL, for example http://127.0.0.1:7866"
            }
            return uri.toString()
        }
        private fun validateEvent(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid event ID" } }
    }
}
