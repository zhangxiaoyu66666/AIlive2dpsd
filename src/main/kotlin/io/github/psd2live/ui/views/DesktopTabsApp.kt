package io.github.psd2live.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import io.github.psd2live.agent.AgentMcpConnectionInfo
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.components.CompactButton
import io.github.psd2live.ui.state.DesktopProjectTabs
import io.github.psd2live.ui.state.DesktopTabsState
import io.github.psd2live.ui.theme.LocalToolColors
import java.nio.file.Path
import javax.swing.JOptionPane

@Composable
fun FrameWindowScope.DesktopTabsApp(
    controller: DesktopProjectTabs,
    window: ComposeWindow,
    windowState: WindowState,
    connection: AgentMcpConnectionInfo?,
    startupError: String?,
    onClose: () -> Unit,
) {
    val tabs by controller.state.collectAsState()
    controller.confirmUnsaved = {
        JOptionPane.showOptionDialog(window, tr("project.unsaved"), tr("project.save"), JOptionPane.DEFAULT_OPTION,
            JOptionPane.QUESTION_MESSAGE, null, arrayOf(tr("project.save"), tr("project.discard"), tr("project.cancel")), tr("project.save"))
    }
    controller.reportError = { JOptionPane.showMessageDialog(window, it, tr("app.title"), JOptionPane.WARNING_MESSAGE) }
    val active = tabs.tabs.firstOrNull { it.id == tabs.activeId }
    if (active != null) key(active.id) {
        PSD2LiveApp(
            viewModel = active.viewModel, window = window, windowState = windowState,
            agentConnectionInfo = connection, agentStartupError = startupError, onCloseRequest = onClose,
            onOpenPath = controller::open,
            onNewTab = { controller.create() }, onCloseTab = { controller.requestClose(active.id) },
            projectTabs = { ProjectTabBar(controller, tabs) },
        )
    }
}

@Composable
private fun ProjectTabBar(controller: DesktopProjectTabs, tabs: DesktopTabsState) {
    val colors = LocalToolColors.current
    Row(Modifier.fillMaxWidth().height(36.dp).background(colors.windowBackground), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            tabs.tabs.forEach { tab -> key(tab.id) {
                val project by tab.viewModel.state.collectAsState()
                val selected = tab.id == tabs.activeId
                val title = (project.projectFile ?: project.loadedInputPath ?: project.inputPath).takeIf { it.isNotBlank() }
                    ?.let { runCatching { Path.of(it).fileName.toString() }.getOrDefault(it) } ?: tr("project.untitled")
                Row(Modifier.widthIn(min = 130.dp, max = 260.dp).fillMaxHeight()
                    .background(if (selected) colors.panelElevated else colors.panelBackground)
                    .selectable(selected, role = Role.Tab, onClick = { controller.select(tab.id) })
                    .padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title + if (project.projectDirty) " *" else "", color = if (selected) colors.textPrimary else colors.textMuted,
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        controller.agents.owner(tab.id)?.let { Text(tr("tabs.agent", it), color = colors.selectionText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    CompactButton(text = "×", modifier = Modifier.semantics { contentDescription = tr("tabs.close", title) }, onClick = { controller.requestClose(tab.id) }, enabled = !project.projectSaving && !project.isGenerating && !project.isAnalyzing)
                }
                Spacer(Modifier.width(1.dp))
            } }
        }
        if (tabs.activeId?.let(controller.agents::owner) != null) {
            CompactButton(text = tr("tabs.release"), onClick = {
                if (!controller.agents.releaseFromUi(tabs.activeId)) controller.reportError(tr("tabs.busy"))
            })
        }
        CompactButton(text = tr("tabs.new"), onClick = { controller.create() })
    }
}
