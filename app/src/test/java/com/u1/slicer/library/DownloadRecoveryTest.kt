package com.u1.slicer.library

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DownloadRecoveryTest {
    @Test fun `missing model is recoverable after store reopen without becoming openable`() = runBlocking {
        val files = Files.createTempDirectory("library-recovery").toFile()
        try {
            val source = File(files, "source.stl").apply { writeText("solid cube\nendsolid cube") }
            val store = DownloadStore(files, OkHttpClient())
            val provenance = DownloadProvenance("printables", "https://www.printables.com/model/42", "Cube")
            val saved = store.importFile(source, "cube.stl", provenance)
            assertTrue(store.fileFor(saved).delete())

            val reopened = DownloadStore(files, OkHttpClient())
            assertEquals(emptyList<DownloadEntry>(), reopened.listing().entries)
            assertEquals(1, reopened.listing().unreadableEntries)
            val recovery = reopened.recoverableDownloads().single()
            assertEquals(saved.sha256, recovery.sha256)
            assertEquals(provenance.pageUrl, recovery.provenance.pageUrl)
            assertTrue(reopened.forgetRecoverable(recovery))
            assertTrue(reopened.recoverableDownloads().isEmpty())
            assertEquals(0, reopened.listing().unreadableEntries)
        } finally { files.deleteRecursively() }
    }

    @Test fun `truncated model can be forgotten but healthy model cannot`() = runBlocking {
        val files = Files.createTempDirectory("library-recovery-truncated").toFile()
        try {
            val source = File(files, "source.stl").apply { writeText("solid cube\nendsolid cube") }
            val store = DownloadStore(files, OkHttpClient())
            val saved = store.importFile(source, "cube.stl", DownloadProvenance("local", "", "Cube"))
            assertTrue(store.recoverableDownloads().isEmpty())
            store.fileFor(saved).writeText("short")
            val recovery = store.recoverableDownloads().single()
            store.fileFor(saved).writeText(source.readText())
            assertFalse(store.forgetRecoverable(recovery))
            assertTrue(store.recoverableDownloads().isEmpty())
        } finally { files.deleteRecursively() }
    }
}
