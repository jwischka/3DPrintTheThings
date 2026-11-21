package com.u1.slicer

import android.app.Application
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.browse.printables.PrintablesClient
import com.u1.slicer.browse.printables.PrintablesFile
import com.u1.slicer.browse.printables.PrintablesFileType
import com.u1.slicer.browse.printables.PrintablesPrint
import com.u1.slicer.library.DownloadProvenance
import com.u1.slicer.library.DownloadStore
import com.u1.slicer.presets.FullPresetCatalogViewModel
import com.u1.slicer.presets.FullPresetCatalogTransfer
import com.u1.slicer.presets.FullPresetStore
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.presets.preview.PREVIEW_MODEL_SURFACE
import com.u1.slicer.ui.FullPresetScreen
import com.u1.slicer.ui.FullPresetTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.floor

/** Real catalog UI, HTTP download, library/ZIP selection, GL preview, JNI slicing and byte-exact export. */
@RunWith(AndroidJUnit4::class)
class FullPresetCatalogInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun browseDownloadPreviewSliceExportAndReopenArchive() {
        assertTrue(BuildConfig.FULL_PRESET_EXPERIMENTAL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val documents = File(context.cacheDir, "catalog-workflow-${UUID.randomUUID()}").apply { mkdirs() }
        val libraryRoot = File(documents, "library").apply { mkdirs() }
        val bundleStore = FullPresetStore(File(context.noBackupFilesDir, "full-presets"))
        val originalBundles = bundleStore.list().map { it.id }.toSet()
        val owners = ViewModelStore()
        val cube = instrumentation.context.assets.open("cube_10mm.stl").use { it.readBytes() }
        val zip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use {
                it.putNextEntry(ZipEntry("models/catalog-cube.stl"))
                it.write(cube)
                it.closeEntry()
            }
        }.toByteArray()
        val requests = ConcurrentLinkedQueue<String>()
        val denySearch = AtomicBoolean(true)
        val holdDownload = AtomicBoolean(false)
        val downloadStarted = CountDownLatch(1)
        val releaseDownload = CountDownLatch(1)
        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val endpoint = "http://127.0.0.1:${server.port}"
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path?.substringBefore('?').orEmpty()
                        requests.add(path)
                        if (path == "/cube.stl") {
                            if (holdDownload.get()) {
                                downloadStarted.countDown()
                                if (!releaseDownload.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                            }
                            return MockResponse().setBody(Buffer().write(cube))
                        }
                        if (path == "/archive.zip") return MockResponse().setBody(Buffer().write(zip))
                        if (path != "/graphql") return MockResponse().setResponseCode(404)
                        val payload = JSONObject(request.body.readUtf8())
                        val query = payload.getString("query")
                        if (query.contains("SearchPrints") && denySearch.get()) {
                            return MockResponse().setResponseCode(403).setBody("Fixture search is unavailable")
                        }
                        val data = when {
                            query.contains("SearchPrints") -> """{"searchPrints2":{"items":[$MODEL]}}"""
                            query.contains("PrintFiles") -> """{"print":${MODEL.dropLast(1)},"stls":[{"id":"file-1","name":"catalog-cube.stl","fileSize":${cube.size}}],"gcodes":[],"otherFiles":[]}}"""
                            query.contains("DownloadLink") -> """{"getDownloadLink":{"ok":true,"errors":[],"output":{"link":"https://catalog-fixture.invalid/cube.stl","ttl":60}}}"""
                            else -> return MockResponse().setResponseCode(400)
                        }
                        return MockResponse().addHeader("Content-Type", "application/json")
                            .setBody("""{"data":$data}""")
                    }
                }
                // Keep the production HTTPS-link check; only this test client's fixture host maps to loopback.
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    val target = if (request.url.host == "catalog-fixture.invalid") {
                        request.newBuilder().url(endpoint + request.url.encodedPath).build()
                    } else request
                    chain.proceed(target)
                }.build()
                val catalog = FullPresetCatalogViewModel(
                    context.applicationContext as Application, httpClient = http,
                    printablesClient = PrintablesClient(http, "$endpoint/graphql"), filesDir = libraryRoot,
                )
                owners.put("catalog", catalog)
                ActivityScenario.launch(FullPresetActivity::class.java).use { scenario ->
                    try {
                        lateinit var model: FullPresetViewModel
                        scenario.onActivity { activity ->
                            model = ViewModelProvider(activity)[FullPresetViewModel::class.java]
                            activity.setContent { FullPresetTheme { FullPresetScreen(onBack = {}, catalog = catalog) } }
                        }
                        fun idle() = compose.waitUntil(180_000) { model.state.value.operation == null }
                        fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        idle()
                        val presets = File(documents, "full-presets-diagnostic.zip")
                        instrumentation.context.assets.open(presets.name).use { source -> presets.outputStream().use(source::copyTo) }
                        scenario.onActivity { model.importBundle(uri(presets)) }
                        idle()
                        assertNull(model.state.value.error)

                        compose.onNodeWithTag("full-presets-tab-browse").performClick()
                        compose.onNodeWithText("Search Printables").performTextInput("fixture cube")
                        closeSoftKeyboard()
                        compose.onNodeWithText("Search", substring = false).performClick()
                        waitForText("Search on website")
                        compose.onNodeWithText("Search on website").assertIsDisplayed().assertIsEnabled()
                        denySearch.set(false)
                        compose.onNodeWithText("Search", substring = false).performClick()
                        waitForText("Catalog fixture cube")
                        saveFullPresetScreenshot("browse")
                        compose.onNodeWithText("Catalog fixture cube").performClick()
                        waitForText("catalog-cube.stl")
                        compose.onNodeWithText("Download and open").performScrollTo().assertIsEnabled().performClick()
                        compose.waitUntil(60_000) {
                            model.state.value.operation == null && model.state.value.model?.name == "catalog-cube.stl"
                        }
                        assertNull(model.state.value.error)
                        assertEquals(SOURCE_URL, model.state.value.model?.sourceUrl)
                        compose.onNodeWithTag("full-presets-model-source").assertIsDisplayed()
                        assertPreviewRendered()
                        saveFullPresetScreenshot("download-preview")
                        val entry = runBlocking { catalog.store.entries().single() }
                        val original = catalog.store.fileFor(entry)
                        assertArrayEquals(cube, original.readBytes())
                        assertNotEquals(original.canonicalFile, model.state.value.model?.file?.canonicalFile)
                        assertEquals(sha256(cube), entry.sha256)

                        compose.onNodeWithTag("full-presets-slice").assertIsEnabled().performClick()
                        idle()
                        assertNull(model.state.value.error)
                        val output = requireNotNull(model.state.value.output)
                        assertEquals(50, output.result.totalLayers)
                        compose.onNodeWithTag("full-presets-result").performScrollTo().assertIsDisplayed()
                        saveFullPresetScreenshot("download-result")
                        val exported = File(documents, "downloaded-cube.gcode")
                        scenario.onActivity { model.exportGcode(uri(exported)) }
                        idle()
                        assertNull(model.state.value.error)
                        assertArrayEquals(File(output.result.gcodePath).readBytes(), exported.readBytes())

                        compose.onNodeWithTag("full-presets-tab-library").performClick()
                        waitForText("Catalog fixture cube")
                        saveFullPresetScreenshot("library")
                        compose.onNodeWithText("Open", substring = false).performClick()
                        compose.waitUntil(60_000) {
                            model.state.value.operation == null &&
                                compose.onAllNodesWithText("Search library").fetchSemanticsNodes().isEmpty()
                        }
                        compose.onNodeWithTag("full-presets-model-source").assertIsDisplayed()
                        assertArrayEquals("Opening a saved model must preserve the library file", cube, original.readBytes())

                        runBlocking {
                            catalog.store.download("$endpoint/archive.zip", "fixture-parts.zip",
                                DownloadProvenance("fixture", SOURCE_URL, "Archive fixture"))
                        }
                        compose.onNodeWithTag("full-presets-tab-library").performClick()
                        waitForText("Archive fixture")
                        compose.onNodeWithText("Search library").performTextInput("fixture-parts.zip")
                        closeSoftKeyboard()
                        compose.onNodeWithText("Open", substring = false).performScrollTo().performClick()
                        waitForText("models/catalog-cube.stl")
                        saveFullPresetScreenshot("archive")
                        compose.onNodeWithText("Open", substring = false).performClick()
                        compose.waitUntil(60_000) {
                            model.state.value.operation == null &&
                                compose.onAllNodesWithText("models/catalog-cube.stl").fetchSemanticsNodes().isEmpty()
                        }
                        assertNull(model.state.value.error)
                        assertEquals(SOURCE_URL, model.state.value.model?.sourceUrl)
                        assertPreviewRendered()
                        assertArrayEquals(cube, original.readBytes())
                        val reopened = DownloadStore(libraryRoot, http)
                        assertEquals(2, runBlocking { reopened.entries().size })
                        assertTrue(requests.containsAll(listOf("/graphql", "/cube.stl", "/archive.zip")))

                        val retainedModel = model.state.value.model
                        val retainedPreview = model.state.value.preview
                        val requestModel = PrintablesPrint("9001", "Catalog fixture cube", "fixture-cube", null, "Fixture author")
                        val requestFile = PrintablesFile("file-1", "catalog-cube.stl", cube.size.toLong(), PrintablesFileType.STL)
                        holdDownload.set(true)
                        scenario.onActivity { catalog.downloadPrintables(requestModel, requestFile, origin = -1L) }
                        compose.waitUntil(20_000) { downloadStarted.count == 0L }
                        compose.onNodeWithTag("full-presets-catalog-cancel").assertIsEnabled().performClick()
                        compose.waitUntil(20_000) { catalog.state.value.transfer == FullPresetCatalogTransfer.Idle }
                        releaseDownload.countDown()
                        assertSame(retainedModel, model.state.value.model)
                        assertSame(retainedPreview, model.state.value.preview)
                        assertEquals(2, runBlocking { reopened.entries().size })
                        assertFalse(File(libraryRoot, "downloads").listFiles().orEmpty().any { it.name.startsWith(".partial-") })

                        // A transfer from an older navigation context remains available without replacing Prepare.
                        holdDownload.set(false)
                        scenario.onActivity { catalog.downloadPrintables(requestModel, requestFile, origin = -1L) }
                        compose.waitUntil(30_000) { catalog.state.value.transfer is FullPresetCatalogTransfer.Ready }
                        assertSame(retainedModel, model.state.value.model)
                        compose.onNodeWithTag("full-presets-catalog-open").assertIsEnabled().performClick()
                        compose.waitUntil(60_000) {
                            model.state.value.operation == null && model.state.value.model !== retainedModel
                        }
                        assertNull(model.state.value.error)
                        assertArrayEquals(cube, original.readBytes())
                    } catch (failure: Throwable) {
                        runCatching { saveFullPresetScreenshot("catalog-failure") }
                            .exceptionOrNull()?.let(failure::addSuppressed)
                        throw failure
                    } finally {
                        releaseDownload.countDown()
                    }
                }
            }
        } finally {
            owners.clear()
            bundleStore.list().filter { it.id !in originalBundles }.forEach { bundleStore.delete(it.id) }
            documents.deleteRecursively()
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertPreviewRendered() {
        compose.waitUntil(20_000) {
            val bounds = compose.onNodeWithTag(PREVIEW_MODEL_SURFACE).assertIsDisplayed()
                .fetchSemanticsNode().boundsInWindow
            val frame = takeFullPresetScreenshot()
            try {
                val left = floor(bounds.left).toInt().coerceIn(0, frame.width - 1)
                val top = floor(bounds.top).toInt().coerceIn(0, frame.height - 1)
                val right = ceil(bounds.right).toInt().coerceIn(left + 1, frame.width)
                val bottom = ceil(bounds.bottom).toInt().coerceIn(top + 1, frame.height)
                val pixels = IntArray((right - left) * (bottom - top))
                frame.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
                pixels.count { Color.green(it) > 70 && Color.green(it) > Color.red(it) * 1.4f &&
                    Color.blue(it) > Color.red(it) * 1.4f } > pixels.size / 50
            } finally { frame.recycle() }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private companion object {
        const val SOURCE_URL = "https://www.printables.com/model/9001-fixture-cube"
        const val MODEL = """{"id":"9001","name":"Catalog fixture cube","slug":"fixture-cube","user":{"publicUsername":"Fixture author"}}"""
    }
}
