package io.github.psd2live.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class AgentTabMcpTest {
    @Test fun twoClientsUseExplicitTargetsAndCannotCrossWrite() = runBlocking<Unit> {
        withTimeout(20000) {
            val a = TabTestWorkspace("a"); val b = TabTestWorkspace("b")
            val tabs = AgentWorkspaceTabs().apply { register("a", a); register("b", b) }
            tabs.createTab = { "c".also { tabs.register(it, TabTestWorkspace(it)) } }
            val token = "test-only-token-01234567890123456789"
            val service = AgentMcpService(a, AgentMcpConfig(port = 0, token = token), tabs)
            val http = HttpClient(CIO) { install(SSE) }
            val first = Client(Implementation("agent-a", "1")); val second = Client(Implementation("agent-b", "1"))
            try {
                val endpoint = service.start().endpoint
                first.connect(StreamableHttpClientTransport(http, endpoint) { header("Authorization", "Bearer $token") })
                second.connect(StreamableHttpClientTransport(http, endpoint) { header("Authorization", "Bearer $token") })
                val tools = first.listTools().tools
                for (tool in tools.filterNot { it.name.startsWith("tab_") }) {
                    assertTrue("tab_id" in tool.inputSchema.required.orEmpty(), tool.name)
                    if (tool.annotations?.readOnlyHint != true) assertTrue("lease_id" in tool.inputSchema.required.orEmpty(), tool.name)
                }
                suspend fun Client.call(name: String, vararg args: Pair<String, String>): CallToolResult =
                    callTool(CallToolRequest(CallToolRequestParams(name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))))
                fun CallToolResult.json() = Json.parseToJsonElement((content.first() as TextContent).text).jsonObject
                val leaseA = first.call("tab_claim", "tab_id" to "a", "agent_name" to "A").json().getValue("lease_id").jsonPrimitive.content
                val leaseB = second.call("tab_claim", "tab_id" to "b", "agent_name" to "B").json().getValue("lease_id").jsonPrimitive.content
                assertTrue(first.call("project_get_state").isError == true)
                assertEquals("b", first.call("project_get_state", "tab_id" to "b").json().getValue("projectId").jsonPrimitive.content)
                assertTrue(first.call("project_save", "tab_id" to "b", "lease_id" to leaseA).isError == true)
                assertTrue(first.call("project_save", "tab_id" to "a").isError == true)
                assertFalse(second.call("project_save", "tab_id" to "b", "lease_id" to leaseB).isError == true)
                assertEquals(0, a.writes); assertEquals(1, b.writes)
                assertFalse(first.call("tab_list").toString().contains(leaseB))
                assertEquals("c", first.call("tab_create").json().getValue("tab_id").jsonPrimitive.content)
                first.call("tab_release", "tab_id" to "a", "lease_id" to leaseA)
                assertTrue(first.call("project_save", "tab_id" to "a", "lease_id" to leaseA).isError == true)
                assertTrue(tabs.tryRemove("b") { true })
                assertTrue(second.call("project_get_state", "tab_id" to "b").isError == true)
            } finally { first.close(); second.close(); http.close(); service.close() }
        }
    }
}
