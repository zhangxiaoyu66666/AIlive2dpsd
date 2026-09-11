package io.github.psd2live.core

import kotlin.math.max
import kotlin.math.min

/** Detect two painted lids on one raster before applying the single-stroke lash preset. */
internal object EyeClosureProfile {
    fun hasPairedLids(layer: ClassifiedLayer, alphaThreshold: Int): Boolean {
        val raster = layer.source.raster
        val width = raster.width
        val height = raster.height
        if (width < 4 || height < 4) return false
        var paintedColumns = 0
        var pairedColumns = 0
        // Ignore the corners, where the two lids are expected to meet.
        for (x in width / 4 until width - width / 4) {
            val runs = mutableListOf<IntRange>()
            var start = -1
            for (y in 0..height) {
                val painted = y < height && (raster.rgba[(y * width + x) * 4 + 3].toInt() and 255) >= alphaThreshold
                if (painted && start < 0) start = y
                if (!painted && start >= 0) { runs += start until y; start = -1 }
            }
            if (runs.isEmpty()) continue
            paintedColumns++
            if (runs.zipWithNext().any { (upper, lower) ->
                lower.first - upper.last - 1 >= max(2, height / 6)
            }) pairedColumns++
        }
        return paintedColumns > 0 && pairedColumns * 3 >= paintedColumns
    }

    fun point(x: Float, y: Float, layer: Bounds, white: Bounds, tag: SemanticTag,
              anchorY: Float = layer.centerY, pairedLids: Boolean = false, legacy: Boolean = false): Pair<Float, Float> {
        val u = ((x - white.centerX) / (white.width * 0.5f).coerceAtLeast(1e-4f)).coerceIn(-1f, 1f)
        val curveY = white.top + white.height * 0.34f + max(1.5f, white.height * 0.38f) * max(0f, 1f - u * u)
        val scale = when {
            tag == SemanticTag.EYELASH && (legacy || !pairedLids) -> 0.88f
            tag == SemanticTag.EYELASH -> min(0.88f, 1.5f / layer.height.coerceAtLeast(1f))
            legacy -> (1.2f / layer.height.coerceAtLeast(1f)).coerceIn(0.015f, 0.55f)
            else -> 0f // The eye aperture closes exactly; no residual white/iris slit.
        }
        return x to curveY + (y - anchorY) * scale
    }
}
