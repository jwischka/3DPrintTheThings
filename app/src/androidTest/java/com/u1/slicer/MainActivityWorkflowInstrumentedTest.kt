package com.u1.slicer

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrintersConfig
import com.u1.slicer.data.PrintersRepository
import com.u1.slicer.debug.TestCommandReceiver
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.GcodeValidator
import com.u1.slicer.library.DownloadProvenance
import com.u1.slicer.printer.PrinterViewModel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Actual MainActivity navigation and upload-only UI on a disposable simulator emulator. */
@RunWith(AndroidJUnit4::class)
class MainActivityWorkflowInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val base = "http://10.0.2.2:18725"
    private val printerId = "simulator-ui-workflow"
    private val fixtureHash = "3bc24cffc43194b17e30507fc6fb06fb6c39bf239087f5c960c1dc9d9dd96e1a"
    private val http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

    private fun control(path: String, reset: Boolean = false): JSONObject {
        val request = Request.Builder().url("$base/__sim/$path")
            .header("X-Simulator-Control", "simulator-control")
            .apply { if (reset) post("{}".toRequestBody()) }.build()
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Simulator control failed: HTTP ${response.code}" }
            JSONObject(response.body!!.string())
        }
    }

    private fun assertSimulatorMarker() {
        assertEquals("3DPrintTheThings U1 simulator", control("info").getString("simulator"))
    }

    @Test fun libraryOpenPrepareSlicePreviewAndUploadOnly() {
        assertSimulatorMarker() // No configuration, download or upload before this check.
        assertTrue("Real native engine is required", NativeLibrary.isLoaded)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = (context.applicationContext as U1SlicerApplication).container
        val repository = container.printersRepository
        val configured = Printer(printerId, "Simulator", moonrakerUrl = base)
        val onlySimulator = PrintersConfig(listOf(configured), printerId)
        // AppContainer starts migration asynchronously. An observed non-null config
        // proves its one-time write has finished before this test replaces it.
        val previous = runBlocking {
            withTimeout(15_000) { repository.config.filterNotNull().first() }
        }
        assertTrue("Disposable emulator must not contain a real printer",
            previous.printers.all { it.moonrakerUrl.isBlank() || it.moonrakerUrl == base })
        control("reset", reset = true)

        var scenario: ActivityScenario<MainActivity>? = null
        var entryHash: String? = null
        try {
            runBlocking {
                repository.replace(onlySimulator)
                assertEquals(onlySimulator, repository.config.first())
            }
            container.moonrakerApiKeys.put(printerId, base, "simulator-test-key")
            val entry = runBlocking {
                container.downloadStore.download(
                    "$base/__sim/fixture/cube_10mm.stl", "cube_10mm.stl",
                    DownloadProvenance("simulator", "$base/__sim/info", "UI workflow cube"),
                    mapOf("X-Simulator-Control" to "simulator-control"),
                )
            }
            entryHash = entry.sha256
            assertEquals(fixtureHash, entry.sha256)
            assertEquals(entry, runBlocking { container.downloadStore.entry(entry.sha256) })

            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            var slicer: SlicerViewModel? = null
            var printer: PrinterViewModel? = null
            scenario.onActivity { activity ->
                slicer = ViewModelProvider(activity)[SlicerViewModel::class.java]
                printer = ViewModelProvider(activity)[PrinterViewModel::class.java]
            }
            val slicerVm = requireNotNull(slicer)
            val printerVm = requireNotNull(printer)
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Load a 3D Model").fetchSemanticsNodes().isNotEmpty()
            }
            context.sendBroadcast(Intent(TestCommandReceiver.ACTION_NAVIGATE)
                .setPackage(context.packageName).putExtra("screen", "downloads"))
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("UI workflow cube").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Open").performClick()
            compose.waitUntil(60_000) { slicerVm.state.value is SlicerViewModel.SlicerState.ModelLoaded }
            assertEquals("cube_10mm.stl", slicerVm.modelFileName.value)
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Slice Model").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Slice Model").performClick()
            compose.waitUntil(180_000) { slicerVm.state.value is SlicerViewModel.SlicerState.SliceComplete }
            val sliced = slicerVm.state.value as SlicerViewModel.SlicerState.SliceComplete
            val source = File(sliced.result.gcodePath)
            assertTrue("UI slice produced no G-code", source.isFile && source.length() > 0)
            val summary = GcodeValidator.summarizeParsedOutput(GcodeParser.parse(source))
            assertFalse("UI preview has no model extrusion", GcodeValidator.isEffectivelyEmpty(summary))
            val markers = source.useLines { lines -> lines.count { it.trim() == ";LAYER_CHANGE" } }
            assertTrue("UI slice has no layer markers", markers > 0)
            assertEquals(markers, summary.layerCount)

            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Upload Only").fetchSemanticsNodes().isNotEmpty() &&
                    printerVm.status.value.isConnected
            }
            assertOnlySimulator(currentConfig(repository), printerVm)
            compose.onNodeWithText("Upload Only").assertIsEnabled().performClick()
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText("Upload to printer").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(0, control("state").getJSONArray("events").length())
            assertOnlySimulator(currentConfig(repository), printerVm)
            compose.onNodeWithText("Upload").performClick()
            compose.waitUntil(60_000) { printerVm.sendingState.value == PrinterViewModel.SendingState.UploadComplete }

            val remapped = File(source.parentFile, "${source.nameWithoutExtension}.remapped.${source.extension}")
            assertTrue("UI did not stage remapped G-code", remapped.isFile && remapped.length() > 0)
            val state = control("state")
            val files = state.getJSONObject("files")
            assertEquals("Only one G-code should be uploaded", 1, files.length())
            val uploaded = files.getJSONObject(files.keys().next())
            assertEquals(remapped.length(), uploaded.getLong("size"))
            assertEquals(sha256(remapped), uploaded.getString("sha256"))
            val events = state.getJSONArray("events")
            val kinds = (0 until events.length()).map { events.getJSONObject(it).getString("kind") }
            assertEquals(1, kinds.count { it == "upload_request" })
            assertEquals(1, kinds.count { it == "upload_accepted" })
            assertEquals(0, kinds.count { it == "start" })
            assertEquals("standby", state.getString("state"))
        } finally {
            scenario?.close()
            runBlocking { repository.replace(previous) }
            container.moonrakerApiKeys.remove(printerId)
            entryHash?.let { hash ->
                runBlocking { container.downloadStore.entry(hash)?.let { container.downloadStore.delete(it) } }
            }
        }
    }

    private fun assertOnlySimulator(config: PrintersConfig?, printerVm: PrinterViewModel) {
        assertSimulatorMarker()
        assertEquals(1, config?.printers?.size)
        assertEquals(printerId, config?.activeId)
        assertEquals(base, config?.active?.moonrakerUrl)
        assertEquals(printerId, printerVm.activePrinter.value?.id)
        assertEquals(base, printerVm.activePrinter.value?.moonrakerUrl)
    }

    private fun currentConfig(repository: PrintersRepository): PrintersConfig? =
        runBlocking { withTimeout(5_000) { repository.config.first() } }

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
