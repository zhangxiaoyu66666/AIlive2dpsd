package io.github.psd2live.ui.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.utils.NativeFilePicker
import io.github.psd2live.workflow.SourceCandidate

@Composable
internal fun SourceWorkflowPanel(vm: PSD2LiveViewModel, window: ComposeWindow) {
    val controller = vm.sourceWorkflow
    val ui by controller.state.collectAsState()
    val project by vm.state.collectAsState()
    val record = controller.record()
    val colors = LocalToolColors.current
    var endpoint by remember(record.endpoint) { mutableStateOf(record.endpoint) }
    var image by remember { mutableStateOf("") }
    var resolution by remember { mutableStateOf("1024") }
    var seed by remember { mutableStateOf("42") }
    var split by remember { mutableStateOf(true) }
    var offload by remember { mutableStateOf(true) }
    val enabled = !ui.busy && !project.isAnalyzing && !project.isGenerating && !project.projectSaving
    Column(Modifier.fillMaxWidth().background(colors.panelBackground).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactButton(tr("flow.title"), controller::toggle)
            Text(tr("flow.stages"), color = colors.textMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            project.sourceWorkflow?.imported?.let { Text(tr("flow.importedHash", it.sha256.take(10)), color = colors.selectionText, fontSize = 11.sp) }
            project.sourceWorkflow?.generation?.let { Text(tr("flow.generated"), color = colors.success, fontSize = 11.sp) }
        }
        if (ui.expanded) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CompactTextField(endpoint, { endpoint = it }, Modifier.width(225.dp), placeholder = tr("flow.endpoint"), enabled = enabled)
                CompactButton(tr("flow.connect"), { controller.action("connect", "endpoint" to endpoint) }, enabled = enabled)
                Text(if (ui.connected) tr("flow.connected") else tr("flow.notConnected"), color = colors.textMuted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                CompactButton(tr("flow.chooseResult"), {
                    NativeFilePicker.choosePsdFile(window, ui.candidate?.version?.path)?.let { controller.action("stage_result", "path" to it) }
                }, enabled = enabled)
            }
            Row(Modifier.height(232.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(266.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompactButton(tr("flow.chooseImage"), { NativeFilePicker.chooseImageFile(window, image)?.let { image = it } }, enabled = enabled)
                        Text(image.substringAfterLast('\\').substringAfterLast('/'), color = colors.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("flow.resolution"), color = colors.textPrimary, fontSize = 11.sp)
                        CompactTextField(resolution, { resolution = it }, Modifier.width(57.dp), enabled = enabled)
                        Text(tr("flow.seed"), color = colors.textPrimary, fontSize = 11.sp)
                        CompactTextField(seed, { seed = it }, Modifier.width(57.dp), enabled = enabled)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(split, { split = it }, enabled = enabled, modifier = Modifier.size(22.dp))
                        Text(tr("flow.split"), color = colors.textPrimary, fontSize = 11.sp)
                        Spacer(Modifier.width(12.dp))
                        Checkbox(offload, { offload = it }, enabled = enabled, modifier = Modifier.size(22.dp))
                        Text(tr("flow.offload"), color = colors.textPrimary, fontSize = 11.sp)
                    }
                    CompactButton(tr("flow.decompose"), {
                        controller.action("decompose", "image" to image, "resolution" to resolution, "seed" to seed, "split" to split.toString(), "offload" to offload.toString())
                    }, enabled = enabled && ui.connected && image.isNotBlank(), isPrimary = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactButton(tr("flow.resume"), { controller.action("resume") }, enabled = enabled && record.eventId != null)
                        CompactButton(tr("flow.stopWait"), { controller.action("cancel_wait") }, enabled = ui.busy)
                    }
                    Text(ui.error ?: ui.status, color = if (ui.error != null) colors.error else colors.textMuted,
                        fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f))
                }
                val candidate = ui.importCandidate ?: ui.candidate
                if (candidate != null) CandidatePreview(candidate, Modifier.width(160.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    candidate?.let {
                        Text(tr(if (ui.importCandidate != null) "flow.importPreview" else "flow.resultPreview"), color = colors.textPrimary, fontSize = 12.sp)
                        SelectionContainer { Text(it.version.path, color = colors.textPrimary, fontSize = 11.sp) }
                        Text(tr("flow.dimensions", it.width, it.height, it.layerNames.size), color = colors.textMuted, fontSize = 11.sp)
                        Text("SHA-256: ${it.version.sha256}", color = colors.textMuted, fontSize = 10.sp)
                        Text(it.layerNames.joinToString(" · "), color = colors.textMuted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactButton(tr("flow.confirm"), { controller.action("confirm", "sha256" to ui.candidate!!.version.sha256) }, enabled = enabled && ui.candidate != null && record.confirmed == null)
                        CompactButton(tr("flow.chooseImport"), {
                            NativeFilePicker.choosePsdFile(window, ui.importCandidate?.version?.path ?: record.confirmed?.path)?.let { controller.action("stage_import", "path" to it) }
                        }, enabled = enabled && record.confirmed != null)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactButton(tr("flow.savePsd"), {
                            candidate?.let { value -> NativeFilePicker.chooseSavePsdFile(window, value.version.path)?.let { target ->
                                controller.action("save_psd", "path" to target, "sha256" to value.version.sha256)
                            } }
                        }, enabled = enabled && candidate != null)
                        CompactButton(tr("flow.refreshImport"), {
                            ui.importCandidate?.let { controller.action("stage_import", "path" to it.version.path) }
                        }, enabled = enabled && record.confirmed != null && ui.importCandidate != null)
                    }
                    record.confirmed?.let { Text(tr("flow.confirmedHash", it.sha256.take(10)), color = colors.success, fontSize = 11.sp) }
                    if (ui.importCandidate != null && record.confirmed?.sha256 != ui.importCandidate?.version?.sha256) {
                        Text(tr("flow.manualRevision"), color = colors.warning, fontSize = 11.sp)
                    }
                    if (project.analysis != null) Text(tr("flow.forkNote"), color = colors.textMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactButton(tr("flow.import"), { controller.action("import", "sha256" to ui.importCandidate!!.version.sha256) }, enabled = enabled && record.confirmed != null && ui.importCandidate != null, isPrimary = true)
                        CompactButton(tr("flow.checkSource"), { controller.action("check_source") }, enabled = enabled && project.sourceWorkflow?.imported != null)
                        CompactButton(tr("flow.generate"), { vm.generateRig() }, enabled = enabled && project.analysis != null && project.sourceWorkflow?.imported != null)
                    }
                    if (ui.sourceChanged == true) Text(tr("flow.sourceChanged"), color = colors.warning, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CandidatePreview(candidate: SourceCandidate, modifier: Modifier) {
    val bitmap = remember(candidate) { candidate.preview.toComposeImageBitmap() }
    Image(bitmap, tr("flow.preview"), modifier.fillMaxHeight())
}
