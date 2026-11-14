package com.u1.slicer.library

import java.util.Locale

enum class LibrarySort { NEWEST, NAME, SITE, SIZE }

/** Search and sort stored metadata without touching model files or the network. */
fun libraryEntries(entries: List<DownloadEntry>, query: String, sort: LibrarySort): List<DownloadEntry> {
    val terms = searchTerms(query)
    val matching = entries.filter { entry ->
        matches(terms, entry.provenance.title, entry.fileName, entry.provenance.site, entry.provenance.author)
    }
    return when (sort) {
        LibrarySort.NEWEST -> matching.sortedWith(compareByDescending<DownloadEntry> { it.createdAtMillis }.thenBy { it.sha256 })
        LibrarySort.NAME -> matching.sortedWith(compareBy<DownloadEntry> { it.fileName.lowercase(Locale.ROOT) }.thenBy { it.sha256 })
        LibrarySort.SITE -> matching.sortedWith(compareBy<DownloadEntry> { it.provenance.site.lowercase(Locale.ROOT) }
            .thenByDescending { it.createdAtMillis }.thenBy { it.sha256 })
        LibrarySort.SIZE -> matching.sortedWith(compareByDescending<DownloadEntry> { it.sizeBytes }.thenBy { it.sha256 })
    }
}

fun recoverableEntries(entries: List<RecoverableDownload>, query: String, sort: LibrarySort): List<RecoverableDownload> {
    val terms = searchTerms(query)
    val matching = entries.filter { item ->
        matches(terms, item.provenance.title, item.fileName, item.provenance.site, item.provenance.author)
    }
    return when (sort) {
        LibrarySort.NEWEST -> matching.sortedWith(compareByDescending<RecoverableDownload> { it.createdAtMillis }.thenBy { it.sha256 })
        LibrarySort.NAME -> matching.sortedWith(compareBy<RecoverableDownload> { it.fileName.lowercase(Locale.ROOT) }.thenBy { it.sha256 })
        LibrarySort.SITE -> matching.sortedWith(compareBy<RecoverableDownload> { it.provenance.site.lowercase(Locale.ROOT) }
            .thenByDescending { it.createdAtMillis }.thenBy { it.sha256 })
        LibrarySort.SIZE -> matching.sortedWith(compareByDescending<RecoverableDownload> { it.sizeBytes }.thenBy { it.sha256 })
    }
}

private fun searchTerms(query: String) = query.trim().lowercase(Locale.ROOT)
    .split(Regex("\\s+")).filter { it.isNotEmpty() }

private fun matches(terms: List<String>, vararg fields: String?): Boolean {
    val text = fields.filterNotNull().joinToString(" ").lowercase(Locale.ROOT)
    return terms.all(text::contains)
}
