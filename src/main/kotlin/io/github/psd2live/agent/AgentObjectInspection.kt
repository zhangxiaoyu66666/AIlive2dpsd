package io.github.psd2live.agent

import io.github.psd2live.core.RigTargetKind
import io.github.psd2live.core.findDrawable
import org.umamo.runtime.model.*
import kotlinx.serialization.json.*

internal fun channelCells(track: KeyformGrid<ChannelValue>): List<JsonObject> = track.cells.map { cell ->
    buildJsonObject {
        putJsonObject("coordinate") { track.axes.forEachIndexed { i, axis -> put(axis.parameterId.raw, axis.keys[cell.coordinate[i]]) } }
        put("value", when (val value = cell.form) {
            is ChannelValue.Scalar -> JsonPrimitive(value.value)
            is ChannelValue.Flag -> JsonPrimitive(value.flag)
            is ChannelValue.Color -> JsonArray(listOf(value.color.red, value.color.green, value.color.blue).map(::JsonPrimitive))
        })
    }
}

internal fun inspectRigObject(rig: io.github.psd2live.core.BuiltRig, target: AgentKeyformTargetRef): AgentObjectSnapshot {
		val puppet = rig.puppet
		val kind = RigTargetKind.fromString(target.kind)

		return when (kind) {
			RigTargetKind.WARP_DEFORMER, RigTargetKind.ROTATION_DEFORMER -> {
				val deformer = puppet.deformers.firstOrNull { it.id.raw == target.id }
					?: throw IllegalArgumentException("Deformer not found: ${target.id}")
				when (deformer) {
					is Deformer.Warp -> {
						val grid = deformer.geometryGrid
						val topo = mapOf(
							"type" to "warp",
							"rows" to deformer.rows.toString(),
							"columns" to deformer.columns.toString(),
							"controlPointsCount" to ((deformer.rows + 1) * (deformer.columns + 1)).toString(),
							"isQuadTransform" to deformer.isQuadTransform.toString(),
						)
						val geoSnapshot = grid?.let { g ->
							AgentObjectGeometrySnapshot(
								axes = g.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = g.cells.size,
								cells = g.cells.map { cell ->
									val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
									AgentObjectCellSnapshot(
										coordinate = coord,
										controlPoints = cell.form.controlPoints.toList(),
									)
								},
							)
						}
						val channelSnapshots = deformer.channelGrids.gridsByChannel.map { (ch, track) ->
							AgentObjectChannelTrackSnapshot(
								channel = ch.name.lowercase(),
								staticValue = when (ch) {
									FormChannel.OPACITY -> deformer.opacity.toString()
									FormChannel.MULTIPLY_COLOR -> deformer.multiplyColor.toString()
									FormChannel.SCREEN_COLOR -> deformer.screenColor.toString()
									else -> ""
								},
								axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = track.cells.size,
                                cells = channelCells(track),
							)
						}
						AgentObjectSnapshot(
							target = target,
							name = deformer.name,
							parentId = deformer.parent?.raw,
							partId = deformer.partId?.raw,
							visible = deformer.isVisible,
							topologyInfo = topo,
							geometry = geoSnapshot,
							channels = channelSnapshots,
						)
					}
					is Deformer.Rotation -> {
						val grid = deformer.geometryGrid
						val topo = mapOf(
							"type" to "rotation",
							"baseAngle" to deformer.baseAngle.toString(),
						)
						val geoSnapshot = grid?.let { g ->
							AgentObjectGeometrySnapshot(
								axes = g.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = g.cells.size,
								cells = g.cells.map { cell ->
									val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
									AgentObjectCellSnapshot(
										coordinate = coord,
										originX = cell.form.originX,
										originY = cell.form.originY,
										angle = cell.form.angle,
										scale = cell.form.scale,
									)
								},
							)
						}
						val channelSnapshots = deformer.channelGrids.gridsByChannel.map { (ch, track) ->
							AgentObjectChannelTrackSnapshot(
								channel = ch.name.lowercase(),
								staticValue = when (ch) {
									FormChannel.OPACITY -> deformer.opacity.toString()
									FormChannel.MULTIPLY_COLOR -> deformer.multiplyColor.toString()
									FormChannel.SCREEN_COLOR -> deformer.screenColor.toString()
									FormChannel.FLIP_X -> deformer.flipX.toString()
									FormChannel.FLIP_Y -> deformer.flipY.toString()
									else -> ""
								},
								axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
								keyformCount = track.cells.size,
                                cells = channelCells(track),
							)
						}
						AgentObjectSnapshot(
							target = target,
							name = deformer.name,
							parentId = deformer.parent?.raw,
							partId = deformer.partId?.raw,
							visible = deformer.isVisible,
							topologyInfo = topo,
							geometry = geoSnapshot,
							channels = channelSnapshots,
						)
					}
				}
			}
			RigTargetKind.ART_MESH -> {
				val drawable = puppet.findDrawable(target.id)
					?: puppet.drawables.firstOrNull { rig.layerIdByDrawableId[it.id.raw] == target.id }
					?: throw IllegalArgumentException("Drawable not found: ${target.id}")
				val grid = drawable.geometryGrid
				val topo = mapOf(
					"type" to "art_mesh",
					"vertexCount" to (drawable.mesh?.vertexCount ?: 0).toString(),
					"triangleCount" to (drawable.mesh?.triangleCount ?: 0).toString(),
					"layerId" to (rig.layerIdByDrawableId[drawable.id.raw] ?: ""),
                    "baseOpacity" to drawable.opacity.toString(),
                    "baseDrawOrder" to drawable.drawOrder.toString(),
                    "maskedBy" to drawable.maskedBy.joinToString(",") { it.raw },
				)
				val geoSnapshot = grid?.let { g ->
					AgentObjectGeometrySnapshot(
						axes = g.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = g.cells.size,
						cells = g.cells.map { cell ->
							val coord = g.axes.indices.associate { i -> g.axes[i].parameterId.raw to g.axes[i].keys[cell.coordinate[i]] }
							AgentObjectCellSnapshot(
								coordinate = coord,
								positionDeltas = cell.form.positionDeltas.toList(),
							)
						},
					)
				}
				val channelSnapshots = drawable.channelGrids.gridsByChannel.map { (ch, track) ->
					AgentObjectChannelTrackSnapshot(
						channel = ch.name.lowercase(),
						staticValue = when (ch) {
							FormChannel.OPACITY -> drawable.opacity.toString()
							FormChannel.DRAW_ORDER -> drawable.drawOrder.toString()
							FormChannel.MULTIPLY_COLOR -> drawable.multiplyColor.toString()
							FormChannel.SCREEN_COLOR -> drawable.screenColor.toString()
							else -> ""
						},
						axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = track.cells.size,
                                cells = channelCells(track),
					)
				}
				AgentObjectSnapshot(
					target = target,
					name = drawable.name,
					parentId = drawable.parentDeformerId?.raw,
					partId = null,
					visible = drawable.isVisible,
					topologyInfo = topo,
					geometry = geoSnapshot,
					channels = channelSnapshots,
				)
			}
			RigTargetKind.PART -> {
				val part = puppet.parts.firstOrNull { it.id.raw == target.id }
					?: throw IllegalArgumentException("Part not found: ${target.id}")
				val channelSnapshots = part.channelGrids.gridsByChannel.map { (ch, track) ->
					AgentObjectChannelTrackSnapshot(
						channel = ch.name.lowercase(),
						staticValue = when (ch) {
							FormChannel.OPACITY -> part.composite.opacity.toString()
							FormChannel.DRAW_ORDER -> part.drawOrder.toString()
							FormChannel.MULTIPLY_COLOR -> part.composite.multiplyColor.toString()
							FormChannel.SCREEN_COLOR -> part.composite.screenColor.toString()
							else -> ""
						},
						axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = track.cells.size,
                                cells = channelCells(track),
					)
				}
				AgentObjectSnapshot(
					target = target,
					name = part.name,
					parentId = null,
					partId = part.id.raw,
					visible = part.isVisible,
					topologyInfo = mapOf("type" to "part", "childrenCount" to part.children.size.toString()),
					geometry = null,
					channels = channelSnapshots,
				)
			}
			RigTargetKind.GLUE -> {
				val glue = puppet.glues.firstOrNull { it.meshA.raw == target.id && it.meshB.raw == target.secondaryId }
					?: throw IllegalArgumentException("Glue not found: ${target.id} -> ${target.secondaryId}")
				val channelSnapshots = glue.channelGrids.gridsByChannel.map { (ch, track) ->
					AgentObjectChannelTrackSnapshot(
						channel = ch.name.lowercase(),
						staticValue = glue.intensity.toString(),
						axes = track.axes.map { AgentObjectAxisSnapshot(it.parameterId.raw, it.keys.toList()) },
						keyformCount = track.cells.size,
                                cells = channelCells(track),
					)
				}
				AgentObjectSnapshot(
					target = target,
					name = "Glue_${glue.meshA.raw}_${glue.meshB.raw}",
					parentId = null,
					partId = null,
					visible = true,
					topologyInfo = mapOf("type" to "glue", "meshA" to glue.meshA.raw, "meshB" to glue.meshB.raw),
					geometry = null,
					channels = channelSnapshots,
				)
			}
		}
	}
