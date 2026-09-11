package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.components.CompactTextField
import io.github.psd2live.ui.state.PSD2LiveViewModel
import io.github.psd2live.ui.theme.LocalToolColors
import io.github.psd2live.ui.utils.NativeFilePicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.awt.Window
import kotlin.math.roundToInt

/** A full workspace page: input and output remain side by side throughout the operation. */
@Composable
internal fun SourceWorkflowPage(vm: PSD2LiveViewModel, window: Window?, autoDetect: Boolean = true) {
    val controller = vm.sourceWorkflow
    val ui by controller.state.collectAsState()
    val project by vm.state.collectAsState()
    val record = controller.record()
    val colors = LocalToolColors.current
    val enabled = !ui.busy && !project.isAnalyzing && !project.isGenerating && !project.projectSaving
    var endpoint by remember(record.endpoint) { mutableStateOf(record.endpoint) }
    var showConnection by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf(ui.options.seed.toString()) }
    var selectedLayer by remember(ui.candidate?.version?.sha256, ui.importCandidate?.version?.sha256) { mutableStateOf<Int?>(null) }
    val candidate = ui.importCandidate ?: ui.candidate
    LaunchedEffect(ui.options.seed) { seed = ui.options.seed.toString() }
    LaunchedEffect(project.projectOpenGeneration) { controller.restorePage() }
    LaunchedEffect(controller, autoDetect) {
        if (autoDetect) while (isActive) { controller.detectService(); delay(15000) }
    }
    val chooseImage = {
        NativeFilePicker.chooseImageFile(window, ui.input?.path)?.let { controller.action("select_image", "path" to it) }
        Unit
    }

    Column(Modifier.fillMaxSize().background(colors.windowBackground).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("●", color = if (ui.connected) colors.success else colors.textMuted, fontSize = 12.sp)
            Text(tr(if (ui.connected) "flow.serviceReady" else if (ui.detecting) "flow.detecting" else "flow.serviceOffline"),
                color = colors.textPrimary, fontSize = 12.sp)
            Text(record.endpoint, color = colors.textMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            CompactButton(tr("flow.connectionSettings"), { showConnection = !showConnection })
            CompactButton(tr("flow.chooseResult"), {
                NativeFilePicker.choosePsdFile(window, candidate?.version?.path)?.let { controller.action("stage_result", "path" to it) }
            }, enabled = enabled)
        }
        if (showConnection) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactTextField(endpoint, { endpoint = it }, Modifier.width(260.dp), enabled = enabled)
            CompactButton(tr("flow.connect"), { controller.action("connect", "endpoint" to endpoint) }, enabled = enabled && !ui.detecting)
            Text(ui.serviceError.orEmpty(), color = colors.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("flow.original"), color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    CompactButton(tr(if (ui.input == null) "flow.uploadImage" else "flow.replaceImage"), chooseImage, enabled = enabled, height = 32.dp)
                }
                WorkflowPreviewPane(ui.input?.preview, tr("flow.inputEmpty"), Modifier.weight(1f).fillMaxWidth(),
                    emptyAction = { CompactButton(tr("flow.uploadImage"), chooseImage, enabled = enabled, isPrimary = true, height = 38.dp) })
                Text(ui.input?.path ?: tr("flow.imageFormats"), color = colors.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("flow.resolution"), color = colors.textPrimary, fontSize = 12.sp)
                    Slider(ui.options.resolution.toFloat(), { value -> controller.setOptions(ui.options.copy(resolution = (value / 64).roundToInt() * 64)) },
                        enabled = enabled, valueRange = 768f..1280f, steps = 7, modifier = Modifier.weight(1f).height(28.dp),
                        colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.border))
                    Text(ui.options.resolution.toString(), color = colors.textPrimary, fontSize = 12.sp)
                    Text(tr("flow.seed"), color = colors.textPrimary, fontSize = 12.sp)
                    CompactTextField(seed, { value -> seed = value; value.toIntOrNull()?.takeIf { it in 0..9999 }?.let { controller.setOptions(ui.options.copy(seed = it)) } }, Modifier.width(58.dp), enabled = enabled)
                    CompactButton(tr("flow.randomSeed"), { controller.setOptions(ui.options.copy(seed = kotlin.random.Random.nextInt(10000))) }, enabled = enabled)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkflowOption(tr("flow.split"), ui.options.split, enabled) { controller.setOptions(ui.options.copy(split = it)) }
                    WorkflowOption(tr("flow.offload"), ui.options.offload, enabled) { controller.setOptions(ui.options.copy(offload = it)) }
                }
                CompactButton(tr("flow.decompose"), {
                    ui.input?.let { input -> controller.action("decompose", "image" to input.path, "expected_image_sha256" to input.sha256,
                        "resolution" to ui.options.resolution.toString(), "seed" to seed, "split" to ui.options.split.toString(), "offload" to ui.options.offload.toString()) }
                }, enabled = enabled && ui.connected && ui.input != null && seed.toIntOrNull() in 0..9999,
                    isPrimary = true, modifier = Modifier.fillMaxWidth(), height = 38.dp)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("flow.generatedPsd"), color = colors.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    candidate?.let { Text(tr("flow.dimensions", it.width, it.height, it.layerNames.size), color = colors.textMuted, fontSize = 11.sp) }
                }
                WorkflowPreviewPane(selectedLayer?.let { candidate?.layers?.getOrNull(it)?.preview } ?: candidate?.preview,
                    tr(if (ui.busy) "flow.resultWaiting" else "flow.outputEmpty"), Modifier.weight(1f).fillMaxWidth())
                candidate?.let { WorkflowLayerGallery(it.layers, selectedLayer) { selectedLayer = it } }
                Text(when {
                    candidate == null -> tr("flow.outputActionsHint")
                    ui.input != null && record.decomposition?.get("imageSha256")?.jsonPrimitive?.contentOrNull?.let { it != ui.input?.sha256 } == true -> tr("flow.previousResult")
                    ui.importCandidate != null && record.confirmed?.sha256 != candidate.version.sha256 -> tr("flow.manualRevision")
                    else -> candidate.version.path
                }, color = colors.textMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactButton(tr("flow.exportPsd"), {
                        candidate?.let { value -> NativeFilePicker.chooseSavePsdFile(window, value.version.path)?.let { target ->
                            controller.action("save_psd", "path" to target, "sha256" to value.version.sha256)
                        } }
                    }, enabled = enabled && candidate != null, height = 38.dp, modifier = Modifier.weight(1f))
                    CompactButton(tr("flow.editHere"), controller::editInApplication,
                        enabled = enabled && candidate != null, isPrimary = true, height = 38.dp, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompactButton(tr("flow.confirm"), { controller.action("confirm", "sha256" to ui.candidate!!.version.sha256) }, enabled = enabled && ui.candidate != null && record.confirmed == null)
                    CompactButton(tr("flow.chooseEditedPsd"), {
                        NativeFilePicker.choosePsdFile(window, candidate?.version?.path)?.let { controller.action("stage_import", "path" to it) }
                    }, enabled = enabled && record.confirmed != null)
                    CompactButton(tr("flow.refreshImport"), {
                        ui.importCandidate?.let { controller.action("stage_import", "path" to it.version.path) }
                    }, enabled = enabled && ui.importCandidate != null)
                }
                Text(if (record.confirmed == null) tr("flow.editConfirms") else tr("flow.confirmedHash", record.confirmed.sha256.take(10)),
                    color = if (record.confirmed == null) colors.textMuted else colors.success, fontSize = 11.sp)
            }
        }
        if (ui.busy || project.isAnalyzing) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = colors.accent)
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ui.error ?: ui.status.ifBlank { tr("flow.pageHint") }, color = if (ui.error != null) colors.error else colors.textMuted,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (ui.busy) CompactButton(tr("flow.stopWait"), { controller.action("cancel_wait") })
            else if (record.eventId != null && candidate == null) CompactButton(tr("flow.resume"), { controller.action("resume") }, enabled = enabled)
            if (project.sourceWorkflow?.imported != null) {
                CompactButton(tr("flow.checkSource"), { controller.action("check_source") }, enabled = enabled)
                CompactButton(tr("flow.generate"), { vm.generateRig() }, enabled = enabled && project.analysis != null)
            }
        }
    }
}

@Composable
private fun WorkflowOption(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, enabled = enabled, modifier = Modifier.size(26.dp),
            colors = CheckboxDefaults.colors(checkedColor = LocalToolColors.current.accent, uncheckedColor = LocalToolColors.current.textMuted))
        Text(label, color = LocalToolColors.current.textPrimary, fontSize = 12.sp)
    }
}
