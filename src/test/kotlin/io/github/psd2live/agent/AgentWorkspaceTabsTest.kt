package io.github.psd2live.agent

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

internal class TabTestWorkspace(private val id: String) : AgentWorkspace {
    var writes = 0
    override fun snapshot() = AgentProjectSnapshot(id, "$id-$writes", loaded = true, inputName = id,
        canvasWidth = 10, canvasHeight = 10, busy = false, status = "ready", selectedLayerId = null,
        layers = emptyList(), parameters = emptyList())
    override suspend fun checkpoint(summary: String): AgentWorkspaceMutationResult {
        writes++
        return AgentWorkspaceMutationResult("$id-$writes", "$id-$writes", summary = summary)
    }
    override suspend fun saveProject(): AgentWorkspaceMutationResult = checkpoint("save")
    override suspend fun renderLayer(layerId: String, background: AgentViewBackground, output: AgentViewOutputSpec): AgentRenderedView = error("unused")
    override suspend fun renderContext(layerId: String, objectScale: Float, aspectRatio: Float, background: AgentViewBackground, output: AgentViewOutputSpec): AgentRenderedView = error("unused")
    override suspend fun renderModel(request: AgentModelViewRequest): AgentRenderedView = error("unused")
}

class AgentWorkspaceTabsTest {
    @Test fun leasesAreIndependentAndRevocable() = runBlocking<Unit> {
        val tabs = AgentWorkspaceTabs()
        val a = TabTestWorkspace("a"); val b = TabTestWorkspace("b")
        tabs.register("a", a); tabs.register("b", b)
        val leaseA = tabs.claim("a", "agent A"); val leaseB = tabs.claim("b", "agent B")
        assertFailsWith<IllegalStateException> { tabs.claim("a", "agent B") }
        assertFailsWith<IllegalStateException> { tabs.withWorkspace("b", leaseA, true) { it.checkpoint("wrong") } }
        assertFailsWith<IllegalStateException> { tabs.withWorkspace("a", null, true) { it.checkpoint("wrong") } }
        tabs.withWorkspace("b", leaseB, true) { it.checkpoint("right") }
        assertEquals(0, a.writes); assertEquals(1, b.writes)
        assertFalse(tabs.manifest().toString().contains(leaseA))
        tabs.release("a", leaseA)
        val next = tabs.claim("a", "agent C")
        assertNotEquals(leaseA, next)
        assertFailsWith<IllegalStateException> { tabs.withWorkspace("a", leaseA, true) { it.checkpoint("stale") } }
        assertFailsWith<IllegalStateException> { tabs.release("a", leaseA) }
    }

    @Test fun differentTabsProgressWhileSameTabAndCloseWait() = runBlocking<Unit> {
        withTimeout(5000) {
            val tabs = AgentWorkspaceTabs()
            tabs.register("a", TabTestWorkspace("a")); tabs.register("b", TabTestWorkspace("b"))
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val first = launch { tabs.withWorkspace("a", null, false) { entered.complete(Unit); finish.await() } }
            entered.await()
            assertFalse(tabs.tryRemove("a") { true })
            val same = async { tabs.withWorkspace("a", null, false) { "a" } }
            yield(); assertFalse(same.isCompleted)
            assertEquals("b", tabs.withWorkspace("b", null, false) { "b" })
            finish.complete(Unit); first.join(); assertEquals("a", same.await())
            assertTrue(tabs.tryRemove("a") { true })
            assertFailsWith<IllegalStateException> { tabs.withWorkspace("a", null, false) { } }
        }
    }

    @Test fun refusedCloseAndCancellationRetainUsableWorkspace() = runBlocking<Unit> {
        val tabs = AgentWorkspaceTabs(); tabs.register("a", TabTestWorkspace("a"))
        assertFalse(tabs.tryRemove("a") { false })
        assertFailsWith<CancellationException> { tabs.withWorkspace("a", null, false) { throw CancellationException() } }
        assertEquals("a", tabs.withWorkspace("a", null, false) { it.snapshot().projectId })
    }
}
