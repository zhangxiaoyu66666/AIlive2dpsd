package io.github.psd2live.workflow

import io.github.psd2live.core.PipelineResult
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant

data class SourceVersion(val path: String, val snapshot: String, val sha256: String, val bytes: Long, val modified: Long, val capturedAt: String)
data class SourceWorkflowRecord(
    val endpoint: String = "http://127.0.0.1:7866",
    val eventId: String? = null,
    val confirmed: SourceVersion? = null,
    val imported: SourceVersion? = null,
    val parentProjectId: String? = null,
    val generation: JsonObject? = null,
    val decomposition: JsonObject? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("endpoint", endpoint); put("eventId", eventId)
        put("confirmed", confirmed?.toJson() ?: JsonNull); put("imported", imported?.toJson() ?: JsonNull)
        put("parentProjectId", parentProjectId); put("generation", generation ?: JsonNull); put("decomposition", decomposition ?: JsonNull)
    }
    companion object {
        fun fromJson(value: JsonObject): SourceWorkflowRecord {
            require(value.getValue("schemaVersion").jsonPrimitive.int == 1) { "Unsupported source workflow version" }
            fun version(key: String) = value[key]?.takeUnless { it is JsonNull }?.jsonObject?.let(::sourceVersion)
            return SourceWorkflowRecord(value.getValue("endpoint").jsonPrimitive.content,
                value["eventId"]?.jsonPrimitive?.contentOrNull, version("confirmed"), version("imported"),
                value["parentProjectId"]?.jsonPrimitive?.contentOrNull, value["generation"]?.takeUnless { it is JsonNull }?.jsonObject, value["decomposition"]?.takeUnless { it is JsonNull }?.jsonObject)
        }
    }
}

private fun SourceVersion.toJson() = buildJsonObject {
    put("path", path); put("snapshot", snapshot); put("sha256", sha256); put("bytes", bytes)
    put("modified", modified); put("capturedAt", capturedAt)
}
private fun sourceVersion(value: JsonObject): SourceVersion = SourceVersion(
    value.getValue("path").jsonPrimitive.content, value.getValue("snapshot").jsonPrimitive.content,
    value.getValue("sha256").jsonPrimitive.content.also { require(it.matches(Regex("[0-9a-f]{64}"))) },
    value.getValue("bytes").jsonPrimitive.long, value.getValue("modified").jsonPrimitive.long,
    value.getValue("capturedAt").jsonPrimitive.content,
)

/** Work only on immutable copies; later Photoshop saves never alter the imported bytes. */
object SourceVersions {
    const val MAX_PSD_BYTES = 1024L * 1024 * 1024
    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun capture(source: Path, directory: Path): SourceVersion {
        val path = source.toAbsolutePath().normalize()
        require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".psd", true)) { "Select a PSD file" }
        val size = Files.size(path); val modified = Files.getLastModifiedTime(path).toMillis()
        require(size in 1..MAX_PSD_BYTES) { "PSD exceeds the 1 GiB import budget" }
        Files.createDirectories(directory)
        val target = directory.resolve("${java.util.UUID.randomUUID()}.psd")
        try {
            Files.copy(path, target)
            val hash = sha256(target)
            check(Files.size(path) == size && Files.getLastModifiedTime(path).toMillis() == modified && sha256(path) == hash) {
                "PSD changed during capture; finish saving it and select the version again"
            }
            return SourceVersion(path.toString(), target.toString(), hash, size, modified, Instant.now().toString())
        } catch (failure: Throwable) { Files.deleteIfExists(target); throw failure }
    }

    fun verify(version: SourceVersion) { check(sha256(Path.of(version.snapshot)) == version.sha256) { "Source snapshot checksum mismatch" } }
    fun changed(version: SourceVersion): Boolean = !Files.isRegularFile(Path.of(version.path)) || sha256(Path.of(version.path)) != version.sha256

    fun receipt(record: SourceWorkflowRecord, projectId: String?, historyHead: String?, result: PipelineResult, output: Path): JsonObject = buildJsonObject {
        put("outputDirectory", output.toAbsolutePath().normalize().toString())
        put("schemaVersion", 1); put("generatedAt", Instant.now().toString()); put("projectId", projectId); put("historyHead", historyHead)
        put("confirmedSha256", record.confirmed?.sha256); put("importedSha256", record.imported?.sha256)
        putJsonArray("files") { result.exportedFiles.forEach { file -> add(buildJsonObject {
            put("path", output.toAbsolutePath().normalize().relativize(file.path.toAbsolutePath().normalize()).toString().replace('\\', '/')); put("bytes", file.bytes); put("sha256", sha256(file.path))
        }) } }
    }

    fun restore(record: SourceWorkflowRecord, root: Path): SourceWorkflowRecord = record.copy(
        imported = record.imported?.copy(snapshot = root.resolve("source/original.psd").toString()),
        confirmed = record.confirmed?.copy(snapshot = root.resolve("source/confirmed.psd").toString()),
    ).also { restored -> restored.imported?.let(::verify); restored.confirmed?.let(::verify) }

    fun writeReceipt(output: Path, receipt: JsonObject) {
        writeJsonAtomically(output.resolve("source-lineage.json"), receipt)
    }

    internal fun writeJsonAtomically(target: Path, value: JsonObject) {
        val temporary = Files.createTempFile(target.parent, ".source-lineage-", ".tmp")
        try {
            Files.writeString(temporary, value.toString())
            java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
