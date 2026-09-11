package io.github.psd2live.project

import java.nio.file.Files
import kotlin.test.*

class TabPathClaimsTest {
    @Test fun aliasesAndNestedExportsCannotCrossWrite() {
        val root = Files.createTempDirectory("tab-path-claims")
        try {
            val claims = TabPathClaims()
            claims.claim("a", root.resolve("project.psd2live"))
            claims.claim("a", root.resolve("project.psd2live"))
            assertFailsWith<IllegalStateException> { claims.claim("b", root.resolve("child/../project.psd2live")) }
            claims.claim("a", root.resolve("export"), directory = true)
            assertFailsWith<IllegalStateException> { claims.claim("b", root.resolve("export/nested"), directory = true) }
            assertFailsWith<IllegalStateException> { claims.claim("b", root, directory = true) }
            assertFailsWith<IllegalStateException> { claims.claim("b", root.resolve("export/project.psd2live")) }
            claims.claim("b", root.resolve("export-b"), directory = true)
            claims.release("a")
            claims.claim("b", root.resolve("project.psd2live"))
        } finally { Files.delete(root) }
    }
}
