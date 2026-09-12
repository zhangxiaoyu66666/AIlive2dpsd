package io.github.psd2live.ui.views

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import io.github.psd2live.agent.AgentMcpConnectionInfo
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.DesktopProjectTabs
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
    // Keep the strip's position outside the keyed project content.
    val tabScroll = rememberScrollState()
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
            projectTabs = { ProjectTabBar(controller, tabs, tabScroll) },
        )
    }
}
