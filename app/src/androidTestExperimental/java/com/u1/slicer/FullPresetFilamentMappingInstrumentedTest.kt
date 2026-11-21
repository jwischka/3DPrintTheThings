package com.u1.slicer

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.network.U1FilamentSnapshot
import com.u1.slicer.presets.FullPresetStore
import com.u1.slicer.presets.FullPresetViewModel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
        val requests = AtomicInteger()
        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                assertTrue(prefs.edit().putString("endpoint", "http://127.0.0.1:${server.port}").commit())
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        if (request.method != "GET" ||
                            request.path != "/printer/objects/query?${U1FilamentSnapshot.OBJECT_QUERY}"
                        ) return MockResponse().setResponseCode(404)
                        val received: CountDownLatch
                        val release: CountDownLatch
                        when (requests.incrementAndGet()) {
                            1 -> { received = initialRequest; release = initialReply }
                            2 -> { received = refreshRequest; release = refreshReply }
                            else -> return MockResponse().setResponseCode(429)
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

                            control("full-presets-map-source-1").assertIsEnabled().performClick()
                            compose.onNodeWithTag("full-presets-map-source-1-option-1").assertDoesNotExist()
                            // Deliberately send the original red and black to the opposite reported colors.
                            compose.onNodeWithTag("full-presets-map-source-1-option-2").assertIsDisplayed().performClick()
                            control("full-presets-map-source-2").assertIsEnabled().performClick()
                            compose.onNodeWithTag("full-presets-map-source-2-option-3").assertIsDisplayed().performClick()
                            for (slot in 1..4) {
                                val picker = "full-presets-map-preset-$slot"
                                control(picker).assertIsEnabled().performClick()
                                compose.onNodeWithTag("$picker-option-$preset").performScrollTo().performClick()
                            }
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
                            assertEquals(List(4) { preset }, mapping.physicalPresets)
                            assertEquals(2, requests.get())
                        } catch (failure: Throwable) {
                            runCatching { saveFullPresetScreenshot("filament-mapping-failed-screen") }
                            throw failure
                        }
                    }
                } finally {
                    initialReply.countDown()
                    refreshReply.countDown()
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
