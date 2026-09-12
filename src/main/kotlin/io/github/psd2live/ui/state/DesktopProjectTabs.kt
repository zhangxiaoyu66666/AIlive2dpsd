package io.github.psd2live.ui.state

import io.github.psd2live.agent.AgentWorkspaceTabs
import io.github.psd2live.agent.ViewModelAgentWorkspace
import io.github.psd2live.project.TabPathClaims
import io.github.psd2live.i18n.I18n
import io.github.psd2live.i18n.tr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class DesktopProjectTab(val id: String, val viewModel: PSD2LiveViewModel, val workspace: ViewModelAgentWorkspace)
data class DesktopTabsState(val tabs: List<DesktopProjectTab> = emptyList(), val activeId: String? = null, val ownershipVersion: Long = 0, val openingTabs: Set<String> = emptySet())

/** UI lifecycle lives on Main; MCP routing remains independent of selection. */
class DesktopProjectTabs : AutoCloseable {
    val agents = AgentWorkspaceTabs()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(DesktopTabsState())
    val state = mutable.asStateFlow()
    private val paths = TabPathClaims()
    private val closed = AtomicBoolean()
    var confirmUnsaved: (() -> Int)? = null
    var reportError: (String) -> Unit = {}

    init {
        agents.createTab = { path -> withContext(Dispatchers.Main) { create(path?.let(Path::of), activate = false).id } }
        agents.ownershipChanged = { scope.launch { mutable.value = mutable.value.copy(ownershipVersion = mutable.value.ownershipVersion + 1) } }
        create()
    }

    fun create(path: Path? = null, activate: Boolean = true): DesktopProjectTab {
        check(!closed.get()) { "Application is closing" }
        if (path != null) {
            require(Files.isRegularFile(path)) { tr("dialog.inputInvalid", path) }
            require(path.fileName.toString().substringAfterLast('.').lowercase() in setOf("psd", "psd2live")) { "Expected .psd or .psd2live" }
            check(paths.owner(path) == null) { tr("tabs.pathConflict", path) }
        }
        val id = UUID.randomUUID().toString()
        // Reserve before publishing the tab so a concurrent Save As cannot take its source path.
        if (path != null) paths.claim(id, path)
        val tab = prepareTab(id, path)
        val vm = tab.viewModel
        registerTab(tab)
        mutable.value = mutable.value.copy(tabs = mutable.value.tabs + tab)
        if (activate || mutable.value.activeId == null) select(id)
        if (path != null) {
            if (path.fileName.toString().endsWith(".psd2live", true)) vm.openProject(path)
            else { vm.setInputPath(path.toString()); vm.analyze() }
        }
        return tab
    }

    private fun prepareTab(id: String, path: Path?): DesktopProjectTab {
        val vm = PSD2LiveViewModel()
        vm.setWorkspaceTab(if (path == null) WorkspaceTab.SEE_THROUGH else WorkspaceTab.PREVIEW)
        val workspace = ViewModelAgentWorkspace(vm)
        vm.attachAgentWorkspace(workspace)
        vm.presentationActive = false
        vm.confirmUnsavedChanges = { confirmUnsaved?.invoke() ?: 2 }
        vm.claimProjectPath = { paths.claim(id, it) }
        vm.claimExportPath = { paths.claim(id, it, directory = true) }
        val tab = DesktopProjectTab(id, vm, workspace)
        vm.sourceWorkflow.openImportedVersion = { targetId ->
            val existing = mutable.value.tabs.firstOrNull { it.id == targetId }
            if (existing != null) { existing.viewModel.setWorkspaceTab(WorkspaceTab.PREVIEW); select(targetId) }
            existing != null
        }
        vm.sourceWorkflow.importVersion = { version, lineage, activate ->
            val destination = if (vm.state.value.analysis == null) tab else create(activate = false)
            destination.viewModel.setSourceWorkflow(lineage)
            destination.viewModel.setInputPath(version.snapshot)
            destination.viewModel.setOutputPath(Path.of(version.path).parent.resolve("${Path.of(version.path).fileName.toString().substringBeforeLast('.')}-${version.sha256.take(8)}-${destination.id.take(8)}-export").toString())
            destination.viewModel.setWorkspaceTab(WorkspaceTab.PREVIEW)
            destination.viewModel.analyze()
            if (activate) {
                select(destination.id)
            }
            destination.id
        }
        return tab
    }

    private fun registerTab(tab: DesktopProjectTab) {
        val id = tab.id; val vm = tab.viewModel; val workspace = tab.workspace
        // Tab listing reads only the cheap immutable UI summary, never hashes every model's history.
        agents.register(id, workspace) {
            val current = vm.state.value
            buildJsonObject {
                put("project_file", current.projectFile?.let(::JsonPrimitive) ?: JsonNull)
                put("input_name", current.projectSourceName?.let(::JsonPrimitive)
                    ?: current.inputPath.takeIf { it.isNotBlank() }?.let { JsonPrimitive(Path.of(it).fileName.toString()) } ?: JsonNull)
                put("dirty", current.projectDirty)
                put("busy", current.isAnalyzing || current.isGenerating || current.projectSaving || vm.sourceWorkflow.state.value.busy || id in mutable.value.openingTabs)
                put("loaded", current.analysis != null)
                current.errorMessage?.let { put("errorMessage", it) }
            }
        }
    }

    fun open(path: Path) {
        try {
            val existing = paths.owner(path)
            if (existing != null) select(existing) else create(path)
        } catch (failure: Exception) { reportError(failure.message ?: tr("tabs.openFailed")) }
    }

