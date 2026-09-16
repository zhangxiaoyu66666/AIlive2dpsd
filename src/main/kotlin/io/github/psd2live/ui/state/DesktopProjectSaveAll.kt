package io.github.psd2live.ui.state

import io.github.psd2live.i18n.tr
import kotlinx.coroutines.flow.first

/** Save through the existing archive writer and location dialog, one project at a time. */
internal suspend fun saveAllProjects(
    tabs: List<DesktopProjectTab>,
    select: (String) -> Unit,
    isAvailable: (DesktopProjectTab) -> Boolean,
): Boolean {
    for (tab in tabs) {
        check(isAvailable(tab)) { tr("tabs.busy") }
        val vm = tab.viewModel
        val state = vm.state.value
        if (!state.projectDirty && (state.projectFile != null || state.analysis == null)) continue
        select(tab.id)
        check(state.analysis != null) { tr("tabs.busy") }
        if (state.projectFile != null) {
            vm.saveProjectNow()
        } else {
            vm.requestProjectSave()
            val result = vm.state.first { !it.showProjectLocationDialog || it.projectSaveError != null }
            check(result.projectSaveError == null) { result.projectSaveError!! }
            // Closing the location dialog without a saved file cancels the whole batch.
            if (result.projectFile == null) return false
        }
        check(!vm.state.value.projectDirty) { tr("project.saveAllChanged") }
    }
    check(tabs.all { isAvailable(it) && !it.viewModel.state.value.projectDirty }) { tr("project.saveAllChanged") }
    return true
}
