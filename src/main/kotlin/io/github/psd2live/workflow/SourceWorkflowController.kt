package io.github.psd2live.workflow

import io.github.psd2live.agent.AgentWorkspaceStore
import io.github.psd2live.core.PSD2LivePipeline
import io.github.psd2live.core.PreviewRenderer
import io.github.psd2live.i18n.tr
import io.github.psd2live.ui.state.PSD2LiveViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class SourceCandidate(val version: SourceVersion, val width: Int, val height: Int, val layerNames: List<String>, val preview: BufferedImage, val layers: List<WorkflowLayer> = emptyList())
data class SourceWorkflowUi(
    val expanded: Boolean = false,
    val busy: Boolean = false,
    val connected: Boolean = false,
    val detecting: Boolean = false,
    val serviceError: String? = null,
    val input: WorkflowInput? = null,
    val options: DecomposeOptions = DecomposeOptions(),
    val optionsEdited: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val candidate: SourceCandidate? = null,
    val importCandidate: SourceCandidate? = null,
    val draft: SourceWorkflowRecord? = null,
    val importedTabId: String? = null,
    val sourceChanged: Boolean? = null,
    val progress: WorkflowProgress? = null,
)

/** One coordinator per project tab. All UI/MCP actions use this same state machine. */
class SourceWorkflowController(private val viewModel: PSD2LiveViewModel, private val workingDirectory: Path? = null) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(SourceWorkflowUi())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var editJob: Job? = null
    private val client by lazy { SeeThroughClient() }
    private var clientUsed = false
    private val directory by lazy { workingDirectory ?: AgentWorkspaceStore.defaultRoot().resolve("source-workflows").resolve(UUID.randomUUID().toString()) }
    var importVersion: (suspend (SourceVersion, SourceWorkflowRecord, Boolean) -> String)? = null
    var openImportedVersion: ((String) -> Boolean)? = null
    fun toggle() { mutable.update { it.copy(expanded = !it.expanded) } }
    fun setOptions(options: DecomposeOptions) { mutable.update { it.copy(options = options, optionsEdited = true) } }

    suspend fun restorePage() = withContext(Dispatchers.Main) {
        val record = viewModel.state.value.sourceWorkflow ?: return@withContext
        if (state.value.busy || state.value.candidate != null || record.confirmed == null) return@withContext
        start {
            val (confirmed, imported, input) = withContext(Dispatchers.IO) {
                val confirmed = readCandidate(record.confirmed)
                val imported = record.imported?.let { if (it.sha256 == confirmed.version.sha256) confirmed.copy(version = it) else readCandidate(it) }
                val input = record.decomposition?.get("imagePath")?.jsonPrimitive?.contentOrNull?.let { path ->
                    try { WorkflowImages.read(Path.of(path)).takeIf { it.sha256 == record.decomposition["imageSha256"]?.jsonPrimitive?.contentOrNull } }
                    catch (_: Exception) { null }
                }
                Triple(confirmed, imported, input)
            }
            mutable.update { it.copy(candidate = confirmed, importCandidate = imported ?: confirmed, input = it.input ?: input) }
        }
    }

    suspend fun detectService() = withContext(Dispatchers.Main) {
        if (state.value.busy || state.value.detecting) return@withContext
        mutable.update { it.copy(detecting = true) }
        clientUsed = true
        try {
            val service = client.describe(record().endpoint)
            mutable.update { it.copy(connected = true, serviceError = null, options = if (it.optionsEdited) it.options else service.defaults) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutable.update { it.copy(connected = false, serviceError = failure.message) } }
        finally { mutable.update { it.copy(detecting = false) } }
    }

    fun editInApplication() {
        if (editJob?.isActive == true || state.value.busy) return
        state.value.importedTabId?.let { if (openImportedVersion?.invoke(it) == true) return }
        editJob = scope.launch {
            try {
                if (record().confirmed == null) {
                    val candidate = checkNotNull(state.value.candidate)
                    execute("confirm", buildJsonObject { put("sha256", candidate.version.sha256) })
                    job?.join()
                    check(state.value.error == null) { state.value.error.orEmpty() }
                }
                val candidate = checkNotNull(state.value.importCandidate)
                execute("import", buildJsonObject { put("sha256", candidate.version.sha256); put("activate", true) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message) } }
        }
    }
    fun record(): SourceWorkflowRecord {
        val draft = mutable.value.draft
        val current = viewModel.state.value.sourceWorkflow
        return if (current?.imported != null && draft?.imported == current.imported && draft.confirmed == current.confirmed) current
            else draft ?: current ?: SourceWorkflowRecord()
    }

    fun snapshot(): JsonObject = buildJsonObject {
        val project = viewModel.state.value
        val projectBusy = project.isAnalyzing || project.isGenerating || project.projectSaving
        put("busy", state.value.busy || projectBusy); put("workflowBusy", state.value.busy); put("projectBusy", projectBusy)
        put("connected", state.value.connected); put("status", state.value.status)
        put("detecting", state.value.detecting); put("serviceError", state.value.serviceError)
        put("progress", state.value.progress?.toJson() ?: JsonNull)
        state.value.input?.let { put("inputPath", it.path); put("inputSha256", it.sha256) }
        put("projectStatus", project.statusText)
        put("error", state.value.error ?: project.errorMessage ?: project.projectSaveError)
        put("versions", record().toJson()); put("importedTabId", state.value.importedTabId)
        put("currentProjectVersions", viewModel.state.value.sourceWorkflow?.toJson() ?: JsonNull)
        state.value.candidate?.let { put("candidateSha256", it.version.sha256); put("candidatePath", it.version.path) }
        state.value.importCandidate?.let { put("importCandidateSha256", it.version.sha256) }
        (state.value.importCandidate ?: state.value.candidate)?.let { candidate ->
            put("previewSha256", candidate.version.sha256)
            put("previewWidth", candidate.width); put("previewHeight", candidate.height)
            put("previewLayerNames", JsonArray(candidate.layerNames.map(::JsonPrimitive)))
        }
        state.value.sourceChanged?.let { put("sourceChanged", it) }
    }

    suspend fun execute(action: String, args: JsonObject): JsonObject = withContext(Dispatchers.Main) {
        fun text(key: String): String = args[key]?.jsonPrimitive?.content ?: error("Missing $key")
        when (action) {
            "state" -> Unit
            "select_image" -> start(action) {
                val input = withContext(Dispatchers.IO) { WorkflowImages.read(Path.of(text("path"))) }
                mutable.update { it.copy(input = input, status = tr("flow.imageReady")) }
            }
            "connect" -> start(action) {
                mutable.update { it.copy(connected = false) }
                clientUsed = true
                val service = client.describe(text("endpoint"))
                val endpoint = service.endpoint
                val changed = endpoint != record().endpoint
                mutable.update { it.copy(connected = true, serviceError = null, options = if (it.optionsEdited) it.options else service.defaults, draft = if (changed) SourceWorkflowRecord(endpoint) else record(), candidate = if (changed) null else it.candidate, importCandidate = if (changed) null else it.importCandidate, status = tr("flow.connected")) }
            }
            "decompose" -> start(action) {
                check(state.value.connected) { tr("flow.connectFirst") }
                check(record().eventId == null || state.value.candidate != null) { tr("flow.resumeFirst") }
                val options = DecomposeOptions(args["resolution"]?.jsonPrimitive?.int ?: 1024, args["seed"]?.jsonPrimitive?.int ?: 42,
                    args["split"]?.jsonPrimitive?.boolean ?: true, args["offload"]?.jsonPrimitive?.boolean ?: true)
                val imagePath = Path.of(text("image"))
                val imageHash = withContext(Dispatchers.IO) { SourceVersions.sha256(imagePath) }
                args["expected_image_sha256"]?.jsonPrimitive?.content?.let { expected ->
                    check(expected == imageHash) { tr("flow.imageChanged") }
                }
                mutable.update { it.copy(candidate = null, importCandidate = null, importedTabId = null, draft = record().copy(confirmed = null, eventId = null)) }
                val decomposition = buildJsonObject {
                    put("imagePath", imagePath.toString()); put("imageSha256", imageHash); put("resolution", options.resolution)
                    put("seed", options.seed); put("split", options.split); put("offload", options.offload)
                }
                val eventId = client.submit(record().endpoint, imagePath, options, imageHash, ::updateProgress)
                mutable.update { it.copy(draft = record().copy(eventId = eventId, decomposition = decomposition)) }
                // Retain event identity before waiting. Reconnection must never submit another GPU job.
                withContext(Dispatchers.IO) { Files.createDirectories(directory); SourceVersions.writeJsonAtomically(directory.resolve("job.json"), record().toJson()) }
                receive()
            }
            "resume" -> start(action) { clientUsed = true; receive() }
            "stage_result" -> start(action) {
                val candidate = inspect(Path.of(text("path")))
                mutable.update { it.copy(candidate = candidate, importCandidate = null, importedTabId = null, draft = record().copy(confirmed = null, eventId = null, decomposition = null), sourceChanged = null, status = tr("flow.resultReady")) }
            }
            "confirm" -> start(action) {
                val candidate = checkNotNull(state.value.candidate) { tr("flow.chooseResult") }
                require(text("sha256") == candidate.version.sha256) { tr("flow.staleCandidate") }
                withContext(Dispatchers.IO) { SourceVersions.verify(candidate.version) }
                if (record().confirmed?.sha256 != candidate.version.sha256) {
                    mutable.update { it.copy(draft = record().copy(confirmed = candidate.version), importCandidate = candidate, importedTabId = null, status = tr("flow.confirmed")) }
                }
            }
            "save_psd" -> start(action) {
                val candidate = checkNotNull(state.value.importCandidate ?: state.value.candidate) { tr("flow.chooseResult") }
                require(text("sha256") == candidate.version.sha256) { tr("flow.staleCandidate") }
                val target = Path.of(text("path")).toAbsolutePath().normalize()
                viewModel.claimProjectPath(target)
                withContext(Dispatchers.IO) {
                    require(target.fileName.toString().endsWith(".psd", true)) { "Select a .psd destination" }
                    SourceVersions.verify(candidate.version)
                    Files.createDirectories(target.parent)
                    val temporary = Files.createTempFile(target.parent, ".editable-", ".psd")
                    try {
                        Files.copy(Path.of(candidate.version.snapshot), temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { Files.deleteIfExists(temporary) }
                }
                if (record().confirmed != null) {
                    val staged = inspect(target)
                    mutable.update { it.copy(importCandidate = staged, importedTabId = null, status = tr("flow.editableSaved")) }
                } else mutable.update { it.copy(status = tr("flow.editableSaved")) }
            }
            "stage_import" -> start(action) {
                check(record().confirmed != null) { tr("flow.confirmFirst") }
                val candidate = inspect(Path.of(text("path")))
                mutable.update { it.copy(importCandidate = candidate, importedTabId = null) }
            }
            "import" -> start(action) {
                check(!viewModel.state.value.isGenerating && !viewModel.state.value.isAnalyzing && !viewModel.state.value.projectSaving) { tr("tabs.busy") }
                val confirmed = checkNotNull(record().confirmed) { tr("flow.confirmFirst") }
                val candidate = checkNotNull(state.value.importCandidate) { tr("flow.chooseImport") }
                require(text("sha256") == candidate.version.sha256) { tr("flow.staleCandidate") }
                withContext(Dispatchers.IO) { SourceVersions.verify(confirmed); SourceVersions.verify(candidate.version) }
                val lineage = record().copy(imported = candidate.version, generation = null, parentProjectId = viewModel.state.value.projectId)
                val tab = checkNotNull(importVersion) { "Tab importer unavailable" }(candidate.version, lineage, args["activate"]?.jsonPrimitive?.booleanOrNull == true)
                mutable.update { it.copy(importedTabId = tab, draft = lineage, status = tr("flow.importStarted")) }
            }
            "generate" -> {
                check(!state.value.busy) { tr("tabs.busy") }
                check(!viewModel.state.value.isGenerating && !viewModel.state.value.isAnalyzing && !viewModel.state.value.projectSaving) { tr("tabs.busy") }
                check(viewModel.state.value.sourceWorkflow?.imported != null) { tr("flow.importFirst") }
                check(viewModel.sourceWorkflowHistoryHead() == text("expected_history_head_node_id")) { "History HEAD changed; read state again" }
                viewModel.generateRig(text("output"))
            }
            "check_source" -> start(action) {
                val imported = checkNotNull(viewModel.state.value.sourceWorkflow?.imported) { tr("flow.importFirst") }
                val changed = withContext(Dispatchers.IO) { SourceVersions.changed(imported) }
                mutable.update { it.copy(sourceChanged = changed, status = tr(if (changed) "flow.sourceChanged" else "flow.sourceCurrent")) }
            }
            "cancel_wait" -> { job?.cancelAndJoin(); mutable.update { it.copy(busy = false, status = tr("flow.waitStopped"),
                progress = it.progress?.let { progress -> if (progress.outcome == WorkflowOutcome.RUNNING) progress.finish(WorkflowOutcome.STOPPED) else progress }) } }
            else -> error("Unknown source workflow action: $action")
        }
        val result = snapshot()
        if (action == "state" && args["include_preview"]?.jsonPrimitive?.booleanOrNull == true) {
            val candidate = state.value.importCandidate ?: state.value.candidate
            if (candidate != null) return@withContext JsonObject(result + ("previewPngBase64" to JsonPrimitive(withContext(Dispatchers.Default) {
                val scale = minOf(1.0, 1024.0 / maxOf(candidate.width, candidate.height))
                val thumbnail = BufferedImage(maxOf(1, (candidate.width * scale).toInt()), maxOf(1, (candidate.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
                thumbnail.createGraphics().let { graphics ->
                    try { graphics.drawImage(candidate.preview, 0, 0, thumbnail.width, thumbnail.height, null) } finally { graphics.dispose() }
                }
                java.io.ByteArrayOutputStream().use { output ->
                    javax.imageio.ImageIO.write(thumbnail, "png", output)
                    java.util.Base64.getEncoder().encodeToString(output.toByteArray())
                }
            })))
        }
        result
    }

    fun action(action: String, vararg args: Pair<String, String>) {
        scope.launch { try { execute(action, JsonObject(args.associate { it.first to JsonPrimitive(it.second) } + ("activate" to JsonPrimitive(true)))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message, expanded = true) } } }
    }

    private fun start(operation: String = "restore", block: suspend () -> Unit) {
        check(!mutable.value.busy) { tr("tabs.busy") }
        mutable.update { it.copy(busy = true, expanded = true, error = null, status = "", progress = WorkflowProgress(operation)) }
        job = scope.launch {
            try { block(); mutable.update { it.copy(progress = it.progress?.finish(WorkflowOutcome.COMPLETED)) } }
            catch (cancelled: CancellationException) {
                mutable.update { it.copy(progress = it.progress?.finish(WorkflowOutcome.STOPPED)) }
                throw cancelled
            }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: tr("flow.failed"), progress = it.progress?.finish(WorkflowOutcome.FAILED)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun receive() {
        val path = client.receive(record().endpoint, checkNotNull(record().eventId) { tr("flow.noEvent") }, directory, ::updateProgress) { message ->
            mutable.update { it.copy(status = message) }
        }
        val candidate = inspect(path)
        mutable.update { it.copy(candidate = candidate, importCandidate = null, importedTabId = null, status = tr("flow.resultReady")) }
    }

    private suspend fun inspect(path: Path): SourceCandidate = withContext(Dispatchers.IO) {
        updateProgress(WorkflowProgressUpdate(WorkflowPhase.READING))
        val version = SourceVersions.capture(path, directory)
        try { readCandidate(version) } catch (failure: Throwable) { Files.deleteIfExists(Path.of(version.snapshot)); throw failure }
    }

    private fun readCandidate(version: SourceVersion): SourceCandidate {
        SourceVersions.verify(version)
        val analysis = PSD2LivePipeline().inspect(Path.of(version.snapshot))
        val preview = PreviewRenderer.composite(analysis.source)
        val layers = analysis.source.layers.map { layer -> WorkflowLayer(layer.name,
            WorkflowImages.thumbnail(PreviewRenderer.rasterImage(layer.raster.width, layer.raster.height, layer.raster.rgba), 320)) }
        return SourceCandidate(version, analysis.source.widthPx, analysis.source.heightPx, analysis.source.layers.map { it.name }, preview, layers)
    }

    private fun updateProgress(update: WorkflowProgressUpdate) {
        mutable.update { it.copy(progress = it.progress?.advance(update)) }
    }

    override fun close() { scope.cancel(); if (clientUsed) client.close() }
}