    /** Replace the active slot only after the new file has loaded; errors keep the existing model. */
    fun openInCurrent(path: Path) {
        val current = mutable.value.tabs.firstOrNull { it.id == mutable.value.activeId } ?: return
        try {
            require(Files.isRegularFile(path)) { tr("dialog.inputInvalid", path) }
            require(path.fileName.toString().substringAfterLast('.').lowercase() in setOf("psd", "psd2live")) { tr("drop.unsupported") }
            check(!tabBusy(current)) { tr("tabs.busy") }
            val previousOwner = paths.owner(path)
            check(previousOwner == null || previousOwner == current.id) { tr("tabs.pathConflict", path) }
            current.viewModel.withSavedChanges {
                if (tabBusy(current) || mutable.value.tabs.none { it === current }) { reportError(tr("tabs.busy")); return@withSavedChanges }
                val editVersion = current.viewModel.state.value.projectEditVersion
                mutable.value = mutable.value.copy(openingTabs = mutable.value.openingTabs + current.id)
                scope.launch {
                    var replacement: DesktopProjectTab? = null
                    var installed = false
                    try {
                        paths.claim(current.id, path)
                        val candidate = prepareTab(current.id, path).also { replacement = it }
                        candidate.viewModel.setLanguage(current.viewModel.state.value.currentLanguage)
                        if (path.fileName.toString().endsWith(".psd2live", true)) candidate.viewModel.openProject(path)
                        else { candidate.viewModel.setInputPath(path.toString()); candidate.viewModel.analyze() }
                        candidate.viewModel.state.first { !it.isAnalyzing }
                        check(candidate.viewModel.state.value.analysis != null && candidate.viewModel.state.value.errorMessage == null) {
                            candidate.viewModel.state.value.errorMessage ?: tr("tabs.openFailed")
                        }
                        check(agents.tryRemove(current.id) {
                            val unchanged = mutable.value.tabs.any { it === current } && !tabBusy(current, includeOpening = false) &&
                                current.viewModel.state.value.projectEditVersion == editVersion
                            // Resolve filesystem claims before revoking the old workspace; failure keeps it usable.
                            if (unchanged) paths.retainOnly(current.id, path)
                            unchanged
                        }) { tr("tabs.busy") }
                        candidate.viewModel.setWorkspaceTab(WorkspaceTab.PREVIEW)
                        registerTab(candidate)
                        mutable.value = mutable.value.copy(tabs = mutable.value.tabs.map { if (it === current) candidate else it })
                        installed = true
                        current.viewModel.close()
                        withContext(Dispatchers.IO) { current.workspace.close() }
                        if (mutable.value.activeId == candidate.id) select(candidate.id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { reportError(failure.message ?: tr("tabs.openFailed")) }
                    finally {
                        if (!installed) {
                            replacement?.viewModel?.close()
                            replacement?.workspace?.close()
                            if (previousOwner == null) paths.release(current.id, path)
                        }
                        mutable.value = mutable.value.copy(openingTabs = mutable.value.openingTabs - current.id)
                    }
                }
            }
        } catch (failure: Exception) { reportError(failure.message ?: tr("tabs.openFailed")) }
    }

    private fun tabBusy(tab: DesktopProjectTab, includeOpening: Boolean = true): Boolean {
        val current = tab.viewModel.state.value
        return current.isAnalyzing || current.isGenerating || current.projectSaving || tab.viewModel.sourceWorkflow.state.value.busy ||
            includeOpening && tab.id in mutable.value.openingTabs
    }

    fun select(id: String) {
        val selected = mutable.value.tabs.firstOrNull { it.id == id } ?: return
        mutable.value.tabs.forEach { it.viewModel.presentationActive = it.id == id }
        if (selected.viewModel.state.value.currentLanguage != I18n.currentLanguage) selected.viewModel.setLanguage(I18n.currentLanguage)
        mutable.value = mutable.value.copy(activeId = id)
    }

    fun requestClose(id: String, afterClose: (() -> Unit)? = null) {
        val tab = mutable.value.tabs.firstOrNull { it.id == id } ?: return
        val previousActive = mutable.value.activeId
        val position = mutable.value.tabs.indexOf(tab)
        val snapshot = tab.viewModel.state.value
        if (tabBusy(tab)) { reportError(tr("tabs.busy")); return }
        if (snapshot.projectDirty) select(id)
        tab.viewModel.withSavedChanges {
            // The user may have explicitly discarded dirty changes. Recheck ongoing work under the MCP gate.
            if (!agents.tryRemove(id) {
                val latest = tab.viewModel.state.value
                !latest.isAnalyzing && !latest.isGenerating && !latest.projectSaving &&
                    !tab.viewModel.sourceWorkflow.state.value.busy &&
                    (latest.projectEditVersion == snapshot.projectEditVersion || !latest.projectDirty)
            }) { reportError(tr("tabs.busy")); return@withSavedChanges }
            tab.viewModel.close()
            scope.launch(Dispatchers.IO) { tab.workspace.close() }
            paths.release(id)
            val remaining = mutable.value.tabs.filterNot { it.id == id }
            val next = previousActive?.takeIf { candidate -> remaining.any { it.id == candidate } }
                ?: remaining.getOrNull(position.coerceAtMost(remaining.lastIndex))?.id
            mutable.value = mutable.value.copy(tabs = remaining, activeId = next)
            next?.let(::select)
            if (afterClose != null) afterClose() else if (remaining.isEmpty()) create()
        }
    }

    fun requestCloseAll(done: () -> Unit) {
        val next = mutable.value.tabs.firstOrNull()
        if (next == null) done() else requestClose(next.id) { requestCloseAll(done) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutable.value.tabs.forEach { it.viewModel.close(); it.workspace.close() }
        scope.cancel()
    }
}
