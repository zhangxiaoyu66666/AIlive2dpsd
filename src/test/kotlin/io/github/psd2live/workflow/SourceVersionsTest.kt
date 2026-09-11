package io.github.psd2live.workflow

import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PipelineConfig
import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.project.WorkspaceStateCodec
import io.github.psd2live.ui.state.PSD2LiveState
import kotlinx.serialization.json.*
import org.umamo.format.psd.PsdSyntheticTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

internal fun syntheticPsd(name: String = "face", red: Byte = 100): ByteArray = PsdSyntheticTest().buildRgbPsd(
    32, 32, name, ByteArray(1024) { red }, ByteArray(1024) { 60 }, ByteArray(1024) { 80 }, ByteArray(1024) { -1 }, 0)

class SourceVersionsTest {
    @Test fun savedArchiveRestoresBothVersionsWithoutOriginalFiles() {
        val root = Files.createTempDirectory("psd2live-project-source-portable-")
        var extracted: Path? = null
        try {
            val file = Files.write(root.resolve("source.psd"), syntheticPsd())
            val confirmed = SourceVersions.capture(file, root.resolve("copies"))
            Files.write(file, syntheticPsd("eyewhite", 120))
            val imported = SourceVersions.capture(file, root.resolve("copies"))
            val record = SourceWorkflowRecord(confirmed = confirmed, imported = imported)
            val staging = Files.createDirectories(root.resolve("staging/source")).parent
            Files.copy(Path.of(confirmed.snapshot), staging.resolve("source/confirmed.psd"))
            Files.copy(Path.of(imported.snapshot), staging.resolve("source/original.psd"))
            ProjectArchive.writeJson(staging.resolve("workspace.json"), WorkspaceStateCodec.encode(PSD2LiveState(sourceWorkflow = record)))
            val project = root.resolve("portable.psd2live")
            ProjectArchive.write(staging, project, "portable")
            Files.delete(file); Files.delete(Path.of(confirmed.snapshot)); Files.delete(Path.of(imported.snapshot))
            val restoredRoot = ProjectArchive.extract(project).also { extracted = it }
            val decoded = WorkspaceStateCodec.decode(ProjectArchive.readJson(restoredRoot.resolve("workspace.json")))
            val restored = SourceVersions.restore(decoded.sourceWorkflow!!, restoredRoot)
            assertEquals(confirmed.sha256, restored.confirmed!!.sha256)
            assertEquals(imported.sha256, restored.imported!!.sha256)
            assertNotEquals(confirmed.snapshot, restored.confirmed.snapshot)
            Files.write(Path.of(restored.confirmed.snapshot), byteArrayOf(0))
            assertFailsWith<IllegalStateException> { SourceVersions.restore(record, restoredRoot) }
        } finally { extracted?.let(ProjectArchive::deleteTemporaryDirectory); ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun samePathCanHaveDistinctConfirmedAndImportedVersions() {
        val root = Files.createTempDirectory("psd2live-project-source-versions-test")
        try {
            val file = Files.write(root.resolve("绘制.psd"), syntheticPsd())
            val confirmed = SourceVersions.capture(file, root.resolve("snapshots"))
            Files.write(file, syntheticPsd("eyewhite", 120))
            val imported = SourceVersions.capture(file, root.resolve("snapshots"))
            assertNotEquals(confirmed.sha256, imported.sha256)
            assertEquals(confirmed.path, imported.path)
            assertTrue(SourceVersions.changed(confirmed)); assertFalse(SourceVersions.changed(imported))
            SourceVersions.verify(confirmed); SourceVersions.verify(imported)
            Files.delete(file)
            SourceVersions.verify(imported)
            assertTrue(SourceVersions.changed(imported))
            val lineage = SourceWorkflowRecord(confirmed = confirmed, imported = imported, parentProjectId = "prior")
            val state = PSD2LiveState(sourceWorkflow = lineage)
            assertEquals(lineage, WorkspaceStateCodec.decode(WorkspaceStateCodec.encode(state)).sourceWorkflow)
            assertNull(WorkspaceStateCodec.decode(buildJsonObject { }).sourceWorkflow)
        } finally { ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun corruptSnapshotsAndFutureSchemasFailClosed() {
        val root = Files.createTempDirectory("psd2live-project-source-corruption-test")
        try {
            val file = Files.write(root.resolve("source.psd"), syntheticPsd())
            val version = SourceVersions.capture(file, root.resolve("copies"))
            Files.write(Path.of(version.snapshot), byteArrayOf(0))
            assertFailsWith<IllegalStateException> { SourceVersions.verify(version) }
            val json = SourceWorkflowRecord().toJson().toMutableMap().apply { put("schemaVersion", JsonPrimitive(2)) }
            assertFailsWith<IllegalArgumentException> { SourceWorkflowRecord.fromJson(JsonObject(json)) }
        } finally { ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun generationReceiptNamesAndHashesTheActualExportFiles() {
        val root = Files.createTempDirectory("psd2live-project-source-receipt-test")
        try {
            val file = Files.write(root.resolve("source.psd"), syntheticPsd())
            val version = SourceVersions.capture(file, root.resolve("copies"))
            val record = SourceWorkflowRecord(confirmed = version, imported = version)
            val output = root.resolve("generated")
            val result = PSD2LivePipeline().run(Path.of(version.snapshot), output, PipelineConfig(atlasSize = 512))
            val receipt = SourceVersions.receipt(record, "project", "head", result, output)
            SourceVersions.writeReceipt(output, receipt)
            assertEquals(version.sha256, receipt.getValue("importedSha256").jsonPrimitive.content)
            assertEquals("head", receipt.getValue("historyHead").jsonPrimitive.content)
            for (entry in receipt.getValue("files").jsonArray) {
                val metadata = entry.jsonObject
                val path = output.resolve(metadata.getValue("path").jsonPrimitive.content)
                assertEquals(SourceVersions.sha256(path), metadata.getValue("sha256").jsonPrimitive.content)
            }
            assertEquals(receipt, ProjectArchive.readJson(output.resolve("source-lineage.json")))
        } finally { ProjectArchive.deleteTemporaryDirectory(root) }
    }
}
