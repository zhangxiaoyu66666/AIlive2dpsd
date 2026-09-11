package io.github.psd2live.project

import io.github.psd2live.i18n.tr
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Retain write destinations until tab close, including earlier Save As destinations. */
internal class TabPathClaims {
    private data class Claim(val tabId: String, val directory: Boolean)
    private val owners = mutableMapOf<String, Claim>()

    @Synchronized fun owner(path: Path): String? = owners[key(path)]?.tabId

    @Synchronized fun claim(tabId: String, path: Path, directory: Boolean = false) {
        val target = key(path)
        val conflict = owners.entries.any { (existing, claim) ->
            claim.tabId != tabId && (existing == target ||
                directory && existing.startsWith("$target/") || claim.directory && target.startsWith("$existing/"))
        }
        check(!conflict) { tr("tabs.pathConflict", path) }
        owners[target] = Claim(tabId, directory || owners[target]?.directory == true)
    }

    @Synchronized fun release(tabId: String) { owners.entries.removeIf { it.value.tabId == tabId } }

    private fun key(path: Path): String {
        var existing = path.toAbsolutePath().normalize()
        val tail = mutableListOf<String>()
        while (!Files.exists(existing) && existing.parent != null) {
            tail.add(existing.fileName.toString())
            existing = existing.parent
        }
        var canonical = existing.toRealPath()
        tail.asReversed().forEach { canonical = canonical.resolve(it) }
        val value = canonical.toString().replace('\\', '/')
        return if (System.getProperty("os.name").startsWith("Windows")) value.lowercase(Locale.ROOT) else value
    }
}
