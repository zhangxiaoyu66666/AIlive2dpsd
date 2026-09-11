package io.github.psd2live.ui

import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.core.*
import io.github.psd2live.ui.state.*
import kotlin.test.*

class MeshSettingsChangeGuardTest {
    private fun state(override: Boolean = false): PSD2LiveState {
        val base = PSD2LiveState(atlasSize = 256, mouthOutlineEnabled = false,
            meshOverrides = if (override) mapOf("eyebrow-r" to MeshSettings()) else emptyMap())
        val config = base.buildConfig()
        val source = WorkspaceSourceArt(80, 80, listOf(rasterLayer("face", 60, 60, 10, 10),
            rasterLayer("eyebrow-r", 20, 3, 20, 30, 1)), emptyList())
        val analysis = CharacterAnalyzer.analyze(source, config)
        val atlas = AtlasPacker.pack(analysis.layers, 256, 2)
        val rig = RigBuilder.build(analysis, atlas, config)
        val mesh = rig.puppet.drawables.single { rig.layerIdByDrawableId[it.id.raw] == "eyebrow-r" }
        val edits = RigEditOverlay.Empty.setKeyform(RigKeyformSetEdit(
            RigTargetRef(RigTargetKind.ART_MESH, mesh.id.raw), mapOf("ParamBrowRY" to 0f),
            geometry = RigKeyformGeometryEdit(positionDeltas = List(mesh.mesh!!.positions.size) { .01f })))
        return base.copy(analysis = analysis, rigEdits = edits,
            previewModel = RigPreviewModel(analysis, atlas, rig, config, CubismRuntimeBundle("test.model3.json", listOf(CubismRuntimeAsset("test.model3.json", "{}".encodeToByteArray())))))
    }

    @Test fun globalMeshAndAlphaChangesRejectBeforePublishingAuthoredState() {
        val current = state()
        for (next in listOf(current.copy(meshOuterMargin = 4f), current.copy(meshMaxEdgeDistance = 30f),
            current.copy(meshInteriorDensity = 15f), current.copy(alphaThreshold = 64))) {
            assertFailsWith<IllegalArgumentException> { requireMeshSettingsChangeSafe(current, next) }
        }
        val viewModel = PSD2LiveViewModel()
        try {
            viewModel.installProjectState(current)
            viewModel.setMeshMaxEdgeDistance(30f)
            assertEquals(current.meshMaxEdgeDistance, viewModel.state.value.meshMaxEdgeDistance)
            assertSame(current.rigEdits, viewModel.state.value.rigEdits)
            assertNotNull(viewModel.state.value.errorMessage)
            assertFalse(viewModel.state.value.projectDirty)
        } finally { viewModel.close() }
    }

    @Test fun localOverridesStayEditableAndResetIsAtomic() {
        val current = state(override = true)
        requireMeshSettingsChangeSafe(current, current.copy(meshMaxEdgeDistance = 30f))
        requireMeshSettingsChangeSafe(current, current.copy(headStrength = 2f))
        assertFailsWith<IllegalArgumentException> { requireMeshSettingsChangeSafe(current, current.copy(meshOverrides = emptyMap())) }
        val viewModel = PSD2LiveViewModel()
        try {
            viewModel.installProjectState(current)
            viewModel.resetSettingsToDefault()
            assertEquals(current.atlasSize, viewModel.state.value.atlasSize)
            assertEquals(current.meshOverrides, viewModel.state.value.meshOverrides)
            assertSame(current.rigEdits, viewModel.state.value.rigEdits)
        } finally { viewModel.close() }
    }
}
