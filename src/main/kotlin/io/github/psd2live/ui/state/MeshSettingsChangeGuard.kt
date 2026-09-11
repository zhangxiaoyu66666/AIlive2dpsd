package io.github.psd2live.ui.state

import io.github.psd2live.core.*

/** Validate the entire proposed settings update before publishing any UI state. */
internal fun requireMeshSettingsChangeSafe(current: PSD2LiveState, next: PSD2LiveState) {
    val preview = current.previewModel ?: run {
        require(current.rigEdits == RigEditOverlay.Empty) { "Wait for the model preview before changing mesh settings." }
        return
    }
    val before = current.buildConfig()
    val after = next.buildConfig()
    val affected = preview.analysis.layers.filter { layer ->
        val id = layer.source.id.raw
        val tag = layer.semantic.tag
        // Compare with the rendered base too: a pending rebuild must not hide a topology change.
        listOf(before, preview.config).any { base ->
            base.alphaThreshold != after.alphaThreshold ||
                base.effectiveMeshSettings(id, tag) != after.effectiveMeshSettings(id, tag) ||
                (tag in CharacterAnalyzer.MOUTH_TAGS &&
                    (base.mouthOutlineEnabled != after.mouthOutlineEnabled || base.mouthCurve != after.mouthCurve ||
                        base.mouthThickness != after.mouthThickness || base.meshOnly != after.meshOnly))
        }
    }.map { it.source.id.raw }
    requireLayerRemeshable(current.rigEdits, preview.rig.puppet, preview.rig.layerIdByDrawableId, affected)
}
