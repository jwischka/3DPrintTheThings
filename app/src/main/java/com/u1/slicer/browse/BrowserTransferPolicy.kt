package com.u1.slicer.browse

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** Identifies a direct model/archive URL before an external top-level navigation starts. */
internal fun isDirectModelDownloadUrl(url: String): Boolean {
    val parsed = url.toHttpUrlOrNull() ?: return false
    if (parsed.scheme != "https" || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
    val path = parsed.encodedPath.lowercase(Locale.ROOT)
    return path.endsWith(".stl") || path.endsWith(".3mf") || path.endsWith(".zip")
}
