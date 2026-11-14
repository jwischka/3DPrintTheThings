package com.u1.slicer.browse

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/** A model site with an HTTPS-only start URL and a constrained set of owned hosts. */
data class ModelSite(
    val id: String,
    val name: String,
    val startUrl: String,
    val allowedHosts: Set<String>,
    val searchPath: String,
    val searchQueryParameter: String? = null,
    val searchQueryInPath: Boolean = false,
    val searchParameters: Map<String, String> = emptyMap(),
) {
    fun searchUrl(query: String): String {
        val base = startUrl.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
            ?: throw IllegalStateException("Invalid HTTPS start URL for $id")
        require(ownsPage(startUrl)) { "Start URL is outside the $id host allowlist" }

        val builder = base.newBuilder().encodedPath(searchPath)
        if (searchQueryInPath) builder.addPathSegment(query)
        searchQueryParameter?.let { builder.addQueryParameter(it, query) }
        searchParameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    fun ownsPage(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.lowercase(Locale.ROOT).trimEnd('.')
        return allowedHosts.any { root ->
            val normalizedRoot = root.lowercase(Locale.ROOT).trimEnd('.')
            host == normalizedRoot || host.endsWith(".$normalizedRoot")
        }
    }
}

object ModelSites {
    val all: List<ModelSite> = listOf(
        ModelSite(
            id = "makerworld",
            name = "MakerWorld",
            startUrl = "https://makerworld.com/en",
            allowedHosts = setOf("makerworld.com"),
            searchPath = "/en/search/models",
            searchQueryParameter = "keyword",
        ),
        ModelSite(
            id = "printables",
            name = "Printables",
            startUrl = "https://www.printables.com",
            allowedHosts = setOf("printables.com"),
            searchPath = "/search/models",
            searchQueryParameter = "q",
        ),
        ModelSite(
            id = "thingiverse",
            name = "Thingiverse",
            startUrl = "https://www.thingiverse.com",
            allowedHosts = setOf("thingiverse.com"),
            searchPath = "/search",
            searchQueryParameter = "q",
        ),
        ModelSite(
            id = "thangs",
            name = "Thangs",
            startUrl = "https://thangs.com",
            allowedHosts = setOf("thangs.com"),
            searchPath = "/search",
            searchQueryInPath = true,
            searchParameters = mapOf("scope" to "thangs", "view" to "list"),
        ),
        ModelSite(
            id = "cults3d",
            name = "Cults3D",
            startUrl = "https://cults3d.com/en",
            allowedHosts = setOf("cults3d.com"),
            searchPath = "/en/search",
            searchQueryParameter = "q",
        ),
    )

    fun find(id: String): ModelSite? = all.firstOrNull { it.id == id }

    fun forUrl(url: String): ModelSite? = all.firstOrNull { it.ownsPage(url) }
}

/** True for known social sign-in hosts that should be opened outside the in-app browser. */
fun shouldOpenExternalAuthUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    val host = parsed.host.lowercase(Locale.ROOT).trimEnd('.')
    return host == "accounts.google.com" || host.endsWith(".accounts.google.com") ||
        host == "appleid.apple.com" || host == "www.facebook.com" || host == "m.facebook.com"
}

/** Resolve a download name from Content-Disposition, URL, then caller fallback. */
fun resolveDownloadFilename(contentDisposition: String?, sourceUrl: String?, fallback: String): String {
    val parameters = parseDispositionParameters(contentDisposition.orEmpty())
    val extended = parameters["filename*"]?.let(::decodeExtendedFilename)
    val headerName = extended?.takeIf { it.isNotBlank() }
        ?: parameters["filename"]?.takeIf { it.isNotBlank() }
    val urlName = sourceUrl?.toHttpUrlOrNull()
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
        ?.pathSegments?.lastOrNull { it.isNotBlank() }
    val chosen = headerName ?: urlName ?: fallback
    val candidates = listOfNotNull(headerName, urlName, fallback)
    val extension = candidates.firstNotNullOfOrNull(::supportedExtension) ?: ".bin"
    val base = safeBasename(chosen).let(::removeExtension)
    return "${base.ifBlank { "download" }}$extension"
}

private fun parseDispositionParameters(header: String): Map<String, String> {
    val parts = mutableListOf<String>()
    var start = 0
    var quoted = false
    var escaped = false
    header.forEachIndexed { index, char ->
        when {
            escaped -> escaped = false
            quoted && char == '\\' -> escaped = true
            char == '"' -> quoted = !quoted
            char == ';' && !quoted -> {
                parts += header.substring(start, index)
                start = index + 1
            }
        }
    }
    parts += header.substring(start)

    val parameterParts = if (parts.firstOrNull()?.contains('=') == true) parts else parts.drop(1)
    return parameterParts.mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals < 0) return@mapNotNull null
        val key = part.substring(0, equals).trim().lowercase(Locale.ROOT)
        val raw = part.substring(equals + 1).trim()
        if (key != "filename" && key != "filename*") return@mapNotNull null
        val value = if (raw.length >= 2 && raw.first() == '"' && raw.last() == '"') {
            raw.substring(1, raw.length - 1).replace(Regex("\\\\(.)")) { it.groupValues[1] }
        } else raw
        key to value
    }.toMap()
}

private fun decodeExtendedFilename(value: String): String? {
    val firstQuote = value.indexOf('\'')
    val secondQuote = value.indexOf('\'', firstQuote + 1)
    if (firstQuote <= 0 || secondQuote < 0) return null
    val charset: Charset = when (value.substring(0, firstQuote).lowercase(Locale.ROOT)) {
        "utf-8" -> StandardCharsets.UTF_8
        "iso-8859-1" -> StandardCharsets.ISO_8859_1
        else -> return null
    }
    return runCatching {
        // URLDecoder treats '+' as a space; in RFC 5987 it is a literal attr-char.
        URLDecoder.decode(value.substring(secondQuote + 1).replace("+", "%2B"), charset.name())
    }.getOrNull()
}

private val supportedExtensions = setOf("3mf", "stl", "obj", "step", "stp", "amf", "zip")

private fun supportedExtension(name: String): String? {
    val dot = name.lastIndexOf('.')
    if (dot <= 0) return null
    val extension = name.substring(dot + 1)
    return extension.takeIf { it.lowercase(Locale.ROOT) in supportedExtensions }?.let { ".$it" }
}

private fun safeBasename(name: String): String = name
    .substringAfterLast('/')
    .substringAfterLast('\\')
    .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
    .replace(Regex("[<>:\"|?*]"), "_")
    .trim(' ', '.')

private fun removeExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}
