package io.github.psd2live.workflow

import kotlin.test.*

class GradioFileUrlTest {
    private val endpoint = "http://127.0.0.1:7866"

    @Test fun encodesRawWindowsFilenameWithoutChangingTheOriginOrPath() {
        val path = "M:\\AI Pet\\人物 #1?\\input.psd"
        val url = GradioFileUrl.resolve(endpoint, "$endpoint/gradio_api/file=$path")
        assertEquals("/gradio_api/file=$path", url.path)
        assertEquals("127.0.0.1:7866", url.authority)
        assertNull(url.query); assertNull(url.fragment)
        assertTrue(url.rawPath.contains("%5C"))
        assertTrue(url.rawPath.contains("%20"))
    }

    @Test fun preservesEncodedNamesAndSupportsRelativeAndPosixPaths() {
        for (prefix in listOf("", endpoint)) {
            val url = GradioFileUrl.resolve(endpoint, "$prefix/gradio_api/file=/tmp/%E4%BA%BA%20%23%25.psd")
            assertEquals("/gradio_api/file=/tmp/人 #%.psd", url.path)
            assertFalse(url.rawPath.contains("%2520"))
        }
    }

    @Test fun rejectsForeignOriginsAndWrongRoutesBeforeEncoding() {
        for (url in listOf("http://example.com/gradio_api/file=a.psd", "//example.com/gradio_api/file=a.psd",
            "http://127.0.0.1:7867/gradio_api/file=a.psd", "https://127.0.0.1:7866/gradio_api/file=a.psd",
            "http://user@127.0.0.1:7866/gradio_api/file=a.psd", "/other/gradio_api/file=a.psd",
            "/other?url=/gradio_api/file=a.psd", "/other#/gradio_api/file=a.psd", "/gradio_api/file=")) {
            assertFailsWith<IllegalArgumentException>(url) { GradioFileUrl.resolve(endpoint, url) }
        }
    }
}
