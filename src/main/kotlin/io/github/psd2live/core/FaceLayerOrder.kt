package io.github.psd2live.core

/** Repair only a brow painted behind its containing face; retain other painter relationships. */
internal fun orderEyebrowsAboveFace(layers: List<ClassifiedLayer>): List<ClassifiedLayer> {
    val result = layers.toMutableList()
    for (brow in layers.filter { it.semantic.tag == SemanticTag.EYEBROW }) {
        val face = layers.filter { it.semantic.tag == SemanticTag.FACE &&
            it.bounds.left <= brow.bounds.centerX && it.bounds.right >= brow.bounds.centerX &&
            it.bounds.top <= brow.bounds.centerY && it.bounds.bottom >= brow.bounds.centerY &&
            (it.semantic.variant == null || it.semantic.variant == brow.semantic.variant)
        }.minByOrNull { it.bounds.width * it.bounds.height } ?: continue
        if (result.indexOf(brow) > result.indexOf(face)) {
            result.remove(brow)
            result.add(result.indexOf(face), brow)
        }
    }
    return result
}
