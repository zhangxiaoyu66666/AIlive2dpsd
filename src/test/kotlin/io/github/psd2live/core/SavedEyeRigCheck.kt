package io.github.psd2live.core

import io.github.psd2live.agent.*
import io.github.psd2live.project.ProjectArchive
import io.github.psd2live.project.WorkspaceStateCodec
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

internal fun checkSavedEyeRig(source: Path, output: Path) {
    val root = ProjectArchive.extract(source)
    try {
        val id = ProjectArchive.readJson(root.resolve("manifest.json")).getValue("projectId").jsonPrimitive.content
        val tree = requireNotNull(AgentWorkspaceStore(root.resolve("workspace")).loadHistory(id))
        val document = tree.head().snapshot
        val ui = WorkspaceStateCodec.decode(ProjectArchive.readJson(root.resolve("workspace.json")))
        val config = WorkspaceStateCodec.decode(document.settings, ui).buildConfig().copy(
            layerVisibility = document.layerVisibility, deletedLayerIds = document.deletedLayerIds,
            layerOverrides = document.layerOverrides, parentOverrides = document.parentOverrides,
            rigEdits = document.rigEdits, meshOverrides = document.meshOverrides)
        val model = PSD2LivePipeline().buildPreview(document.source, config)
        val eyes = model.analysis.layers.filter { it.semantic.tag in setOf(SemanticTag.EYEBROW, SemanticTag.EYELASH,
            SemanticTag.EYEWHITE, SemanticTag.IRIDES) }
        val bounds = eyes.map { it.bounds }.reduce(Bounds::union).expanded(.35f)
        for (open in listOf(0f, .1f, .5f, 1f)) {
            val view = AgentViewRenderer.modelComposite(model, tree.head().node.revisionId,
                mapOf("ParamEyeLOpen" to open, "ParamEyeROpen" to open), model.rig.layerIdByDrawableId.values.toSet(),
                emptySet(), AgentViewFrame.CanvasRect(bounds), AgentViewBackground.CHECKERBOARD, AgentViewOutputSpec(1200))
            output.resolve("saved-open-$open.png").writeBytes(view.png)
        }
        output.resolve("saved-project.json").writeText(buildJsonObject {
            put("rigGenerationVersion", config.rigGenerationVersion)
            put("historyNodes", tree.nodes().size)
            put("authoredKeyforms", document.rigEdits.keyformSetEdits.size)
            put("meshInspection", inspectMeshes(model, buildJsonObject {
                put("layer_ids", JsonArray(eyes.map { JsonPrimitive(it.source.id.raw) }))
            }))
        }.toString())
        println("Saved project reopened: generation=${config.rigGenerationVersion}, history=${tree.nodes().size}, authored=${document.rigEdits.keyformSetEdits.size}")
    } finally {
        ProjectArchive.deleteTemporaryDirectory(root)
    }
}
