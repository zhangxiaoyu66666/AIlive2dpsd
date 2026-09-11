package io.github.psd2live.core

/** One resolver for generation, the local mesh dialog and MCP. Values are source pixels. */
fun PipelineConfig.defaultMeshSettings(tag: SemanticTag?): MeshSettings {
    val density = when (tag) {
        SemanticTag.FACE, SemanticTag.FRONT_HAIR, SemanticTag.BACK_HAIR, SemanticTag.TOPWEAR -> 0.65f
        SemanticTag.IRIDES, SemanticTag.EYELASH, SemanticTag.EYEWHITE, SemanticTag.EYEBROW,
        SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN, SemanticTag.MOUTH_CLOSE,
        SemanticTag.TOOTH_T, SemanticTag.TOOTH_B, SemanticTag.TONGUE -> 0.45f
        else -> 1f
    }
    return MeshSettings(meshOuterMargin, tag == SemanticTag.FACE, meshInnerMargin,
        (meshMaxEdgeDistance * density).coerceAtLeast(if (rigGenerationVersion == 1) 12f else 1f),
        (meshInteriorDensity * density).coerceAtLeast(if (rigGenerationVersion == 1) 12f else 2f))
}

fun PipelineConfig.effectiveMeshSettings(layerId: String?, tag: SemanticTag?): MeshSettings {
    val settings = meshOverrides[layerId] ?: defaultMeshSettings(tag)
    return if (mouthOutlineEnabled && !meshOnly && tag in setOf(SemanticTag.MOUTH, SemanticTag.MOUTH_OPEN))
        settings.copy(outerMargin = 0f, maxEdgeDistance = 2f) else settings
}
