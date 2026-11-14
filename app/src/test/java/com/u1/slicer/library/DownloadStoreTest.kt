package com.u1.slicer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class DownloadStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var filesDir: File
    private lateinit var store: DownloadStore
    private val provenance = DownloadProvenance(
        "printables", "https://www.printables.com/model/3161-benchy?session=secret",
        "Benchy", "Prusa", "CC BY", "https://media.printables.com/cover.jpg?token=secret",
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        filesDir = temporaryFolder.newFolder("files")
        store = DownloadStore(filesDir, OkHttpClient(), clockMillis = { 1234L })
    }

    // Keep deliberate disconnects independent of localhost's IPv4/IPv6 route ordering.
    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build()

    @After fun tearDown() { server.shutdown() }

    @Test fun downloadPersistsAcrossStoreInstancesAndDeletes() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("solid benchy\nendsolid benchy\n"))
        val entry = store.download(url("/signed?token=secret").toString(), "benchy.stl", provenance)
        assertEquals(64, entry.sha256.length)
        assertEquals("solid benchy\nendsolid benchy\n", store.fileFor(entry).readText())
        assertEquals(1, server.requestCount)

        val reopened = DownloadStore(filesDir, OkHttpClient())
        assertEquals(listOf(entry), reopened.entries())
        assertFalse(File(filesDir, "downloads/${entry.sha256}/metadata.json").readText().contains("secret"))
        assertTrue(reopened.delete(entry))
        assertTrue(reopened.entries().isEmpty())
        assertFalse(reopened.fileFor(entry).exists())
    }

    @Test fun identicalBytesKeepFirstNameAndProvenance() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("same"))
        server.enqueue(MockResponse().setBody("same"))
        val first = store.download(url("/one").toString(), "first.stl", provenance)
        val second = store.download(url("/two").toString(), "second.3mf", provenance.copy(title = "Other"))
        assertEquals(first, second)
        assertEquals(listOf(first), store.entries())
        assertEquals(2, server.requestCount)
    }

    @Test fun absentLibraryAndUncommittedDirectoryAreEmpty() = runBlocking<Unit> {
        assertTrue(store.entries().isEmpty())
        val orphan = File(filesDir, "downloads/${"a".repeat(64)}")
        assertTrue(orphan.mkdirs())
        File(orphan, "model.stl").writeText("unfinished")
        assertTrue(store.entries().isEmpty())
        assertTrue(File(orphan, "model.stl").isFile)
    }

    @Test fun invalidMetadataIsReportedWithoutDeletingFiles() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("model"))
        val entry = store.download(url("/model").toString(), "model.stl", provenance)
        val metadata = File(filesDir, "downloads/${entry.sha256}/metadata.json")
        metadata.writeText("{")
        assertThrows(IOException::class.java) { runBlocking { store.entries() } }
        assertTrue(store.fileFor(entry).isFile)

        metadata.writeText("""{"sha256":"${entry.sha256}","fileName":"../bad.stl","sizeBytes":5,"createdAtMillis":1,"provenance":{"site":"printables","pageUrl":"https://example.com/","title":"Model"}}""")
        assertThrows(IOException::class.java) { runBlocking { store.entries() } }
        assertTrue(store.fileFor(entry).isFile)
    }

    @Test fun nonDirectoryLibraryRootIsReported() {
        File(filesDir, "downloads").writeText("not a directory")
        assertThrows(IOException::class.java) { runBlocking { store.entries() } }
    }

    @Test fun stripsTraversalFromIncomingFilenameAndRejectsForgedEntry() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("model"))
        val entry = store.download(url("/model").toString(), "../../folder\\model.3mf", provenance)
        assertEquals("model.3mf", entry.fileName)
        assertThrows(IllegalArgumentException::class.java) {
            store.fileFor(entry.copy(fileName = "../escape.stl"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.fileFor(entry.copy(sha256 = "../escape"))
        }
    }

    @Test fun longFilenamePreservesExtensionAndOversizeMetadataIsRejected() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("model"))
        val entry = store.download(url("/long").toString(), "a".repeat(200) + ".3mf", provenance)
        assertEquals(128, entry.fileName.length)
        assertTrue(entry.fileName.endsWith(".3mf"))

        server.enqueue(MockResponse().setBody("different"))
        assertThrows(IOException::class.java) {
            runBlocking {
                store.download(url("/metadata").toString(), "model.stl",
                    provenance.copy(title = "a".repeat(70_000)))
            }
        }
        assertEquals(listOf(entry), store.entries())
    }

    @Test fun fileForRejectsSymlinkEvenWhenTargetIsInSameDirectory() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("model"))
        val entry = store.download(url("/symlink").toString(), "model.stl", provenance)
        val file = store.fileFor(entry)
        val target = File(file.parentFile, "alternate.stl")
        target.writeText("model")
        file.delete()
        try {
            Files.createSymbolicLink(file.toPath(), target.toPath())
        } catch (e: Exception) {
            assumeNoException(e)
        }
        assertThrows(IOException::class.java) { store.fileFor(entry) }
    }

    @Test fun htmlAndOversizeAreRejectedWithoutPartialFiles() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>login</html>"))
        assertThrows(IOException::class.java) {
            runBlocking { store.download(url("/html").toString(), "model.stl", provenance) }
        }
        server.enqueue(MockResponse().setBody("<html>login</html>"))
        assertThrows(IOException::class.java) {
            runBlocking { store.download(url("/sniff").toString(), "model.stl", provenance) }
        }
        val smallStore = DownloadStore(filesDir, OkHttpClient(), maxBytes = 4)
        server.enqueue(MockResponse().setBody("12345"))
        assertThrows(IOException::class.java) {
            runBlocking { smallStore.download(url("/large").toString(), "model.stl", provenance) }
        }
        assertTrue(runBlocking { store.entries() }.isEmpty())
        assertTrue(File(filesDir, "downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun droppedResponseLeavesNoPartialFiles() {
        server.enqueue(MockResponse().setBody("x".repeat(100_000))
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        assertThrows(IOException::class.java) {
            runBlocking { store.download(url("/broken").toString(), "model.stl", provenance) }
        }
        assertEquals("/broken", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertTrue(runBlocking { store.entries() }.isEmpty())
        assertTrue(File(filesDir, "downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationLeavesNoPartialFiles() {
        server.enqueue(MockResponse().setBody("x".repeat(100_000)).throttleBody(1, 100, TimeUnit.MILLISECONDS))
        runBlocking {
            val job = launch(Dispatchers.IO) { store.download(url("/slow").toString(), "model.stl", provenance) }
            assertEquals("/slow", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            withTimeout(5_000) { job.cancelAndJoin() }
        }
        assertTrue(runBlocking { store.entries() }.isEmpty())
        assertTrue(File(filesDir, "downloads").listFiles().orEmpty().isEmpty())
    }

    @Test fun redirectDropsHeadersAcrossHosts() = runBlocking<Unit> {
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/file")))
            other.enqueue(MockResponse().setBody("model"))
            store.download(url("/redirect").toString(), "model.stl", provenance,
                headers = mapOf("Cookie" to "private=value", "Authorization" to "Bearer secret"))
            val first = server.takeRequest()
            val second = other.takeRequest()
            assertEquals("private=value", first.getHeader("Cookie"))
            assertEquals(null, second.getHeader("Cookie"))
            assertEquals(null, second.getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }
}
