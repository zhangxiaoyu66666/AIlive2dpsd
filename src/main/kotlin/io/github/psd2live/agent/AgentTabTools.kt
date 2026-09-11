package io.github.psd2live.agent

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal const val TAB_INSTRUCTIONS = """
This server hosts independent project tabs. Start with tab_list, or tab_create with an optional absolute .psd/.psd2live path.
Every project tool requires an explicit tab_id. No tool follows the user's visible tab.
Before writing, call tab_claim(tab_id, agent_name), retain its private lease_id and include it on every write.
Use one tab per agent. Never share leases. A missing/wrong/expired lease is rejected. Read tools only require tab_id.
Release your lease with tab_release when finished. Claims survive transport reconnects until released or the tab closes.
Do not retry tab_create/tab_claim blindly after an uncertain response: inspect tab_list first.
The current/manifest resource lists tabs; use project_get_state(tab_id) for project contents.
"""

internal fun Server.addWorkspaceTool(
    tabs: AgentWorkspaceTabs?,
    fallback: AgentWorkspace,
    name: String,
    description: String,
    inputSchema: ToolSchema = ToolSchema(),
    toolAnnotations: ToolAnnotations? = null,
    handler: suspend (CallToolRequest, AgentWorkspace) -> CallToolResult,
) {
    val schema = if (tabs == null) inputSchema else inputSchema.copy(
        properties = JsonObject(inputSchema.properties.orEmpty() + buildJsonObject {
            putJsonObject("tab_id") { put("type", "string"); put("description", "Explicit target from tab_list/tab_create") }
            putJsonObject("lease_id") { put("type", "string"); put("description", "Private tab_claim token; required for writes") }
        }),
        required = inputSchema.required.orEmpty() + "tab_id" + if (toolAnnotations?.readOnlyHint == true) emptyList() else listOf("lease_id"),
    )
    addTool(name = name, description = description, inputSchema = schema, toolAnnotations = toolAnnotations) { request ->
        if (tabs == null) handler(request, fallback) else tabResult {
            val args = request.arguments.orEmpty()
            val id = args["tab_id"]?.jsonPrimitive?.content ?: error("tab_id is required. Call tab_list first.")
            tabs.withWorkspace(id, args["lease_id"]?.jsonPrimitive?.content, toolAnnotations?.readOnlyHint != true) {
                handler(request.copy(params = request.params.copy(arguments = JsonObject(args - setOf("tab_id", "lease_id")))), it)
            }
        }
    }
}

internal fun Server.addTabTools(tabs: AgentWorkspaceTabs) {
    val read = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false)
    val write = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false)
    fun schema(vararg fields: String, required: List<String> = fields.toList()) = ToolSchema(
        properties = buildJsonObject { fields.forEach { key -> putJsonObject(key) { put("type", "string") } } }, required = required)
    addTool("tab_list", "List project tabs and their agent names; never returns lease tokens.", toolAnnotations = read) {
        tabJson(tabs.manifest())
    }
    addTool("tab_create", "Create an independent background tab. Optional path opens a PSD or project asynchronously; poll project_get_state. Duplicate project paths are rejected.",
        inputSchema = schema("path", required = emptyList()), toolAnnotations = write) { request -> tabResult {
        val id = checkNotNull(tabs.createTab) { "Tab creation unavailable" }(request.arguments?.get("path")?.jsonPrimitive?.content)
        tabJson(buildJsonObject { put("tab_id", id) })
    } }
    addTool("tab_claim", "Claim an unowned tab for one agent. Retain the returned lease_id for writes and release.",
        inputSchema = schema("tab_id", "agent_name"), toolAnnotations = write) { request -> tabResult {
        val id = request.requiredString("tab_id")
        val lease = tabs.claim(id, request.requiredString("agent_name"))
        tabJson(buildJsonObject { put("tab_id", id); put("lease_id", lease) })
    } }
    addTool("tab_release", "Release your tab's write lease; an expired or foreign token is rejected.",
        inputSchema = schema("tab_id", "lease_id"), toolAnnotations = write) { request -> tabResult {
        tabs.release(request.requiredString("tab_id"), request.requiredString("lease_id"))
        tabJson(buildJsonObject { put("released", true) })
    } }
}

private suspend fun tabResult(block: suspend () -> CallToolResult): CallToolResult = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (failure: Exception) { CallToolResult(content = listOf(TextContent(failure.message ?: "Tab operation failed")), isError = true) }

private fun tabJson(value: JsonObject) = CallToolResult(content = listOf(TextContent(value.toString())))

private fun CallToolRequest.requiredString(key: String): String = arguments?.get(key)?.jsonPrimitive?.content ?: error("Missing $key")
