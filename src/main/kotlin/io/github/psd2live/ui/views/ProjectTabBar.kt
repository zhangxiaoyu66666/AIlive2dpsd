package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.state.DesktopProjectTabs
import io.github.psd2live.ui.state.DesktopTabsState
import io.github.psd2live.ui.theme.LocalToolColors
import java.nio.file.Path

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ProjectTabBar(controller: DesktopProjectTabs, tabs: DesktopTabsState, scroll: ScrollState) {
    val colors = LocalToolColors.current
    val wheelStep = with(LocalDensity.current) { 40.dp.toPx() }
    var viewportWidth by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().height(46.dp).background(colors.windowBackground), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportWidth = it.width }
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val delta = event.changes.firstOrNull()?.scrollDelta
                if (delta != null) {
                    val distance = if (kotlin.math.abs(delta.x) > kotlin.math.abs(delta.y)) delta.x else delta.y
                    scroll.dispatchRawDelta(distance * wheelStep)
                    event.changes.forEach { it.consume() }
                }
            }) {
            Row(Modifier.fillMaxWidth().weight(1f).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                tabs.tabs.forEach { tab -> key(tab.id) {
                    val project by tab.viewModel.state.collectAsState()
                    val workflow by tab.viewModel.sourceWorkflow.state.collectAsState()
                    val selected = tab.id == tabs.activeId
                    val bringIntoView = remember { BringIntoViewRequester() }
                    var tabWidth by remember { mutableIntStateOf(0) }
                    LaunchedEffect(selected, tabWidth, viewportWidth) {
                        if (selected && tabWidth > 0 && viewportWidth > 0) bringIntoView.bringIntoView()
                    }
                    val title = (project.projectFile ?: project.projectSourceName ?: workflow.candidate?.version?.path ?: project.loadedInputPath ?: project.inputPath).takeIf { it.isNotBlank() }
                        ?.let { runCatching { Path.of(it).fileName.toString() }.getOrDefault(it) } ?: tr("project.untitled")
                    Row(Modifier.widthIn(min = 130.dp, max = 260.dp).fillMaxHeight()
                        .bringIntoViewRequester(bringIntoView).onSizeChanged { tabWidth = it.width }
                        .background(if (selected) colors.panelElevated else colors.panelBackground)
                        .selectable(selected, role = Role.Tab, onClick = { controller.select(tab.id) })
                        .padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title + if (tab.id in tabs.openingTabs) " · " + tr("tabs.opening") else if (project.projectDirty) " *" else "", color = if (selected) colors.textPrimary else colors.textMuted,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            controller.agents.owner(tab.id)?.let { Text(tr("tabs.agent", it), color = colors.selectionText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        CompactButton(text = "×", modifier = Modifier.semantics { contentDescription = tr("tabs.close", title) }, onClick = { controller.requestClose(tab.id) }, enabled = !project.projectSaving && !project.isGenerating && !project.isAnalyzing && !workflow.busy && tab.id !in tabs.openingTabs)
                    }
                    Spacer(Modifier.width(1.dp))
                } }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(scroll), Modifier.fillMaxWidth().height(10.dp),
                style = defaultScrollbarStyle().copy(thickness = 10.dp, unhoverColor = colors.textMuted, hoverColor = colors.accent))
        }
        if (tabs.activeId?.let(controller.agents::owner) != null) {
            CompactButton(text = tr("tabs.release"), onClick = {
                if (!controller.agents.releaseFromUi(tabs.activeId)) controller.reportError(tr("tabs.busy"))
            })
        }
        CompactButton(text = tr("tabs.new"), onClick = { controller.create() })
    }
}
