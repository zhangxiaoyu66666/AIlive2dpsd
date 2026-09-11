package io.github.psd2live.workflow

import io.github.psd2live.agent.ViewModelAgentWorkspace
import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.project.ProjectSession
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SourceWorkflowIntegrationTest {
    @Test fun importedModelGeneratesAndReopensWithPortableLineage() = runBlocking<Unit> {
        val root = Files.createTempDirectory("psd2live-project-workflow-integration-")
        val vm = PSD2LiveViewModel().apply { presentationActive = false }
        val workspace = ViewModelAgentWorkspace(vm, root.resolve("history"))
        val reopened = PSD2LiveViewModel().apply { presentationActive = false }
        val reopenedWorkspace = ViewModelAgentWorkspace(reopened, root.resolve("reopened-history"))
        try {
            val original = Files.write(root.resolve("edited.psd"), syntheticPsd())
            val confirmed = SourceVersions.capture(original, root.resolve("versions"))
            Files.write(original, syntheticPsd("face", 110))
            val imported = SourceVersions.capture(original, root.resolve("versions"))
            withContext(Dispatchers.Main) {
                vm.attachAgentWorkspace(workspace)
                vm.setStateForTest(vm.state.value.copy(atlasSize = 512))
                vm.setSourceWorkflow(SourceWorkflowRecord(confirmed = confirmed, imported = imported))
                vm.setInputPath(imported.snapshot)
                vm.analyze()
                assertTrue(vm.state.value.isAnalyzing)
            }
            withTimeout(30000) { vm.state.first { !it.isAnalyzing } }
            assertNull(vm.state.value.errorMessage)
            assertNotNull(vm.state.value.analysis)
            assertNotNull(vm.state.value.historySnapshot)
            val head = workspace.snapshot().historyHeadNodeId!!
            val response = vm.sourceWorkflow.execute("generate", buildJsonObject {
                put("expected_history_head_node_id", head); put("output", root.resolve("exports").toString())
            })
            assertTrue(response.getValue("busy").jsonPrimitive.boolean)
            withTimeout(30000) { vm.state.first { !it.isGenerating } }
            assertNull(vm.state.value.errorMessage)
            val receipt = vm.state.value.sourceWorkflow!!.generation!!
            assertEquals(head, receipt.getValue("historyHead").jsonPrimitive.content)
            assertEquals(imported.sha256, receipt.getValue("importedSha256").jsonPrimitive.content)
            val output = Path.of(receipt.getValue("outputDirectory").jsonPrimitive.content)
            assertTrue(Files.isRegularFile(output.resolve("source-lineage.json")))
            val project = root.resolve("saved.psd2live")
            withContext(Dispatchers.Main) { ProjectSession(vm).save(workspace, project) }
            Files.delete(original); Files.delete(Path.of(confirmed.snapshot)); Files.delete(Path.of(imported.snapshot))
            withContext(Dispatchers.Main) {
                reopened.attachAgentWorkspace(reopenedWorkspace)
                ProjectSession(reopened).open(reopenedWorkspace, project)
            }
            val lineage = reopened.state.value.sourceWorkflow!!
            assertEquals(confirmed.sha256, lineage.confirmed!!.sha256)
            assertEquals(imported.sha256, lineage.imported!!.sha256)
            assertEquals(receipt, lineage.generation)
            SourceVersions.verify(lineage.confirmed); SourceVersions.verify(lineage.imported)
            assertNotNull(reopened.state.value.analysis)
        } finally {
            workspace.flushProjectPersistence(); reopenedWorkspace.flushProjectPersistence()
            vm.close(); reopened.close(); workspace.close(); reopenedWorkspace.close()
            ProjectArchive.deleteTemporaryDirectory(root)
        }
    }
}
