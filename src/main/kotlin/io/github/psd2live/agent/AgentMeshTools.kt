package io.github.psd2live.agent

import io.github.psd2live.core.*
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import org.umamo.runtime.model.PuppetModel

internal fun meshToolSchema(mutation: Boolean) = ToolSchema(properties = buildJsonObject {
    putJsonObject("layer_ids") {
        put("type", "array"); put("minItems", 1); put("maxItems", 32); put("uniqueItems", true)
        putJsonObject("items") { put("type", "string") }
    }
    if (mutation) {
        putJsonObject("expected_history_head_node_id") { put("type", "string") }
        putJsonObject("task_id") { put("type", "string") }
        putJsonObject("settings") {
            put("type", JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))
            put("additionalProperties", false)
            putJsonObject("properties") {
                for ((key, range) in mapOf("outer_margin" to (0f..20f), "inner_margin" to (0.5f..20f),
                    "max_edge_distance" to (1f..128f), "interior_density" to (2f..128f))) {
                    putJsonObject(key) { put("type", "number"); put("minimum", range.start); put("maximum", range.endInclusive) }
                }
                putJsonObject("inner_margin_enabled") { put("type", "boolean") }
            }
            put("description", "Partial override in source pixels. Null removes the layer override. Existing vertex-authored geometry must not be silently remeshed.")
        }
    }
}, required = if (mutation) listOf("layer_ids", "settings", "expected_history_head_node_id") else listOf("layer_ids"))

internal fun meshLayerIds(arguments: JsonObject): List<String> {
    val ids = arguments.getValue("layer_ids").jsonArray.map { it.jsonPrimitive.content }
    require(ids.size in 1..32 && ids.distinct().size == ids.size && ids.all { it.isNotBlank() })
    return ids
}

internal fun MeshSettings.toAgentJson(): JsonObject = buildJsonObject {
    put("outer_margin", outerMargin); put("inner_margin_enabled", innerMarginEnabled)
    put("inner_margin", innerMargin); put("max_edge_distance", maxEdgeDistance); put("interior_density", interiorDensity)
}

internal fun patchMeshSettings(base: MeshSettings, patch: JsonObject): MeshSettings {
    val keys = setOf("outer_margin", "inner_margin_enabled", "inner_margin", "max_edge_distance", "interior_density")
    require(patch.isNotEmpty() && patch.keys.all { it in keys }) { "Specify supported mesh settings" }
    fun number(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
        (patch[key]?.jsonPrimitive?.float ?: fallback).also { require(it.isFinite() && it in range) { "Invalid $key; expected $range" } }
    return MeshSettings(number("outer_margin", base.outerMargin, 0f..20f),
        patch["inner_margin_enabled"]?.jsonPrimitive?.boolean ?: base.innerMarginEnabled,
        number("inner_margin", base.innerMargin, 0.5f..20f),
        number("max_edge_distance", base.maxEdgeDistance, 1f..128f),
        number("interior_density", base.interiorDensity, 2f..128f))
}

internal fun changeMeshSettings(document: AgentWorkspaceDocument, puppet: PuppetModel,
                                layerIdByDrawableId: Map<String, String>, arguments: JsonObject): AgentWorkspaceDocument {
    val ids = meshLayerIds(arguments)
    val sourceIds = document.source.layers.map { it.id.raw }.toSet() - document.deletedLayerIds
    require(ids.all { it in sourceIds }) { "Unknown or deleted source layer in layer_ids" }
    require(ids.all { it in layerIdByDrawableId.values }) { "Layer has no generated mesh" }
    requireLayerRemeshable(document.rigEdits, puppet, layerIdByDrawableId, ids)
    val config = io.github.psd2live.project.WorkspaceStateCodec.decode(document.settings).buildConfig()
    val analysis = CharacterAnalyzer.analyze(document.source, config.copy(layerOverrides = document.layerOverrides))
    val patch = arguments.getValue("settings")
    val overrides = document.meshOverrides.toMutableMap()
    for (id in ids) {
        if (patch == JsonNull) overrides.remove(id)
        else {
            val tag = analysis.layers.first { it.source.id.raw == id }.semantic.tag
            overrides[id] = patchMeshSettings(overrides[id] ?: config.defaultMeshSettings(tag), patch.jsonObject)
        }
    }
    // Settings and explicit overrides are both persisted in older projects. Keep one value.
    val encoded = io.github.psd2live.project.WorkspaceStateCodec.settings(
        io.github.psd2live.project.WorkspaceStateCodec.decode(document.settings).copy(meshOverrides = overrides))
    val settings = JsonObject(document.settings + ("meshOverrides" to encoded.getValue("meshOverrides")))
    return document.copy(meshOverrides = overrides, settings = settings)
}

internal fun inspectMeshes(preview: RigPreviewModel, arguments: JsonObject): JsonObject = buildJsonObject {
    val ids = meshLayerIds(arguments)
    put("rigGenerationVersion", preview.config.rigGenerationVersion)
    put("scope", "Neutral source-alpha coverage in texture coordinates. Visibility, deformation and foreground occlusion are separate from this measurement.")
    putJsonArray("layers") {
        for (id in ids) {
            val layer = preview.analysis.layers.firstOrNull { it.source.id.raw == id } ?: error("Layer not found: $id")
            val drawable = preview.rig.puppet.drawables.firstOrNull { preview.rig.layerIdByDrawableId[it.id.raw] == id }
                ?: error("Layer has no mesh: $id")
            val mesh = requireNotNull(drawable.mesh)
            val placement = preview.atlas.placementByLayerId.getValue(id)
            val size = preview.atlas.pages[placement.page].image.width
            val local = FloatArray(mesh.uvs.size) { i -> mesh.uvs[i] * size - if (i % 2 == 0) placement.x else placement.y }
            val raster = layer.source.raster
            val coverage = MeshAlphaCoverage.measure(raster.width, raster.height, raster.rgba, local, mesh.indices, preview.config.alphaThreshold)
            add(buildJsonObject {
                put("layerId", id); put("drawableId", drawable.id.raw)
                put("settings", preview.config.effectiveMeshSettings(id, layer.semantic.tag).toAgentJson())
                put("overridden", id in preview.config.meshOverrides)
                put("vertexCount", mesh.vertexCount); put("triangleCount", mesh.triangleCount)
                put("alphaThreshold", preview.config.alphaThreshold)
                put("paintedPixels", coverage.paintedPixels); put("clippedPixels", coverage.clippedPixels)
                put("clippedAlphaFraction", coverage.clippedAlphaFraction)
                put("visible", drawable.isVisible); put("baseOpacity", drawable.opacity); put("baseDrawOrder", drawable.drawOrder)
                putJsonArray("maskedBy") { drawable.maskedBy.forEach { add(JsonPrimitive(it.raw)) } }
            })
        }
    }
}
