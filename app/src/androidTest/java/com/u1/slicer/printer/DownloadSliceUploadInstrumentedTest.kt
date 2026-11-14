package com.u1.slicer.printer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.library.DownloadEntry
import com.u1.slicer.library.DownloadProvenance
import com.u1.slicer.library.DownloadStore
import com.u1.slicer.library.LibraryScreen
import com.u1.slicer.network.MoonrakerClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Component pipeline with a real Library Open interaction and real JNI slicing; no printer hardware. */
@RunWith(AndroidJUnit4::class)
class DownloadSliceUploadInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val base = "http://10.0.2.2:18725"
    private val fixture = "$base/__sim/fixture/cube_10mm.stl"
    private val fixtureHash = "3bc24cffc43194b17e30507fc6fb06fb6c39bf239087f5c960c1dc9d9dd96e1a"
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val provenance = DownloadProvenance("simulator", "$base/__sim/info", "Cube 10 mm")
    private val fixtureHeaders = mapOf("X-Simulator-Control" to "simulator-control")

    private fun control(path: String, reset: Boolean = false): JSONObject {
        val request = Request.Builder().url("$base/__sim/$path")
            .header("X-Simulator-Control", "simulator-control")
            .apply { if (reset) post("{}".toRequestBody()) }.build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Simulator control failed: HTTP ${response.code}" }
            JSONObject(response.body!!.string())
        }
    }

    @Before fun verifySimulatorBeforeMutation() {
        // This exact emulator-host marker is checked before reset, download or upload.
        assertEquals("3DPrintTheThings U1 simulator", control("info").getString("simulator"))
        control("reset", reset = true)
    }

    @Test fun downloadedLibrarySelectionSlicesAndUploadsExactGcode() = runBlocking {
        assertTrue("Real native engine is required", NativeLibrary.isLoaded)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(context.filesDir, "download-slice-upload-${System.nanoTime()}")
        assertTrue(files.mkdirs())
        val native = NativeLibrary()
        try {
            val store = DownloadStore(files, http)
            val downloaded = store.download(fixture, "cube_10mm.stl", provenance, fixtureHeaders)
            assertEquals(fixtureHash, downloaded.sha256)
            val reopened = DownloadStore(files, http)
            assertEquals(listOf(downloaded), reopened.entries())

            var selected: DownloadEntry? = null
            compose.setContent { LibraryScreen(reopened, onOpen = { selected = it }, onBack = {}) }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Cube 10 mm").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Open").performClick()
            compose.runOnIdle { assertEquals(downloaded, selected) }
            val model = reopened.fileFor(requireNotNull(selected))
            assertTrue(model.isFile && model.length() == downloaded.sizeBytes)
            assertEquals(fixtureHash, sha256(model))

            native.clearModel()
            assertTrue("Stored model could not load", native.loadModel(model.absolutePath))
            val printer = context.assets.open("orca_profiles/printer/snapmaker_u1.json")
                .bufferedReader().use { JSONObject(it.readText()) }
            val config = SliceConfig(
                machineStartGcode = printer.getString("machine_start_gcode"),
                machineEndGcode = printer.getString("machine_end_gcode"),
            )
            val result = native.slice(config) ?: throw AssertionError("Native slice returned no result")
            assertTrue("Native slice failed: ${result.errorMessage}", result.success)
            val gcode = File(result.gcodePath)
            assertTrue("Native slice returned no G-code", gcode.isFile && gcode.length() > 0)
            val summary = GcodeValidator.summarizeParsedOutput(GcodeParser.parse(gcode))
            assertFalse("Preview contains no model extrusion", GcodeValidator.isEffectivelyEmpty(summary))
            val markers = gcode.useLines { lines -> lines.count { it.trim() == ";LAYER_CHANGE" } }
            assertEquals("Preview layers must match slicer markers", markers, summary.layerCount)
            assertEquals(49, markers)

            val client = MoonrakerClient(http).apply {
                baseUrl = base
                configureApiKey("simulator-test-key")
            }
            val transport = MoonrakerTransport(client)
            assertEquals(TransportCommandResult.Success, transport.validateStartJob())
            var sent = 0L
            assertEquals(TransportCommandResult.Success,
                transport.uploadJob(gcode, "cube_10mm.gcode") { bytes, _ -> sent = bytes })
            assertEquals(gcode.length(), sent)
            val state = control("state")
            val uploaded = state.getJSONObject("files").getJSONObject("cube_10mm.gcode")
            assertEquals(gcode.length(), uploaded.getLong("size"))
            assertEquals(sha256(gcode), uploaded.getString("sha256"))
            val events = state.getJSONArray("events")
            val kinds = (0 until events.length()).map { events.getJSONObject(it).getString("kind") }
            assertEquals(1, kinds.count { it == "upload_request" })
            assertEquals(1, kinds.count { it == "upload_accepted" })
            assertEquals(0, kinds.count { it == "start" })
            assertEquals("standby", state.getString("state"))
        } finally {
            native.clearModel()
            files.deleteRecursively()
        }
    }

    @Test fun failedFixtureDownloadLeavesNoEntryOrUpload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(context.filesDir, "failed-model-download-${System.nanoTime()}")
        assertTrue(files.mkdirs())
        try {
            val store = DownloadStore(files, http)
            try {
                store.download("$base/__sim/fixture/missing.stl", "missing.stl", provenance, fixtureHeaders)
                fail("Missing fixture must fail")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("404"))
            }
            assertTrue(store.entries().isEmpty())
            val root = File(files, "downloads")
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".partial-") })
            assertEquals(0, control("state").getJSONArray("events").length())
        } finally { files.deleteRecursively() }
    }

    @Test fun truncatedFixtureCleansPartialAndNormalRetryPersists() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(context.filesDir, "truncated-model-download-${System.nanoTime()}")
        assertTrue(files.mkdirs())
        try {
            val store = DownloadStore(files, http)
            try {
                store.download("$base/__sim/fixture/truncated-cube_10mm.stl",
                    "cube_10mm.stl", provenance, fixtureHeaders)
                fail("Truncated fixture must not commit")
            } catch (_: IOException) {
                // The advertised length exceeds the bytes actually sent.
            }
            assertTrue(store.entries().isEmpty())
            assertTrue(File(files, "downloads").listFiles().orEmpty().none {
                it.name.startsWith(".partial-") || it.name == fixtureHash
            })
            assertEquals(0, control("state").getJSONArray("events").length())

            val downloaded = store.download(fixture, "cube_10mm.stl", provenance, fixtureHeaders)
            assertEquals(fixtureHash, downloaded.sha256)
            val reopened = DownloadStore(files, http)
            assertEquals(listOf(downloaded), reopened.entries())
            assertEquals(fixtureHash, sha256(reopened.fileFor(downloaded)))
        } finally { files.deleteRecursively() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
