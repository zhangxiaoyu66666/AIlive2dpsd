package io.github.psd2live.agent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Explicit addressing: the visible UI tab is never an MCP routing input. */
class AgentWorkspaceTabs {
    private class Entry(val workspace: AgentWorkspace, val summary: (() -> JsonObject)?) {
        val operations = Mutex()
        @Volatile var owner: String? = null
        var lease: String? = null
        var closed = false
    }
    private val entries = ConcurrentHashMap<String, Entry>()
    var createTab: (suspend (String?) -> String)? = null
    var ownershipChanged: () -> Unit = {}

    fun register(id: String, workspace: AgentWorkspace, summary: (() -> JsonObject)? = null) {
        check(entries.putIfAbsent(id, Entry(workspace, summary)) == null) { "Tab already registered" }
    }

    fun owner(id: String): String? = entries[id]?.owner

    fun releaseFromUi(id: String): Boolean {
        val entry = entries[id] ?: return false
        if (!entry.operations.tryLock()) return false
        try {
            if (entry.closed || entry.summary?.invoke()?.get("busy")?.jsonPrimitive?.booleanOrNull == true) return false
            entry.owner = null
            entry.lease = null
            ownershipChanged()
            return true
        } finally { entry.operations.unlock() }
    }

    fun manifest(): JsonObject = buildJsonObject {
        putJsonArray("tabs") {
            entries.toSortedMap().forEach { (id, entry) ->
                add(buildJsonObject {
                    if (entry.summary != null) {
                        entry.summary.invoke().forEach { (key, value) -> put(key, value) }
                    } else {
                        val state = entry.workspace.snapshot()
                        put("project_file", state.projectFile?.let(::JsonPrimitive) ?: JsonNull)
                        put("input_name", state.inputName?.let(::JsonPrimitive) ?: JsonNull)
                        put("dirty", state.projectDirty)
                        put("busy", state.busy)
                        put("loaded", state.loaded)
                    }
                    put("tab_id", id)
                    put("agent_name", entry.owner?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }
    }

    suspend fun claim(id: String, name: String): String {
        require(name.isNotBlank() && name.length <= 80) { "agent_name must contain 1..80 characters" }
        val entry = requireEntry(id)
        return entry.operations.withLock {
            check(!entry.closed) { "Tab is closed" }
            check(entry.owner == null) { "Tab is already claimed by ${entry.owner}; use a separate tab" }
            UUID.randomUUID().toString().also {
                entry.owner = name
                entry.lease = it
                ownershipChanged()
            }
        }
    }

    suspend fun release(id: String, lease: String) {
        val entry = requireEntry(id)
        entry.operations.withLock {
            check(entry.summary?.invoke()?.get("busy")?.jsonPrimitive?.booleanOrNull != true) { "Tab has an active background operation; stop waiting or let it finish first" }
            check(!entry.closed && entry.lease == lease) { "Invalid or expired lease_id" }
            entry.owner = null
            entry.lease = null
            ownershipChanged()
        }
    }

    suspend fun <T> withWorkspace(id: String, lease: String?, mutating: Boolean, action: suspend (AgentWorkspace) -> T): T {
        val entry = requireEntry(id)
        return entry.operations.withLock {
            check(!entry.closed) { "Tab is closed" }
            if (mutating) check(entry.lease != null && entry.lease == lease) {
                "Writing requires tab_claim and the returned lease_id for this tab"
            }
            action(entry.workspace)
        }
    }

    /** UI close refuses an in-flight tool instead of destroying its workspace. */
    fun tryRemove(id: String, canClose: () -> Boolean): Boolean {
        val entry = entries[id] ?: return true
        if (!entry.operations.tryLock()) return false
        try {
            if (!canClose()) return false
            entry.closed = true
            entries.remove(id, entry)
            return true
        } finally { entry.operations.unlock() }
    }

    /** Window close is all-or-nothing, including tabs with an in-flight MCP operation. */
    fun tryRemoveAll(ids: List<String>, canClose: () -> Boolean): Boolean {
        val locked = mutableListOf<Pair<String, Entry>>()
        try {
            for (id in ids.distinct().sorted()) {
                val entry = entries[id] ?: return false
                if (!entry.operations.tryLock()) return false
                locked += id to entry
                if (entry.closed) return false
            }
            if (!canClose()) return false
            locked.forEach { (id, entry) -> entry.closed = true; entries.remove(id, entry) }
            return true
        } finally { locked.asReversed().forEach { (_, entry) -> entry.operations.unlock() } }
    }

    private fun requireEntry(id: String): Entry = entries[id] ?: error("Unknown or closed tab_id: $id. Call tab_list.")
}
