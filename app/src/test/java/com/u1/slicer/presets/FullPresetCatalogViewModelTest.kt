package com.u1.slicer.presets

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.u1.slicer.browse.BrowserDownload
import com.u1.slicer.browse.ModelSites
import com.u1.slicer.library.DownloadStore
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FullPresetCatalogViewModelTest {
    @get:Rule val files = TemporaryFolder()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val server = MockWebServer()
    private val site = requireNotNull(ModelSites.find("printables"))
    private lateinit var catalog: FullPresetCatalogViewModel

    @Before fun setUp() {
        Dispatchers.setMain(main)
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        catalog = FullPresetCatalogViewModel(Application(), httpClient = OkHttpClient(), filesDir = files.root)
    }

    @After fun tearDown() {
        try {
            if (::catalog.isInitialized) runBlocking {
                withTimeout(10_000) { catalog.viewModelScope.coroutineContext.job.cancelAndJoin() }
            }
        } finally {
            try {
                server.shutdown()
            } finally {
                Dispatchers.resetMain()
                main.close()
            }
        }
    }

    @Test fun savesBrowserFileWithPublicProvenance() = runBlocking<Unit> {
        val body = "solid cube\nendsolid cube\n"
        server.enqueue(MockResponse().setBody(body))
        startDownload("cube.stl")

        val ready = withTimeout(10_000) { catalog.state.first { it.transfer is FullPresetCatalogTransfer.Ready } }
        val transfer = ready.transfer as FullPresetCatalogTransfer.Ready
        val reopenedStore = DownloadStore(files.root, OkHttpClient())
        val entry = reopenedStore.entries().single()
        assertEquals(entry, transfer.entry)
        assertEquals(PAGE, entry.provenance.pageUrl)
        assertEquals(PAGE, transfer.sourceUrl)
        assertEquals(body, reopenedStore.fileFor(entry).readText())
    }

    @Test fun cancellingStreamRemovesPartialAndAllowsAnotherTransfer() = runBlocking<Unit> {
        server.enqueue(MockResponse()
            .setBody(Buffer().write(ByteArray(512 * 1024)))
            .throttleBody(16 * 1024, 500, TimeUnit.MILLISECONDS))
        startDownload("slow.stl")
        val downloads = File(files.root, "downloads")
        withTimeout(10_000) {
            while (!downloads.listFiles().orEmpty().any { it.name.startsWith(".partial-") && it.length() > 0 }) {
                delay(10)
            }
        }
        startDownload("overlapping.stl")
        assertEquals("slow.stl", (catalog.state.value.transfer as FullPresetCatalogTransfer.InProgress).fileName)
        withContext(main) { catalog.cancelTransfer() }
        withTimeout(10_000) { catalog.state.first { it.transfer == FullPresetCatalogTransfer.Idle } }
        assertTrue(downloads.listFiles().orEmpty().isEmpty())
        assertTrue(catalog.store.entries().isEmpty())

        server.enqueue(MockResponse().setBody("solid another\nendsolid another\n"))
        startDownload("another.stl")
        withTimeout(10_000) { catalog.state.first { it.transfer is FullPresetCatalogTransfer.Ready } }
        assertEquals("another.stl", catalog.store.entries().single().fileName)
        assertEquals("/slow.stl", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("/another.stl", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals(2, server.requestCount)
    }

    private suspend fun startDownload(fileName: String) = withContext(main) {
        val url = server.url("/$fileName").newBuilder().host("127.0.0.1").build()
        catalog.downloadFromBrowser(
            site,
            BrowserDownload.Network(url.toString(), fileName, "$PAGE?token=private#files", "Cube", "Test browser", null),
            origin = 1,
        )
    }

    private companion object {
        const val PAGE = "https://www.printables.com/model/42-cube"
    }
}
