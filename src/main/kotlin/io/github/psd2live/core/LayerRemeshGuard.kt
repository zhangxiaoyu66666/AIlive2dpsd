package io.github.psd2live.core

import org.umamo.runtime.model.PuppetModel

/** Vertex identities are shared by authored deltas, copy operations and glue. Never silently erase them. */
fun requireLayerRemeshable(edits: RigEditOverlay, puppet: PuppetModel,
                          layerIdByDrawableId: Map<String, String>, layerIds: Collection<String>) {
    val targets = layerIdByDrawableId.filterValues { it in layerIds }.keys
    fun affects(target: RigTargetRef): Boolean = target.kind == RigTargetKind.ART_MESH &&
        (target.id in layerIds || puppet.findDrawable(target.id)?.id?.raw in targets)
    require(edits.keyformSetEdits.none { affects(it.target) && it.geometry != null } &&
        edits.keyformCopyEdits.none { (affects(it.sourceTarget) || affects(it.destinationTarget)) &&
            (it.channels == null || it.channels.any { name -> name.equals("geometry", ignoreCase = true) }) } &&
        puppet.glues.none { it.meshA.raw in targets || it.meshB.raw in targets }) {
        "Layer has vertex-authored keyforms or glue. Remeshing would invalidate vertex identities; retain this mesh or remesh an unedited history branch. No edits were removed."
    }
}
