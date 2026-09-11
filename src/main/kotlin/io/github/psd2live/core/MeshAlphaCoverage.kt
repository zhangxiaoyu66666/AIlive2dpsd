package io.github.psd2live.core

import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.floor

/** Neutral raster coverage, independent of pose, visibility, masks and foreground occlusion. */
internal object MeshAlphaCoverage {
    data class Report(val paintedPixels: Int, val clippedPixels: Int, val clippedAlphaFraction: Double)

    fun measure(width: Int, height: Int, rgba: ByteArray, positions: FloatArray,
                indices: IntArray, threshold: Int): Report {
        require(width > 0 && height > 0 && width.toLong() * height * 4 <= rgba.size)
        require(threshold in 1..255 && positions.size % 2 == 0 && positions.all { it.isFinite() })
        require(indices.size % 3 == 0 && indices.all { it in 0 until positions.size / 2 })
        val covered = BitSet(width * height)
        for (i in indices.indices step 3) {
            val a = indices[i] * 2; val b = indices[i + 1] * 2; val c = indices[i + 2] * 2
            val ax = positions[a].toDouble(); val ay = positions[a + 1].toDouble()
            val bx = positions[b].toDouble(); val by = positions[b + 1].toDouble()
            val cx = positions[c].toDouble(); val cy = positions[c + 1].toDouble()
            val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            if (kotlin.math.abs(area) < 1e-10) continue
            val left = ceil(minOf(ax, bx, cx) - 0.5).toInt().coerceAtLeast(0)
            val right = floor(maxOf(ax, bx, cx) - 0.5).toInt().coerceAtMost(width - 1)
            val top = ceil(minOf(ay, by, cy) - 0.5).toInt().coerceAtLeast(0)
            val bottom = floor(maxOf(ay, by, cy) - 0.5).toInt().coerceAtMost(height - 1)
            for (y in top..bottom) for (x in left..right) {
                val px = x + 0.5; val py = y + 0.5
                val u = ((bx - px) * (cy - py) - (by - py) * (cx - px)) / area
                val v = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) / area
                if (u >= -1e-6 && v >= -1e-6 && u + v <= 1.000001) covered.set(y * width + x)
            }
        }
        var painted = 0; var clipped = 0; var totalAlpha = 0L; var clippedAlpha = 0L
        for (i in 0 until width * height) {
            val alpha = rgba[i * 4 + 3].toInt() and 255
            if (alpha < threshold) continue
            painted++; totalAlpha += alpha
            if (!covered[i]) { clipped++; clippedAlpha += alpha }
        }
        return Report(painted, clipped, if (totalAlpha == 0L) 0.0 else clippedAlpha.toDouble() / totalAlpha)
    }
}
