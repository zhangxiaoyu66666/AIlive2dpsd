package io.github.psd2live.core

import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.*

/** Fold blink into the existing visibility track, retaining toggle/switch axes and source opacity. */
internal fun eyeOpacityChannels(layer: ClassifiedLayer, base: ChannelGrids): ChannelGrids {
    if (layer.semantic.tag !in setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE)) return base
    val blink = when (layer.semantic.side) {
        Side.LEFT -> listOf(StandardParameters.EYE_L_OPEN)
        Side.RIGHT -> listOf(StandardParameters.EYE_R_OPEN)
        Side.NONE -> listOf(StandardParameters.EYE_L_OPEN, StandardParameters.EYE_R_OPEN)
    }
    val axes = base[FormChannel.OPACITY]?.axes.orEmpty().toMutableList()
    for (id in blink) {
        val index = axes.indexOfFirst { it.parameterId == id }
        val keys = (axes.getOrNull(index)?.keys?.toList().orEmpty() + listOf(0f, 0.1f, 1f)).distinct().sorted().toFloatArray()
        val axis = KeyformAxis(id, keys)
        if (index < 0) axes.add(axis) else axes[index] = axis
    }
    val opacity = RigBuilder.grid<ChannelValue>(axes) { values ->
        val pose = axes.indices.associate { axes[it].parameterId to values[it] }
        // A combined two-eye layer cannot be hidden while either eye remains open.
        val factor = blink.maxOf { (pose.getValue(it) / 0.1f).coerceIn(0f, 1f) }
        ChannelValue.Scalar(base.scalarAt(FormChannel.OPACITY, layer.source.opacity, { pose[it] ?: 0f }) * factor)
    }
    return ChannelGrids(base.gridsByChannel + (FormChannel.OPACITY to opacity))
}
