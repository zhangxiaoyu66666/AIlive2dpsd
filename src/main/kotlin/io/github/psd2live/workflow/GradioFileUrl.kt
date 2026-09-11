package io.github.psd2live.workflow

import io.ktor.http.encodeURLPath
import java.net.URI

/** Gradio appends a filesystem path to file=, which can contain unescaped Windows characters. */
internal object GradioFileUrl {
    private const val ROUTE = "/gradio_api/file="

    fun resolve(endpoint: String, value: String): URI {
        val base = URI(SeeThroughClient.endpoint(endpoint))
        val boundary = value.indexOf(ROUTE)
        require(boundary >= 0) { "See-Through returned an unexpected download location" }
        // Validate the URL origin and route before treating the suffix as a filesystem path.
        val route = base.resolve(value.substring(0, boundary) + ROUTE)
        require(route.scheme == base.scheme && route.rawAuthority == base.rawAuthority &&
            route.rawPath == ROUTE && route.rawQuery == null && route.rawFragment == null) {
            "See-Through returned an unexpected download location"
        }
        val path = value.substring(boundary + ROUTE.length)
        require(path.isNotBlank()) { "Result has no file path" }
        // Use Ktor's encoder; preserve existing %hh escapes and encode spaces, backslashes, # and ?.
        return URI(route.toASCIIString() + path.encodeURLPath(encodeEncoded = false))
    }
}
