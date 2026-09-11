package io.github.psd2live.core

import io.github.psd2live.agent.WorkspaceSourceArt
import io.github.psd2live.agent.WorkspaceSourceLayer
import io.github.psd2live.project.WorkspaceStateCodec
import io.github.psd2live.ui.state.PSD2LiveState
import kotlinx.serialization.json.*
import org.umamo.format.art.*
import kotlin.math.*
import kotlin.test.*

internal fun rasterLayer(name: String, width: Int, height: Int, left: Int = 0, top: Int = 0,
                         order: Int = 0, alpha: (Int, Int) -> Int = { _, _ -> 255 }): WorkspaceSourceLayer {
    val rgba = ByteArray(width * height * 4)
    for (y in 0 until height) for (x in 0 until width) {
        val i = (y * width + x) * 4
        rgba[i] = 100; rgba[i + 1] = 70; rgba[i + 2] = 40; rgba[i + 3] = alpha(x, y).toByte()
    }
    return WorkspaceSourceLayer(LayerId(name), name, "", SourceLayerKind.Raster, true, order,
        LayerBounds(left, top, width, height), 1f, false, LayerBlend.Normal, ChannelMask.ALL,
        LayerRaster(width, height, rgba), null, null, false)
}

internal fun classify(layer: SourceLayer): ClassifiedLayer = ClassifiedLayer(layer, LayerClassifier.classify(layer.name),
    Bounds(layer.bounds.left.toFloat(), layer.bounds.top.toFloat(), (layer.bounds.left + layer.bounds.width).toFloat(),
        (layer.bounds.top + layer.bounds.height).toFloat()), layer.bounds.left + layer.bounds.width / 2f,
    layer.bounds.top + layer.bounds.height / 2f, layer.raster.width * layer.raster.height)

class EyeRigReliabilityTest {
    @Test fun softIrisAndDetachedLashTipRemainInsideMesh() {
        val layer = rasterLayer("irides-l", 40, 34) { x, y ->
            val distance = hypot((x - 19.5) / 16, (y - 16.5) / 13)
            when { x == 0 && y == 0 -> 255; distance < .75 -> 255; distance < 1 -> 40; else -> 0 }
        }
        val bytes = layer.raster.rgba.copyOf()
        val result = assertNotNull(AdaptiveMeshGenerator.generate(40, 34, bytes, 8, MeshSettings()))
        val coverage = MeshAlphaCoverage.measure(40, 34, bytes, result.positions, result.indices, 8)
        assertEquals(0, coverage.clippedPixels, "Soft iris pixels and one-pixel lash islands must not be discarded")
        assertContentEquals(layer.raster.rgba, bytes, "Meshing must not alter source texture alpha")
        val legacy = assertNotNull(AdaptiveMeshGenerator.generate(40, 34, bytes, 8, spacing = 12f, preserveSourceAlpha = false))
        assertTrue(MeshAlphaCoverage.measure(40, 34, bytes, legacy.positions, legacy.indices, 8).clippedPixels > 0,
            "The fixture must reproduce the original clipping defect")
    }

    @Test fun donutHoleAndSeparateIslandsAreRetained() {
        val layer = rasterLayer("eyelash-l", 64, 40) { x, y ->
            val d = hypot((x - 31.5) / 25, (y - 19.5) / 14)
            if (d in .7..1.0 || (x in 0..1 && y in 0..1)) 220 else 0
        }
        val mesh = assertNotNull(AdaptiveMeshGenerator.generate(64, 40, layer.raster.rgba, 8, MeshSettings(maxEdgeDistance = 2f)))
        assertTrue(mesh.boundaryLoops.size >= 3, "Outer ring, inner hole and detached mark")
        val coverage = MeshAlphaCoverage.measure(64, 40, layer.raster.rgba, mesh.positions, mesh.indices, 8)
        assertEquals(0, coverage.clippedPixels)
        val center = ByteArray(64 * 40 * 4).also { it[(20 * 64 + 32) * 4 + 3] = -1 }
        assertEquals(1, MeshAlphaCoverage.measure(64, 40, center, mesh.positions, mesh.indices, 8).clippedPixels)
    }

