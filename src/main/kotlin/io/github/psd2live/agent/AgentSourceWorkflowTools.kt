package io.github.psd2live.agent

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*

internal fun registerSourceWorkflowTools(server: Server, workspace: AgentWorkspace, tabs: AgentWorkspaceTabs?) {
    server.addWorkspaceTool(tabs, workspace, "source_workflow_get", "Read this tab's See-Through job, confirmation, import and generation lineage. Async actions report busy/status/error; poll here.",
        inputSchema = ToolSchema(properties = buildJsonObject { putJsonObject("include_preview") { put("type", "boolean") } }),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)) { request, target ->
        val result = target.sourceWorkflow("state", request.arguments ?: buildJsonObject { })
        val png = result["previewPngBase64"]?.jsonPrimitive?.contentOrNull
        val json = JsonObject(result - "previewPngBase64")
        CallToolResult(content = buildList { add(TextContent(json.toString())); if (png != null) add(ImageContent(data = png, mimeType = "image/png")) }, structuredContent = json)
    }
    server.addWorkspaceTool(tabs, workspace, "source_workflow", "Connect to local See-Through, upload and start decomposition, resume its event, stage/confirm exact PSD SHA-256, or import a reviewed version. Loaded models import into a new tab; returned importedTabId must be claimed separately. Actions are asynchronous and never auto-retry GPU submissions. cancel_wait stops waiting, not the server's job. stage_result also accepts a manually edited PSD.",
        inputSchema = ToolSchema(properties = buildJsonObject {
            putJsonObject("action") { put("type", "string"); put("enum", JsonArray(listOf("connect", "decompose", "resume", "stage_result", "confirm", "stage_import", "import", "check_source", "cancel_wait", "generate", "save_psd").map(::JsonPrimitive))) }
            listOf("endpoint", "image", "path", "sha256", "output", "expected_history_head_node_id").forEach { key -> putJsonObject(key) { put("type", "string") } }
            listOf("resolution", "seed").forEach { key -> putJsonObject(key) { put("type", "integer") } }
            listOf("split", "offload").forEach { key -> putJsonObject(key) { put("type", "boolean") } }
        }, required = listOf("action")),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true)) { request, target ->
        // Visible tab selection is a UI-only option, never accepted from an MCP request.
        val args = JsonObject((request.arguments ?: error("Missing arguments")) - "activate")
        val json = target.sourceWorkflow(args.getValue("action").jsonPrimitive.content, args)
        CallToolResult(content = listOf(TextContent(json.toString())), structuredContent = json)
    }
}
