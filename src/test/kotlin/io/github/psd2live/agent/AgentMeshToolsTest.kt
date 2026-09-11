package io.github.psd2live.agent

import io.github.psd2live.core.*
import io.github.psd2live.history.WorkspaceHistoryTree
import io.github.psd2live.project.WorkspaceStateCodec
import io.github.psd2live.ui.state.PSD2LiveState
import kotlinx.serialization.json.*
import org.umamo.runtime.model.*
import java.nio.file.Files
import kotlin.test.*

class AgentMeshToolsTest {
    private val config = PipelineConfig(atlasSize = 256, mouthOutlineEnabled = false)
    private val source = WorkspaceSourceArt(40, 40, listOf(rasterLayer("eyebrow-r", 20, 3, 5, 10),
        rasterLayer("eyebrow-l", 20, 3, 5, 20, 1)), emptyList())
    private fun rig(): BuiltRig {
        val analysis = CharacterAnalyzer.analyze(source, config)
        return RigBuilder.build(analysis, AtlasPacker.pack(analysis.layers, 256, 2), config)
    }
    private fun document() = AgentWorkspaceDocument(source, emptyMap(), emptySet(), emptyMap(), emptyMap(), RigEditOverlay.Empty,
        WorkspaceStateCodec.settings(PSD2LiveState(atlasSize = 256, mouthOutlineEnabled = false)))
    private fun request(settings: String, ids: String = "\"eyebrow-r\"") =
        Json.parseToJsonElement("""{"layer_ids":[$ids],"settings":$settings,"expected_history_head_node_id":"head"}""").jsonObject

    @Test fun partialOverrideAndResetSurviveHistoryPersistence() {
        val rig = rig(); val original = document()
        val updated = changeMeshSettings(original, rig.puppet, rig.layerIdByDrawableId, request("""{"max_edge_distance":2}"""))
        assertEquals(2f, updated.meshOverrides.getValue("eyebrow-r").maxEdgeDistance)
        assertFalse("eyebrow-l" in updated.meshOverrides)
        assertSame(original.source, updated.source)
        assertSame(original.rigEdits, updated.rigEdits)
        assertEquals(updated.meshOverrides, WorkspaceStateCodec.decode(updated.settings).meshOverrides)
        val root = Files.createTempDirectory("psd2live-mesh-history-test")
        try {
            val history = WorkspaceHistoryTree(updated, "revision", "hash")
            val store = AgentWorkspaceStore(root)
            store.persistHistory("project", history.state())
            val restored = assertNotNull(store.loadHistory("project")).head().snapshot
            assertEquals(updated.meshOverrides, restored.meshOverrides)
            assertEquals(updated.settings, restored.settings)
        } finally { root.toFile().deleteRecursively() }
        val reset = changeMeshSettings(updated, rig.puppet, rig.layerIdByDrawableId, request("null"))
        assertTrue(reset.meshOverrides.isEmpty())
        assertTrue(WorkspaceStateCodec.decode(reset.settings).meshOverrides.isEmpty())
    }

    @Test fun invalidBatchAndVertexAuthoredMeshCannotBePartiallyChanged() {
        val rig = rig(); val doc = document()
        assertFailsWith<IllegalArgumentException> {
            changeMeshSettings(doc, rig.puppet, rig.layerIdByDrawableId, request("""{"max_edge_distance":0}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            changeMeshSettings(doc, rig.puppet, rig.layerIdByDrawableId, request("""{"max_edge_distance":2}""", "\"eyebrow-r\",\"missing\""))
        }
        val mesh = rig.puppet.drawables.first()
        val edit = RigKeyformSetEdit(RigTargetRef(RigTargetKind.ART_MESH, mesh.id.raw), mapOf("ParamBrowRY" to 0f),
            geometry = RigKeyformGeometryEdit(positionDeltas = List(mesh.mesh!!.positions.size) { 0f }))
        val authored = doc.copy(rigEdits = doc.rigEdits.setKeyform(edit))
        assertFailsWith<IllegalArgumentException> {
            changeMeshSettings(authored, rig.puppet, rig.layerIdByDrawableId,
                request("""{"max_edge_distance":2}""", "\"eyebrow-r\",\"eyebrow-l\""))
        }
        assertTrue(doc.meshOverrides.isEmpty())
        assertEquals(listOf(edit), authored.rigEdits.keyformSetEdits)
    }

    @Test fun objectJsonReportsActualDrawOrderAtAllSeededCoordinates() {
        val base = rig()
        val mesh = base.puppet.drawables.first()
        val edit = RigKeyformSetEdit(RigTargetRef(RigTargetKind.ART_MESH, mesh.id.raw), mapOf("ParamBrowRY" to 0f),
            channels = RigKeyformChannelsEdit(drawOrder = 99f))
        val edited = base.copy(puppet = RigEditOverlay(keyformSetEdits = listOf(edit)).applyTo(base.puppet))
        val json = inspectRigObject(edited, AgentKeyformTargetRef("mesh", mesh.id.raw)).toJson()
        val track = json.getValue("channels").jsonArray.first { it.jsonObject["channel"]?.jsonPrimitive?.content == "draw_order" }.jsonObject
        val cells = track.getValue("cells").jsonArray
        assertEquals(3, cells.size)
        val values = cells.associate { it.jsonObject.getValue("coordinate").jsonObject.getValue("ParamBrowRY").jsonPrimitive.float to
            it.jsonObject.getValue("value").jsonPrimitive.float }
        assertEquals(99f, values[0f])
        assertEquals(mesh.drawOrder, values[-1f])
        assertEquals(mesh.drawOrder, values[1f])
    }
}
