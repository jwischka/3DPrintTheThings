package com.u1.slicer.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCatalogTest {
    private fun entry(hash: String, name: String, title: String, site: String, author: String?,
                      size: Long, time: Long) = DownloadEntry(hash, name, size, time,
        DownloadProvenance(site, "https://example.com/model", title, author))

    private val entries = listOf(
        entry("a", "Blue.3mf", "Blue Benchy", "printables", "Alice", 10, 100),
        entry("b", "Red.stl", "Red Vase", "thingiverse", "Bob", 30, 200),
        entry("c", "Green.3mf", "Green Benchy", "printables", "Carol", 20, 300),
    )

    @Test fun `search matches all terms across metadata fields`() {
        assertEquals(listOf("a"), libraryEntries(entries, "  ALICE blue  ", LibrarySort.NEWEST).map { it.sha256 })
        assertEquals(listOf("c", "a"), libraryEntries(entries, "printables benchy", LibrarySort.NEWEST).map { it.sha256 })
        assertEquals(emptyList<String>(), libraryEntries(entries, "benchy vase", LibrarySort.NEWEST).map { it.sha256 })
    }

    @Test fun `sort options are deterministic`() {
        assertEquals(listOf("c", "b", "a"), libraryEntries(entries, "", LibrarySort.NEWEST).map { it.sha256 })
        assertEquals(listOf("a", "c", "b"), libraryEntries(entries, "", LibrarySort.NAME).map { it.sha256 })
        assertEquals(listOf("c", "a", "b"), libraryEntries(entries, "", LibrarySort.SITE).map { it.sha256 })
        assertEquals(listOf("b", "c", "a"), libraryEntries(entries, "", LibrarySort.SIZE).map { it.sha256 })
    }

    @Test fun `recoverable metadata obeys the same search and sort choices`() {
        val broken = entries.map {
            RecoverableDownload(it.sha256, it.fileName, it.sizeBytes, it.createdAtMillis, it.provenance)
        }
        assertEquals(listOf("c", "a"), recoverableEntries(broken, "printables benchy", LibrarySort.NEWEST).map { it.sha256 })
        assertEquals(listOf("b", "c", "a"), recoverableEntries(broken, "", LibrarySort.SIZE).map { it.sha256 })
    }
}
