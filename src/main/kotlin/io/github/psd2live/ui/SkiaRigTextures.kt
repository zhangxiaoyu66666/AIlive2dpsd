package io.github.psd2live.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.psd2live.core.RigPreviewModel
import org.jetbrains.skia.*
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId

/** Texture resources belong to one model/viewport; no atlas upload or full-frame bitmap per pose. */
internal class SkiaRigTextures(private val model: RigPreviewModel) : AutoCloseable {
    private val images = model.atlas.pages.map { page ->
        page.image.toComposeImageBitmap().asSkiaBitmap().use { Image.makeFromBitmap(it) }
    }
    private val shaders = images.map { it.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null) }
    private val paint = Paint()
    // Expand indices once: Skia's indexed API is limited to 16-bit indices, our meshes are not.
    private val textureCoordinates = model.rig.puppet.drawables.associate { drawable ->
        val mesh = drawable.mesh
        val page = model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage
        val image = images.getOrNull(page)
        drawable.id to if (mesh == null || image == null) FloatArray(0) else FloatArray(mesh.indices.size * 2) { i ->
            mesh.uvs[mesh.indices[i / 2] * 2 + i % 2] * if (i % 2 == 0) image.width else image.height
        }
    }

    fun draw(canvas: Canvas, geometry: DeformedGeometry, viewport: CanvasViewport,
             alpha: Float = 1f, visibleLayerIds: Set<String>? = null,
             drawOrderOverrides: Map<String, Float> = emptyMap(), dimUnselected: Boolean = false,
             highlightedLayerIds: Set<String>? = null, dimmedAlphaMultiplier: Float = 0.22f) {
        val masks = mutableMapOf<List<DrawableId>, Path?>()
        try {
            for (drawable in model.rig.puppet.drawables.sortedBy {
                drawOrderOverrides[model.rig.layerIdByDrawableId[it.id.raw]] ?: drawOrderOverrides[it.id.raw]
                    ?: geometry.drawOrder[it.id] ?: it.drawOrder
            }) {
                val layerId = model.rig.layerIdByDrawableId[drawable.id.raw]
                if (visibleLayerIds != null && layerId !in visibleLayerIds) continue
                val mesh = drawable.mesh ?: continue
                val positions = geometry.worldPositions[drawable.id] ?: continue
                val shader = shaders.getOrNull(model.rig.pageByDrawableId[drawable.id.raw] ?: drawable.texturePage) ?: continue
                val highlighted = highlightedLayerIds == null || layerId in highlightedLayerIds || drawable.id.raw in highlightedLayerIds
                val opacity = ((geometry.opacity[drawable.id] ?: drawable.opacity) * alpha *
                    if (dimUnselected && !highlighted) dimmedAlphaMultiplier else 1f).coerceIn(0f, 1f)
                if (opacity <= 0.001f) continue
                val vertices = FloatArray(mesh.indices.size * 2) { i ->
                    val value = positions[mesh.indices[i / 2] * 2 + i % 2]
                    (if (i % 2 == 0) viewport.x(value) else viewport.yFromWorld(value)).toFloat()
                }
                val saved = canvas.save()
                try {
                    // Keep the editor's geometric clipping contract, independently of Cubism's alpha masks.
                    if (drawable.maskedBy.isNotEmpty() && !drawable.invertMask) {
                        val ids = drawable.maskedBy
                        if (!masks.containsKey(ids)) masks[ids] = maskPath(ids, geometry, viewport)
                        masks[ids]?.let { canvas.clipPath(it, false) }
                    }
                    paint.shader = shader
                    paint.setAlphaf(opacity)
                    canvas.drawVertices(VertexMode.TRIANGLES, vertices, null, textureCoordinates.getValue(drawable.id), null, BlendMode.MODULATE, paint)
                } finally { canvas.restoreToCount(saved) }
            }
        } finally { masks.values.filterNotNull().forEach { it.close() } }
    }

    private fun maskPath(ids: List<DrawableId>, geometry: DeformedGeometry, viewport: CanvasViewport): Path? {
        var triangles = 0
        PathBuilder().use { path ->
            for (mask in model.rig.puppet.drawables.filter { it.id in ids && it.isVisible }) {
                val mesh = mask.mesh ?: continue
                val positions = geometry.worldPositions[mask.id] ?: continue
                for (offset in mesh.indices.indices step 3) {
                    val a = mesh.indices[offset] * 2
                    var b = mesh.indices[offset + 1] * 2
                    var c = mesh.indices[offset + 2] * 2
                    val cross = (positions[b] - positions[a]) * (positions[c + 1] - positions[a + 1]) -
                        (positions[b + 1] - positions[a + 1]) * (positions[c] - positions[a])
                    if (cross == 0f) continue
                    // Uniform winding makes overlapping triangles a union, without expensive Area unions.
                    if (cross < 0f) { val swap = b; b = c; c = swap }
                    path.moveTo(viewport.x(positions[a]).toFloat(), viewport.yFromWorld(positions[a + 1]).toFloat())
                    path.lineTo(viewport.x(positions[b]).toFloat(), viewport.yFromWorld(positions[b + 1]).toFloat())
                    path.lineTo(viewport.x(positions[c]).toFloat(), viewport.yFromWorld(positions[c + 1]).toFloat())
                    path.closePath()
                    triangles++
                }
            }
            return if (triangles > 0) path.detach() else null
        }
    }

    override fun close() { paint.close(); shaders.forEach { it.close() }; images.forEach { it.close() } }
}
