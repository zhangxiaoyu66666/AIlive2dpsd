package io.github.psd2live.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.jetbrains.skia.Surface
import org.umamo.runtime.model.ParameterId
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Same real PSD, poses, viewport and visibility in both renderers; entirely offscreen. */
object PreviewRenderCheck {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val output = Path.of(args[1]); Files.createDirectories(output)
        val vm = withContext(Dispatchers.Main) { PSD2LiveViewModel().apply { presentationActive = false; setInputPath(args[0]); analyze() } }
        try {
            withTimeout(120000) { vm.state.first { !it.isAnalyzing } }
            val state = vm.state.value
            val model = checkNotNull(state.previewModel) { state.errorMessage.orEmpty() }
            println("NATIVE_STATUS ${state.sdkStatus}")
            val viewport = CanvasViewport(0.92, -50.0, -80.0, model.analysis.source.widthPx.toFloat(), model.analysis.source.heightPx.toFloat())
            val visible = model.rig.layerIdByDrawableId.values.toSet()
            val poses = listOf(emptyMap(), mapOf(ParameterId("ParamAngleX") to 20f, ParameterId("ParamEyeLOpen") to 0.2f), mapOf(ParameterId("ParamAngleY") to -20f))
            val timesOld = mutableListOf<Double>(); val timesNew = mutableListOf<Double>()
            SkiaRigTextures(model).use { textures ->
                Surface.makeRasterN32Premul(900, 760).use { surface ->
                    for ((index, pose) in poses.withIndex()) {
                        val geometry = RigCanvasSupport.evaluate(model, pose)
                        fun legacy(): BufferedImage {
                            val image = BufferedImage(900, 760, BufferedImage.TYPE_INT_ARGB)
                            val g = image.createGraphics()
                            try { RigCanvasSupport.paintTexturedRig(g, model, geometry, viewport, visibleLayerIds = visible) } finally { g.dispose() }
                            return image
                        }
                        fun skia(): BufferedImage {
                            surface.canvas.clear(0)
                            textures.draw(surface.canvas, geometry, viewport, visibleLayerIds = visible)
                            return surface.makeImageSnapshot().use { it.toComposeImageBitmap().toAwtImage() }
                        }
                        val old = legacy(); val fresh = skia()
                        ImageIO.write(old, "png", output.resolve("legacy-$index.png").toFile())
                        ImageIO.write(fresh, "png", output.resolve("skia-$index.png").toFile())
                        var error = 0.0; var pixels = 0
                        for (y in 0 until 760) for (x in 0 until 900) {
                            val a = old.getRGB(x, y); val b = fresh.getRGB(x, y)
                            val aa = a ushr 24; val ba = b ushr 24
                            if (aa == 0 && ba == 0) continue
                            pixels++
                            error += kotlin.math.abs(aa - ba)
                            for (shift in listOf(0, 8, 16)) error += kotlin.math.abs(((a ushr shift and 255) * aa - (b ushr shift and 255) * ba) / 255.0)
                        }
                        val mean = error / (pixels * 4)
                        println("POSE $index pixels=$pixels mean_premultiplied_difference=$mean")
                        check(mean < 12) { "Texture placement, clipping or alpha changed materially" }
                        repeat(12) { iteration ->
                            var start = System.nanoTime(); legacy(); val oldMs = (System.nanoTime() - start) / 1e6
                            start = System.nanoTime(); skia(); val newMs = (System.nanoTime() - start) / 1e6
                            if (iteration >= 2) { timesOld += oldMs; timesNew += newMs }
                        }
                    }
                }
            }
            fun percentile(values: List<Double>, percent: Double) = values.sorted()[(values.size * percent).toInt().coerceAtMost(values.lastIndex)]
            println("PREVIEW_RENDER_OK layers=${model.analysis.layers.size} triangles=${model.rig.puppet.drawables.sumOf { it.mesh?.triangleCount ?: 0 }} " +
                "legacy_median_ms=${percentile(timesOld, .5)} legacy_p95_ms=${percentile(timesOld, .95)} skia_median_ms=${percentile(timesNew, .5)} skia_p95_ms=${percentile(timesNew, .95)}")
        } finally { vm.close() }
    }
}
