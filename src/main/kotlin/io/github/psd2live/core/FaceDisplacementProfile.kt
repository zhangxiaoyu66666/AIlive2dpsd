package io.github.psd2live.core

import kotlin.math.*

/** Additional drawing correction after the common facial surface and before local feature poses. */
internal object FaceDisplacementProfile {
    fun usesSharedSurface(feature: FaceFeature): Boolean =
        feature in setOf(FaceFeature.EYE, FaceFeature.BROW, FaceFeature.MOUTH)

    fun point(u: Float, v: Float, angleX: Float, angleY: Float, strength: Float,
              aspectRatio: Float, generationVersion: Int): Pair<Float, Float> {
        val legacy = legacyPoint(u, v, angleX, angleY, strength, aspectRatio)
        if (generationVersion < 3 || (angleX == 0f && angleY == 0f) || strength == 0f) return legacy
        // The face surface already supplies perspective; the extra layer is a small correction.
        // Keep the neutral point exact and retain authored v1/v2 base grids unchanged.
        return (u + (legacy.first - u) * .35f) to (v + (legacy.second - v) * .35f)
    }

	private fun legacyPoint(
		u: Float, v: Float, angleX: Float, angleY: Float, strength: Float,
		aspectRatio: Float = 1f,
	): Pair<Float, Float> {
		val yaw = (angleX / 45f * strength).coerceIn(-1f, 1f)
		val pitch = (angleY / 30f * strength).coerceIn(-1f, 1f)
		// Cubic Bezier with endpoints 0 and handles 4/3 peaks at 1 at t=1/2.
		fun bow(t: Float): Float = BezierWarp.cubic(0f, 4f / 3f, 4f / 3f, 0f, t)
		val x = 0.5f + (u - 0.5f) * (1f - 0.15f * abs(yaw)) + yaw * (0.025f + 0.055f * bow(v))
		// Up: compress the whole height down toward the bottom, with extra compression
		// in the upper half. Down: compress only the lower half up toward the middle.
		// The squared half profiles are cubic Beziers with zero slope at their join.
		fun compressedV(value: Float): Float {
			fun halfCompression(t: Float) = BezierWarp.cubic(0f, 0f, 1f / 3f, 1f, t)
			return if (pitch > 0f) {
				value + pitch * (0.08f * (1f - value) +
					0.10f * halfCompression((1f - 2f * value).coerceAtLeast(0f)))
			} else {
				value + pitch * 0.10f * halfCompression((2f * value - 1f).coerceAtLeast(0f))
			}
		}
		// Canvas Y grows downwards; negative AngleY is a downward look (U-shaped rows).
		val y = compressedV(v) - pitch * (0.020f + 0.050f * bow(u))
		// In canvas coordinates positive rotation is clockwise. Upper-left/lower-right
		// have yaw*pitch < 0. Rotate the entire curved surface about its displaced center;
		// pure horizontal/vertical poses stay unchanged. Correct for non-square face frames.
		val radians = -yaw * pitch * (3f * PI.toFloat() / 180f)
		val centerX = 0.5f + yaw * 0.080f
		val centerY = compressedV(0.5f) - pitch * 0.070f
		val dx = (x - centerX) * aspectRatio
		val dy = y - centerY
		val cosine = cos(radians)
		val sine = sin(radians)
		return (centerX + (dx * cosine - dy * sine) / aspectRatio) to
			(centerY + dx * sine + dy * cosine)
	}

}
