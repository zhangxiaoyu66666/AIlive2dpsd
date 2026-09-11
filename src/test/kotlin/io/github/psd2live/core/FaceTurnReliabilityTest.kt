package io.github.psd2live.core

import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.project.WorkspaceStateCodec
import io.github.psd2live.ui.state.PSD2LiveState
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import kotlin.math.*
import kotlin.test.*

class FaceTurnReliabilityTest {
    private fun rig(version: Int): BuiltRig {
        val source = WorkspaceSourceArt(200, 240, listOf(
            rasterLayer("face", 160, 200, 20, 20),
            rasterLayer("eyewhite-l", 32, 12, 118, 82, 1),
            rasterLayer("eyewhite-r", 32, 12, 50, 82, 2),
            rasterLayer("nose", 12, 12, 94, 126, 3),
            rasterLayer("mouth", 28, 4, 86, 157, 4)), emptyList())
        val config = PipelineConfig(atlasSize = 512, mouthOutlineEnabled = false, rigGenerationVersion = version)
        val analysis = CharacterAnalyzer.analyze(source, config)
        return RigBuilder.build(analysis, AtlasPacker.pack(analysis.layers, 512, 2), config)
    }

    @Test fun reducedExtraDisplacementPreservesNoseDepthAndNeutralVertices() {
        val old = rig(2); val fixed = rig(3)
        val oldNose = old.puppet.deformers.single { it.id.raw.contains("Nose") }
        val newNose = fixed.puppet.deformers.single { it.id.raw.contains("Nose") }
        assertEquals("DeformFaceNinePose", oldNose.parent?.raw)
        assertEquals(oldNose.parent, newNose.parent, "Nose retains its separate depth correction")
        val evaluator = CpuDeformationEvaluator()
        val neutralOld = evaluator.evaluate(old.puppet, emptyMap()).worldPositions
        val neutralNew = evaluator.evaluate(fixed.puppet, emptyMap()).worldPositions
        for ((id, vertices) in neutralOld) {
            val next = neutralNew.getValue(id)
            assertEquals(vertices.size, next.size)
            vertices.indices.forEach { assertEquals(vertices[it], next[it], .001f, "Neutral $id vertex $it") }
        }
        fun alignmentError(rig: BuiltRig, x: Float, y: Float): Float {
            val points = evaluator.evaluate(rig.puppet, mapOf(ParameterId("ParamAngleX") to x,
                ParameterId("ParamAngleY") to y)).worldPositions
            fun center(layer: String): Pair<Float, Float> {
                val id = rig.puppet.drawables.single { rig.layerIdByDrawableId[it.id.raw] == layer }.id
                val p = points.getValue(id)
                assertTrue(p.all { it.isFinite() })
                val xs = p.filterIndexed { i, _ -> i % 2 == 0 }; val ys = p.filterIndexed { i, _ -> i % 2 == 1 }
                return (xs.min() + xs.max()) / 2 to (ys.min() + ys.max()) / 2
            }
            val nose = center("nose"); val mouth = center("mouth")
            val eyeX = (center("eyewhite-l").first + center("eyewhite-r").first) / 2
            assertTrue(abs(nose.second - mouth.second) > 10f, "Nose and mouth retain separation at ($x,$y)")
            return abs(nose.first - eyeX) + abs(nose.first - mouth.first)
        }
        for (x in listOf(-45f, -30f, -15f, 15f, 30f, 45f)) for (y in listOf(-30f, -15f, 0f, 15f, 30f)) {
            val before = alignmentError(old, x, y); val after = alignmentError(fixed, x, y)
            assertTrue(after < before, "Shared facial midline at ($x,$y): $before -> $after")
        }
    }

    @Test fun legacyDisplacementAndPersistedGenerationsRemainStable() {
        val legacy = FaceDisplacementProfile.point(.2f, .4f, -45f, 0f, 1f, 1f, 1)
        assertEquals(.1672f, legacy.first, .00001f)
        assertEquals(.4f, legacy.second)
        assertEquals(legacy, FaceDisplacementProfile.point(.2f, .4f, -45f, 0f, 1f, 1f, 2))
        for (i in 0..8) for (j in 0..8) {
            assertEquals(FaceDisplacementProfile.point(i / 8f, j / 8f, 0f, 0f, 1f, 1f, 2),
                FaceDisplacementProfile.point(i / 8f, j / 8f, 0f, 0f, 1f, 1f, 3))
        }
        for (version in 1..3) {
            val state = PSD2LiveState(rigGenerationVersion = version)
            assertEquals(version, WorkspaceStateCodec.decode(WorkspaceStateCodec.settings(state)).rigGenerationVersion)
        }
        assertEquals(3, PSD2LiveState().rigGenerationVersion)
    }
}
