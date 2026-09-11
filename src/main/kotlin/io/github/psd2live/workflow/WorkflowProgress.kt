package io.github.psd2live.workflow

import kotlinx.serialization.json.*

enum class WorkflowPhase { PREPARING, UPLOADING, SUBMITTING, WAITING, GENERATING, DOWNLOADING, READING }
enum class WorkflowOutcome { RUNNING, COMPLETED, FAILED, STOPPED }
data class WorkflowProgressUpdate(val phase: WorkflowPhase, val bytes: Long = 0, val totalBytes: Long? = null)

/** Transfer percentages come only from measured bytes. Inference has no fabricated percentage. */
data class WorkflowProgress(
    val operation: String,
    val startedNanos: Long = System.nanoTime(),
    val updatedNanos: Long = startedNanos,
    val finishedNanos: Long? = null,
    val phase: WorkflowPhase = WorkflowPhase.PREPARING,
    val outcome: WorkflowOutcome = WorkflowOutcome.RUNNING,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0 && phase in setOf(WorkflowPhase.UPLOADING, WorkflowPhase.DOWNLOADING) }
        ?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
    fun elapsedSeconds(now: Long = System.nanoTime()): Long = ((finishedNanos ?: now) - startedNanos).coerceAtLeast(0) / 1_000_000_000
    fun quietSeconds(now: Long = System.nanoTime()): Long = (now - updatedNanos).coerceAtLeast(0) / 1_000_000_000
    fun advance(update: WorkflowProgressUpdate, now: Long = System.nanoTime()): WorkflowProgress {
        if (outcome != WorkflowOutcome.RUNNING) return this
        // Bound UI updates during fast local transfers; never throttle a stage change or the last byte.
        if (update.phase == phase && update.totalBytes != null && update.bytes < update.totalBytes && now - updatedNanos < 100_000_000) return this
        return copy(phase = update.phase, bytes = update.bytes.coerceAtLeast(0), totalBytes = update.totalBytes?.takeIf { it > 0 }, updatedNanos = now)
    }
    fun finish(result: WorkflowOutcome, now: Long = System.nanoTime()) = copy(outcome = result, finishedNanos = now)
    fun toJson(): JsonObject = buildJsonObject {
        put("operation", operation); put("phase", phase.name.lowercase()); put("outcome", outcome.name.lowercase())
        put("elapsedSeconds", elapsedSeconds()); put("secondsSinceUpdate", quietSeconds())
        put("bytes", bytes); put("totalBytes", totalBytes?.let(::JsonPrimitive) ?: JsonNull)
        put("fraction", fraction?.let(::JsonPrimitive) ?: JsonNull)
    }
}
