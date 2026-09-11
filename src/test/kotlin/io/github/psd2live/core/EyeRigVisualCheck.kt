package io.github.psd2live.core

import io.github.psd2live.agent.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis

/** Opt-in local-art QA. No user artwork or generated model enters the repository. */
object EyeRigVisualCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: source.psd output-directory" }
        val source = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        require(Files.isRegularFile(source))
        Files.createDirectories(output)
        if (source.fileName.toString().endsWith(".psd2live", ignoreCase = true)) {
            checkSavedEyeRig(source, output)
            return
        }
        val pipeline = PSD2LivePipeline()
        val report = buildJsonObject {
            for (version in 1..3) {
                lateinit var preview: RigPreviewModel
                val elapsed = measureTimeMillis { preview = pipeline.buildPreview(source, PipelineConfig(rigGenerationVersion = version)) }
                if (version >= 2) writeFaceTurnQa(preview, output, version)
                val eyeTags = setOf(SemanticTag.IRIDES, SemanticTag.EYEWHITE, SemanticTag.EYELASH, SemanticTag.EYEBROW)
                val eyes = preview.analysis.layers.filter { it.semantic.tag in eyeTags }
                require(eyes.isNotEmpty()) { "No eye layers found" }
                val bounds = eyes.map { it.bounds }.reduce(Bounds::union).expanded(.35f)
                val included = preview.rig.layerIdByDrawableId.values.toSet()
                val poses = linkedMapOf(
                    "open" to emptyMap<String, Float>(),
                    "half" to mapOf("ParamEyeLOpen" to .5f, "ParamEyeROpen" to .5f),
                    "near-closed" to mapOf("ParamEyeLOpen" to .1f, "ParamEyeROpen" to .1f),
                    "closed" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 0f),
                    "wink" to mapOf("ParamEyeLOpen" to 0f, "ParamEyeROpen" to 1f),
                    "gaze-left" to mapOf("ParamEyeBallX" to -1f),
                    "gaze-right" to mapOf("ParamEyeBallX" to 1f),
                    "brows-up" to mapOf("ParamBrowLY" to 1f, "ParamBrowRY" to 1f),
                    "brows-down" to mapOf("ParamBrowLY" to -1f, "ParamBrowRY" to -1f))
                for ((name, pose) in poses) {
                    val view = AgentViewRenderer.modelComposite(preview, "qa-v$version", pose, included, emptySet(),
                        AgentViewFrame.CanvasRect(bounds), AgentViewBackground.CHECKERBOARD, AgentViewOutputSpec(1200))
                    output.resolve("v$version-$name.png").writeBytes(view.png)
                }
                val inspect = inspectMeshes(preview, buildJsonObject { put("layer_ids", JsonArray(eyes.map { JsonPrimitive(it.source.id.raw) })) })
                put("v$version", JsonObject(inspect + ("buildMilliseconds" to JsonPrimitive(elapsed))))
                println("v$version: ${elapsed}ms; eye coverage written")
            }
        }
        output.resolve("coverage.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println(output)
    }
}
