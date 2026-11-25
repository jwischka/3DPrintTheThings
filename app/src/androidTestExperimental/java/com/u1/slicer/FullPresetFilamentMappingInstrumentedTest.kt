package com.u1.slicer

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.network.U1FilamentSnapshot
import com.u1.slicer.presets.FullPresetPrintPhase
import com.u1.slicer.presets.FullPresetStore
import com.u1.slicer.presets.FullPresetViewModel
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FullPresetFilamentMappingInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun opensMappingWhileLoadingAndKeepsDraftAcrossExpiredReportRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("experimental-printer", Context.MODE_PRIVATE)
        val previousEndpoint = prefs.getString("endpoint", null)
        val store = FullPresetStore(File(context.noBackupFilesDir, "full-presets"))
        val originalIds = store.list().map { it.id }.toSet()
        val documents = File(context.cacheDir, "preset-mapping-${UUID.randomUUID()}")
        assertTrue(documents.mkdirs())
        fun asset(name: String): Uri {
            val file = File(documents, name)
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
            return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val initialRequest = CountDownLatch(1)
        val initialReply = CountDownLatch(1)
        val refreshRequest = CountDownLatch(1)
        val refreshReply = CountDownLatch(1)
        val uploadRequest = CountDownLatch(1)
        val uploadReply = CountDownLatch(1)
        val requests = AtomicInteger()
        val uploads = AtomicInteger()
        val starts = AtomicInteger()
        val gcodeBytes = AtomicLong()
        val remoteName = AtomicReference<String>()
        val startCall = AtomicReference<JSONObject>()
        val printerBusy = AtomicBoolean(true)
        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                assertTrue(prefs.edit().putString("endpoint", "http://127.0.0.1:${server.port}").commit())
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        when (request.method to request.path) {
                            "GET" to "/server/info" -> return MockResponse().setBody(SERVER_READY)
                            "GET" to "/printer/objects/query?webhooks&print_stats&machine_state_manager=main_state" ->
                                return MockResponse().setBody(if (printerBusy.get()) PRINTER_BUSY else PRINTER_READY)
                            "GET" to "/websocket" -> return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onMessage(webSocket: WebSocket, text: String) {
                                    val call = JSONObject(text)
                                    startCall.set(call)
                                    starts.incrementAndGet()
                                    val result = JSONObject().put("state", "success").put("filename", remoteName.get())
                                    webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", call.get("id")).put("result", result).toString())
                                }

                                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                    webSocket.close(code, reason)
                                }
                            })
                            "POST" to "/server/files/upload" -> {
                                uploads.incrementAndGet()
                                val body = request.body.readUtf8()
                                val filename = Regex("filename=\"([A-Za-z0-9_-]+\\.gcode)\"").find(body)?.groupValues?.get(1)
                                if (filename == null || !body.contains("name=\"print\"") || !body.contains("\r\nfalse\r\n")) {
                                    return MockResponse().setResponseCode(400)
                                }
                                remoteName.set(filename)
                                uploadRequest.countDown()
                                if (!uploadReply.await(60, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
                                return MockResponse().setBody(
                                    """{"result":{"item":{"root":"gcodes","path":"$filename","size":${gcodeBytes.get()}},"print_started":false,"print_queued":false}}""",
                                )
                            }
                            "GET" to "/printer/objects/query?${U1FilamentSnapshot.OBJECT_QUERY}" -> Unit
                            else -> return MockResponse().setResponseCode(404)
                        }
                        val received: CountDownLatch
                        val release: CountDownLatch
                        when (requests.incrementAndGet()) {
                            1 -> { received = initialRequest; release = initialReply }
                            2 -> { received = refreshRequest; release = refreshReply }
                            else -> return MockResponse().setBody(SLOTS)
                        }
                        received.countDown()
                        return if (release.await(60, TimeUnit.SECONDS)) MockResponse().setBody(SLOTS)
                        else MockResponse().setResponseCode(504)
                    }
                }
                try {
                    ActivityScenario.launch(FullPresetActivity::class.java).use { scenario ->
                        lateinit var model: FullPresetViewModel
                        scenario.onActivity { model = ViewModelProvider(it)[FullPresetViewModel::class.java] }
                        fun idle() = compose.waitUntil(180_000) { model.state.value.operation == null }
                        try {
                            idle()
                            val bundle = asset("full-presets-diagnostic.zip")
                            scenario.onActivity { model.importBundle(bundle) }
                            idle()
                            assertNull(model.state.value.error)
                            val cube = asset("calib-cube-10-dual-colour-merged.3mf")
                            scenario.onActivity { model.importModel(cube) }
                            idle()
                            assertNull(model.state.value.error)
                            val source = requireNotNull(model.state.value.model?.filaments)
                            assertEquals(setOf(1, 2), source.usedSlots)
                            assertEquals("#9D2235", source.color(1)?.uppercase())
                            assertEquals("#000000", source.color(2)?.uppercase())
                            val preset = requireNotNull(model.state.value.selection).filaments.first()

                            control("full-presets-settings").assertIsEnabled().performClick()
                            control("full-presets-printer-filaments").assertIsEnabled().performClick()
                            assertTrue("Initial printer report was not requested", initialRequest.await(10, TimeUnit.SECONDS))
                            waitForText(R.string.full_presets_mapping_loading)
                            control(REFRESH).assertIsNotEnabled()
                            compose.onNodeWithTag(APPLY).assertDoesNotExist()
                            initialReply.countDown()
                            waitForText(R.string.full_presets_mapping_fresh)

                            control("full-presets-map-source-1-option-2").assertIsEnabled()
                            compose.onNodeWithTag("full-presets-map-source-1-option-1").assertDoesNotExist()
                            // Deliberately send the original red and black to the opposite reported colors.
                            control("full-presets-map-source-1-option-2").performClick()
                            control("full-presets-map-source-2-option-3").assertIsEnabled().performClick()
                            control("full-presets-map-source-1")
                            saveFullPresetScreenshot("filament-mapping")
                            compose.onNodeWithTag("full-presets-filament-1").assertDoesNotExist()
                            compose.onNodeWithTag("full-presets-map-preset-1").assertDoesNotExist()
                            control("full-presets-physical-presets").performClick()
                            for (slot in 1..4) {
                                control("full-presets-map-preset-$slot").assertIsEnabled()
                            }
                            control("full-presets-physical-presets").performClick()
                            waitForText(R.string.full_presets_mapping_stale)
                            control(APPLY).assertIsNotEnabled()
                            control(REFRESH).assertIsEnabled().performClick()
                            assertTrue("Refresh was not requested", refreshRequest.await(10, TimeUnit.SECONDS))
                            control(REFRESH).assertIsNotEnabled()
                            control(APPLY).assertIsNotEnabled()
                            compose.onNodeWithTag("full-presets-map-source-1").assertExists()
                            refreshReply.countDown()

                            // The button can enable only if the draft still contains the target and all four presets.
                            waitForText(R.string.full_presets_mapping_fresh)
                            control(APPLY).assertIsEnabled().performClick()
                            compose.waitUntil(10_000) { model.state.value.filamentMapping != null }
                            val mapping = requireNotNull(model.state.value.filamentMapping)
                            assertEquals(mapOf(1 to 2, 2 to 3), mapping.targets)
                            assertEquals(listOf(2, 3), mapping.positionalMapping)
                            val selected = requireNotNull(model.state.value.selection).filaments
                            assertEquals(List(4) { index -> selected.getOrNull(index) ?: preset }, mapping.physicalPresets)
                            assertEquals(2, requests.get())

                            compose.onNodeWithTag("full-presets-settings-done").performClick()
                            compose.onNodeWithTag("full-presets-slice").assertIsEnabled().performClick()
                            idle()
                            assertNull(model.state.value.error)
                            val output = requireNotNull(model.state.value.output)
                            gcodeBytes.set(File(output.result.gcodePath).length())
                            compose.onNodeWithTag("full-presets-export").assertIsEnabled()
                            compose.onNodeWithTag("full-presets-review-print").performClick()
                            waitForText(R.string.full_presets_print_printer_not_ready)
                            waitForText(R.string.full_presets_print_review_retained_filaments)
                            assertEquals(FullPresetPrintPhase.FAILED, model.state.value.printReview?.phase)
                            assertNull(model.state.value.printReview?.report)
                            compose.onNodeWithTag("full-presets-confirm-print").assertDoesNotExist()
                            for (slot in listOf(2, 3)) {
                                compose.onNodeWithText(context.getString(
                                    R.string.full_presets_mapping_slot_report, slot,
                                    context.getString(R.string.full_presets_mapping_loaded),
                                    "Snapmaker", "PLA", "Matte",
                                )).performScrollTo().assertIsDisplayed()
                            }
                            scenario.onActivity { model.confirmPrint() }
                            assertEquals(0, uploads.get())
                            assertEquals(0, starts.get())
                            printerBusy.set(false)
                            control("full-presets-print-review-refresh").performClick()
                            waitForText(R.string.full_presets_print_review_ready)
                            compose.onNodeWithText(context.getString(R.string.full_presets_print_review_retained_filaments))
                                .assertDoesNotExist()
                            val review = requireNotNull(model.state.value.printReview)
                            assertEquals(FullPresetPrintPhase.READY, review.phase)
                            assertEquals(setOf(2, 3), review.usage?.requiredSlots)
                            control("full-presets-print-tool-2").assertIsDisplayed()
                            control("full-presets-print-tool-3").assertIsDisplayed()
                            control("full-presets-print-tool-2")
                            saveFullPresetScreenshot("print-review")
                            compose.onNodeWithTag("full-presets-print-tool-1").assertDoesNotExist()
                            assertEquals(0, uploads.get())
                            assertEquals(0, starts.get())

                            compose.onNodeWithTag("full-presets-confirm-print").assertIsEnabled().performClick()
                            assertTrue("Expected an upload only after confirmation", uploadRequest.await(20, TimeUnit.SECONDS))
                            compose.onNodeWithTag("full-presets-close-print-review").assertIsNotEnabled()
                            scenario.onActivity { model.confirmPrint() }
                            assertEquals(1, uploads.get())
                            assertEquals(0, starts.get())
                            uploadReply.countDown()
                            waitForText(R.string.full_presets_print_review_started)
                            idle()
                            val call = requireNotNull(startCall.get())
                            assertEquals("server.files.start_local_print", call.getString("method"))
                            assertEquals(remoteName.get(), call.getJSONObject("params").getString("path"))
                            assertEquals(1, starts.get())
                            compose.onNodeWithTag("full-presets-close-print-review").performClick()
                            compose.onNodeWithTag("full-presets-review-print").performClick()
                            waitForText(R.string.full_presets_print_review_started)
                            compose.onNodeWithTag("full-presets-confirm-print").assertDoesNotExist()
                            assertEquals(1, uploads.get())
                            assertEquals(1, starts.get())
                        } catch (failure: Throwable) {
                            runCatching { saveFullPresetScreenshot("filament-mapping-failed-screen") }
                            throw failure
                        }
                    }
                } finally {
                    initialReply.countDown()
                    refreshReply.countDown()
                    uploadReply.countDown()
                }
            }
        } finally {
            assertTrue(prefs.edit().putString("endpoint", previousEndpoint).commit())
            store.list().filter { it.id !in originalIds }.forEach { store.delete(it.id) }
            documents.deleteRecursively()
        }
    }

    private fun control(tag: String) =
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()

    private fun waitForText(resource: Int) {
        val text = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val REFRESH = "full-presets-refresh-printer-filaments"
        const val APPLY = "full-presets-apply-printer-filaments"
        const val SERVER_READY = """{"result":{"klippy_connected":true,"klippy_state":"ready"}}"""
        const val PRINTER_READY = """{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"standby"},"machine_state_manager":{"main_state":0}}}}"""
        const val PRINTER_BUSY = """{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"printing"},"machine_state_manager":{"main_state":1}}}}"""
        const val SLOTS = """
            {"result":{"status":{"print_task_config":{
                "filament_exist":[false,true,true,false],
                "filament_vendor":[null,"Snapmaker","Snapmaker",null],
                "filament_type":[null,"PLA","PLA",null],
                "filament_sub_type":[null,"Matte","Matte",null],
                "filament_color_rgba":[null,"000000FF","9D2235FF",null]
            }}}}
        """
    }
}
