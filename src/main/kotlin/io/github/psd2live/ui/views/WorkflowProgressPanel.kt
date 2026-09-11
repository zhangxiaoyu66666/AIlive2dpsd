package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.workflow.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The clock invalidates only this panel, not the image canvases or the project state. */
@Composable
internal fun WorkflowProgressPanel(progress: WorkflowProgress, status: String, error: String?, onStop: () -> Unit) {
    val colors = LocalToolColors.current
    val running = progress.outcome == WorkflowOutcome.RUNNING
    var now by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(progress.startedNanos, running) {
        while (running) { now = System.nanoTime(); delay(1000) }
    }
    val tint = when (progress.outcome) {
        WorkflowOutcome.COMPLETED -> colors.success
        WorkflowOutcome.FAILED -> colors.error
        WorkflowOutcome.STOPPED -> colors.warning
        else -> colors.accent
    }
    val title = if (running) tr("flow.progress.${progress.phase.name.lowercase()}") else tr("flow.progress.${progress.outcome.name.lowercase()}")
    val fraction = if (progress.outcome == WorkflowOutcome.COMPLETED) 1f else progress.fraction
    val elapsed = progress.elapsedSeconds(now)
    val elapsedText = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    val stale = running && progress.phase in setOf(WorkflowPhase.WAITING, WorkflowPhase.GENERATING) && progress.quietSeconds(now) >= 30
    Column(Modifier.fillMaxWidth().background(colors.panelElevated, RoundedCornerShape(5.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (fraction != null && running) Text("${(fraction * 100).roundToInt()}%", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(tr("flow.progress.elapsed", elapsedText), color = colors.textPrimary, fontSize = 14.sp)
            if (running) CompactButton(tr("flow.stopWait"), onStop)
        }
        val bar = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))
        if (fraction != null || !running) LinearProgressIndicator(fraction ?: 0f, bar, color = tint, backgroundColor = colors.border)
        else LinearProgressIndicator(bar, color = tint, backgroundColor = colors.border)
        val detail = when {
            error != null -> error
            progress.outcome == WorkflowOutcome.STOPPED -> tr("flow.waitStopped")
            progress.outcome == WorkflowOutcome.COMPLETED -> status.ifBlank { tr("flow.resultReady") }
            stale -> tr("flow.progress.quiet", progress.quietSeconds(now))
            progress.phase in setOf(WorkflowPhase.UPLOADING, WorkflowPhase.DOWNLOADING) && progress.bytes > 0 ->
                tr("flow.progress.bytes", "%.1f".format(progress.bytes / 1048576.0), progress.totalBytes?.let { "%.1f MiB".format(it / 1048576.0) } ?: tr("flow.progress.unknownSize"))
            progress.phase == WorkflowPhase.GENERATING -> status.ifBlank { tr("flow.progress.noEstimate") }
            progress.phase == WorkflowPhase.WAITING -> tr("flow.progress.queueHint")
            else -> tr("flow.progress.keepOpen")
        }
        Text(detail, color = if (stale) colors.warning else colors.textMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (progress.operation in setOf("decompose", "resume")) {
            val active = when (progress.phase) {
                WorkflowPhase.PREPARING, WorkflowPhase.UPLOADING -> 0
                WorkflowPhase.SUBMITTING, WorkflowPhase.WAITING, WorkflowPhase.GENERATING -> 1
                WorkflowPhase.DOWNLOADING -> 2
                WorkflowPhase.READING -> 3
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("uploadStep", "generateStep", "downloadStep", "previewStep").forEachIndexed { index, key ->
                    Text("${index + 1}. ${tr("flow.progress.$key")}", color = if (index == active) tint else colors.textMuted, fontSize = 11.sp)
                }
            }
        }
    }
}
