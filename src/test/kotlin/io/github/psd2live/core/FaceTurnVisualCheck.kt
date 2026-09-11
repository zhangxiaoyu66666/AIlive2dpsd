package io.github.psd2live.core

import io.github.psd2live.agent.*
import kotlinx.serialization.json.*
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.ParameterId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Local-art diagnostics sharing the application's evaluator, renderer and actual export bundle. */
internal fun writeFaceTurnQa(preview: RigPreviewModel, output: Path, version: Int) {
    val root = output.resolve("turn-v$version")
    Files.createDirectories(root)
    val poses = buildMap<String, Map<String, Float>> {
        for (x in listOf(-45, -30, -15, 0, 15, 30, 45)) for (y in listOf(-30, 0, 30)) {
            put("x${x}y${y}", mapOf("ParamAngleX" to x.toFloat(), "ParamAngleY" to y.toFloat()))
        }
        for (x in listOf(-45, 0, 45)) for (open in listOf(0f, .5f, 1f)) {
            put("blink${x}-$open", mapOf("ParamAngleX" to x.toFloat(), "ParamEyeLOpen" to open, "ParamEyeROpen" to open))
        }
    }
    root.resolve("poses.json").writeText(JsonObject(poses.mapValues { (_, values) ->
        JsonObject(values.mapValues { JsonPrimitive(it.value) }) }).toString())
    val evaluator = CpuDeformationEvaluator()
    val geometry = buildJsonObject {
        for ((name, values) in poses) {
            val result = evaluator.evaluate(preview.rig.puppet, values.mapKeys { ParameterId(it.key) })
            put(name, buildJsonObject {
                for ((id, points) in result.worldPositions) put(id.raw, JsonArray(points.map { JsonPrimitive(it) }))
            })
            if (name.startsWith("x") && values["ParamAngleX"] in listOf(-45f, 0f, 45f)) {
                val view = AgentViewRenderer.modelComposite(preview, "turn-v$version", values,
                    preview.rig.layerIdByDrawableId.values.toSet(), emptySet(),
                    AgentViewFrame.CanvasRect(preview.analysis.anchors.face.expanded(.20f)),
                    AgentViewBackground.CHECKERBOARD, AgentViewOutputSpec(900))
                root.resolve("$name.png").writeBytes(view.png)
            }
        }
    }
    root.resolve("cpu-poses.json").writeText(geometry.toString())
    for (asset in preview.runtimeBundle.assets) {
        val target = root.resolve(asset.path).normalize()
        require(target.startsWith(root))
        Files.createDirectories(target.parent)
        target.writeBytes(asset.bytes)
    }
}
