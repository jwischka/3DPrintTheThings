package com.u1.slicer.browse

data class SharedModelLink(val site: ModelSite, val url: String)

/** Android shares can contain a title and commentary around the model URL. */
fun sharedModelLink(text: String): SharedModelLink? {
    val urls = Regex("https://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    for (match in urls.findAll(text.take(16_384))) {
        val url = match.value.trimEnd('.', ',', ';', ')', ']', '}')
        val site = ModelSites.forUrl(url) ?: continue
        return SharedModelLink(site, url)
    }
    return null
}

/** 3MF is itself ZIP-based and must always go directly to the model loader. */
fun isIncomingZip(fileName: String?, mimeType: String?): Boolean {
    val extension = fileName?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)
    if (extension in setOf("3mf", "stl", "obj", "step", "stp", "amf")) return false
    return extension == "zip" || mimeType?.substringBefore(';')?.trim()?.lowercase(java.util.Locale.ROOT) in
        setOf("application/zip", "application/x-zip-compressed")
}