    @Test fun pairedLidsCloseToAStrokeWithoutSquashingASingleLash() {
        val ring = classify(rasterLayer("eyelash-l", 40, 24) { _, y -> if (y in 2..5 || y in 18..21) 255 else 0 })
        val stroke = classify(rasterLayer("eyelash-l", 40, 24) { _, y -> if (y in 2..5) 255 else 0 })
        assertTrue(EyeClosureProfile.hasPairedLids(ring, 8))
        assertFalse(EyeClosureProfile.hasPairedLids(stroke, 8))
        val white = Bounds(2f, 5f, 38f, 20f)
        fun span(paired: Boolean) = EyeClosureProfile.point(20f, 24f, ring.bounds, white, SemanticTag.EYELASH, pairedLids = paired).second -
            EyeClosureProfile.point(20f, 0f, ring.bounds, white, SemanticTag.EYELASH, pairedLids = paired).second
        assertTrue(span(true) <= 1.501f)
        assertEquals(24f * .88f, span(false), .001f)
        for (x in 2..38) {
            val a = EyeClosureProfile.point(x.toFloat(), 5f, white, white, SemanticTag.EYEWHITE)
            val b = EyeClosureProfile.point(x.toFloat(), 20f, white, white, SemanticTag.EYEWHITE)
            assertEquals(a, b, "Closed eye mask must have zero aperture at each column")
        }
    }

    @Test fun browMovesOnlyAboveItsFaceAndStaysUnderFrontHair() {
        val layers = listOf(classify(rasterLayer("front hair", 100, 100)),
            classify(rasterLayer("face", 80, 80, 10, 10)),
            classify(rasterLayer("eyebrow-r", 15, 3, 20, 30)),
            classify(rasterLayer("eyebrow-l", 15, 3, 200, 30)))
        val ordered = orderEyebrowsAboveFace(layers)
        assertEquals(listOf("front hair", "eyebrow-r", "face", "eyebrow-l"), ordered.map { it.source.name })
        assertEquals(ordered, orderEyebrowsAboveFace(ordered))
        assertEquals("face", layers[1].source.name)
    }

    @Test fun savedLegacyProjectsKeepVertexGenerationAndNewProjectsRoundTrip() {
        val legacy = WorkspaceStateCodec.decode(buildJsonObject { put("meshMaxEdgeDistance", 6f) })
        assertEquals(1, legacy.rigGenerationVersion)
        assertEquals(12f, legacy.buildConfig().defaultMeshSettings(SemanticTag.IRIDES).maxEdgeDistance)
        val state = PSD2LiveState(meshMaxEdgeDistance = 2f)
        val saved = WorkspaceStateCodec.settings(state)
        val restored = WorkspaceStateCodec.decode(saved)
        assertEquals(2, restored.rigGenerationVersion)
        assertEquals(1f, restored.buildConfig().defaultMeshSettings(SemanticTag.IRIDES).maxEdgeDistance)
        assertEquals(saved, WorkspaceStateCodec.settings(restored))
        assertFailsWith<IllegalArgumentException> { WorkspaceStateCodec.decode(buildJsonObject { put("rigGenerationVersion", 99) }) }
    }

    @Test fun builderPreservesExplicitBrowDrawOrderAndOpenKey() {
        val source = WorkspaceSourceArt(100, 100, listOf(rasterLayer("face", 80, 80, 10, 10, 0),
            rasterLayer("eyebrow-r", 16, 3, 25, 28, 1), rasterLayer("eyewhite-r", 18, 8, 25, 40, 2)), emptyList())
        val config = PipelineConfig(atlasSize = 256, mouthOutlineEnabled = false, drawOrderOverrides = mapOf("eyebrow-r" to 0f))
        val analysis = CharacterAnalyzer.analyze(source, config)
        val rig = RigBuilder.build(analysis, AtlasPacker.pack(analysis.layers, 256, 2), config)
        val brow = rig.puppet.drawables.first { rig.layerIdByDrawableId[it.id.raw] == "eyebrow-r" }
        assertEquals(0f, brow.drawOrder)
        val white = rig.puppet.drawables.first { rig.layerIdByDrawableId[it.id.raw] == "eyewhite-r" }
        val grid = assertNotNull(white.geometryGrid)
        val open = grid.cells.first { grid.axes.single().keys[it.coordinate.single()] == 1f }
        assertTrue(open.form.positionDeltas.all { it == 0f })
    }
}
