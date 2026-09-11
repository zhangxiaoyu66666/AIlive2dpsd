package io.github.psd2live.workflow

import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class SourceWorkflowControllerTest {
    @Test fun editButtonConfirmsImportsAndReturnsToExistingEditor() = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-edit-button-")
        val vm = PSD2LiveViewModel().apply { presentationActive = false }
        val controller = SourceWorkflowController(vm, root)
        try {
            val source = Files.write(root.resolve("model.psd"), syntheticPsd())
            controller.execute("stage_result", buildJsonObject { put("path", source.toString()) })
            withTimeout(10000) { controller.state.first { !it.busy } }
            var imports = 0
            var returned = false
            controller.importVersion = { version, lineage, activate ->
                assertTrue(activate)
                assertEquals(version.sha256, lineage.confirmed!!.sha256)
                imports++
                "editor-tab"
            }
            controller.openImportedVersion = { assertEquals("editor-tab", it); returned = true; true }
            controller.editInApplication()
            withTimeout(10000) { controller.state.first { it.importedTabId == "editor-tab" && !it.busy } }
            controller.editInApplication()
            assertTrue(returned)
            assertEquals(1, imports)
        } finally { controller.close(); vm.close(); ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun importRequiresReviewedHashAndKeepsManualRevisionLineage() = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-source-controller-test")
        val vm = PSD2LiveViewModel().apply { presentationActive = false }
        val controller = SourceWorkflowController(vm, root.resolve("workspace"))
        try {
            suspend fun action(name: String, vararg args: Pair<String, String>) {
                controller.execute(name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))
                withTimeout(10000) { controller.state.first { !it.busy } }
            }
            val file = Files.write(root.resolve("model.psd"), syntheticPsd())
            action("stage_result", "path" to file.toString())
            assertNull(controller.state.value.error)
            val confirmedHash = controller.state.value.candidate!!.version.sha256
            val preview = controller.execute("state", buildJsonObject { put("include_preview", true) })
            assertEquals(confirmedHash, preview.getValue("previewSha256").jsonPrimitive.content)
            val png = java.util.Base64.getDecoder().decode(preview.getValue("previewPngBase64").jsonPrimitive.content)
            assertEquals(32, javax.imageio.ImageIO.read(png.inputStream()).width)
            action("import", "sha256" to confirmedHash)
            assertNotNull(controller.state.value.error)
            action("confirm", "sha256" to "stale")
            assertNull(controller.record().confirmed)
            action("confirm", "sha256" to confirmedHash)
            assertEquals(confirmedHash, controller.record().confirmed!!.sha256)
            val editable = root.resolve("editable.psd")
            action("save_psd", "path" to editable.toString(), "sha256" to confirmedHash)
            assertNull(controller.state.value.error)
            assertEquals(confirmedHash, SourceVersions.sha256(editable))
            assertEquals(editable.toString(), controller.state.value.importCandidate!!.version.path)
            Files.write(file, syntheticPsd("face", 110))
            action("stage_import", "path" to file.toString())
            val importedHash = controller.state.value.importCandidate!!.version.sha256
            assertNotEquals(confirmedHash, importedHash)
            var imported: SourceWorkflowRecord? = null
            controller.importVersion = { _, record, activate ->
                assertFalse(activate)
                imported = record
                "separate-tab"
            }
            action("import", "sha256" to confirmedHash)
            assertNull(imported)
            action("import", "sha256" to importedHash)
            assertNull(controller.state.value.error)
            assertEquals(confirmedHash, imported!!.confirmed!!.sha256)
            assertEquals(importedHash, imported!!.imported!!.sha256)
            assertEquals("separate-tab", controller.state.value.importedTabId)
            assertNull(vm.state.value.sourceWorkflow) // Caller decides the destination; original VM untouched.
        } finally { controller.close(); vm.close(); ProjectArchive.deleteTemporaryDirectory(root) }
    }

    @Test fun incompleteConnectionCannotSubmitAndCancellationLeavesIdle() = runBlocking<Unit> {
        val vm = PSD2LiveViewModel().apply { presentationActive = false }
        try {
            vm.sourceWorkflow.execute("decompose", buildJsonObject { put("image", "unused.png") })
            withTimeout(5000) { vm.sourceWorkflow.state.first { !it.busy } }
            assertNotNull(vm.sourceWorkflow.state.value.error)
            assertNull(vm.sourceWorkflow.record().eventId)
            vm.sourceWorkflow.execute("cancel_wait", buildJsonObject { })
            assertFalse(vm.sourceWorkflow.state.value.busy)
            vm.setStateForTest(vm.state.value.copy(isGenerating = true, statusText = "exporting"))
            assertTrue(vm.sourceWorkflow.snapshot().getValue("busy").jsonPrimitive.boolean)
            assertEquals("exporting", vm.sourceWorkflow.snapshot().getValue("projectStatus").jsonPrimitive.content)
        } finally { vm.close() }
    }
}
