package com.u1.slicer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.MoonrakerApiKeyStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class FullPresetPrinterInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun showsPrinterProgressCameraAndConnectionFailures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("experimental-printer", android.content.Context.MODE_PRIVATE)
        val previousEndpoint = prefs.getString("endpoint", null)
        val credentials = MoonrakerApiKeyStore(context.noBackupFilesDir)
        val previousKey = previousEndpoint?.let { credentials.get("experimental-printer-dashboard", it) }
        assertTrue(prefs.edit().remove("endpoint").commit())
        val mode = AtomicReference(Mode.PRINTING)
        val jpeg = makeSnapshot()
        val requests = java.util.concurrent.ConcurrentLinkedQueue<RecordedRequest>()

        fun capture(name: String) {
            compose.waitForIdle()
            val directory = File(context.filesDir, "preset-workflow-capture").apply { mkdirs() }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally {
                bitmap.recycle()
            }
        }

        fun waitFor(text: String) {
            compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        }

        try {
            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        requests.add(request)
                        if (request.method != "GET") return MockResponse().setResponseCode(405)
                        if (mode.get() == Mode.OFFLINE) {
                            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        }
                        return when (request.path?.substringBefore('?')) {
                            "/printer/objects/query" -> if (mode.get() == Mode.AUTHORIZATION_REQUIRED) {
                                MockResponse().setResponseCode(401)
                            } else {
                                MockResponse().setBody(PRINTING)
                            }
                            "/server/webcams/list" -> MockResponse().setBody(
                                """{"result":{"webcams":[{"enabled":true,"snapshot_url":"${server.url("/snapshot.jpg")}"}]}}""",
                            )
                            "/snapshot.jpg" -> MockResponse().addHeader("Content-Type", "image/jpeg")
                                .setBody(Buffer().write(jpeg))
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }

                ActivityScenario.launch(FullPresetActivity::class.java).use {
                    compose.onNodeWithTag("full-presets-tab-printer").performClick()
                    waitFor("Monitor a printer")
                    compose.onNodeWithTag("experimental-printer-connect").performClick()
                    compose.onNodeWithTag("experimental-printer-endpoint").performTextInput(server.url("/").toString())
                    compose.onNodeWithTag("experimental-printer-save").performClick()
                    waitFor("Printing")
                    compose.onNodeWithText("Example bracket.gcode").assertIsDisplayed()
                    compose.onNodeWithText("72% complete").assertIsDisplayed()
                    compose.onNodeWithText("Elapsed 2 min").assertIsDisplayed()
                    compose.onNodeWithText("Remaining", substring = true).assertDoesNotExist()
                    compose.waitUntil(20_000) {
                        compose.onAllNodesWithText("Snapshot received at", substring = true)
                            .fetchSemanticsNodes().isNotEmpty()
                    }
                    capture("printer-connected")
                    compose.onNodeWithTag("experimental-printer-camera-frame").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText("Tool 3 · active").performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText("220.6 / 220 °C").assertIsDisplayed()
                    capture("printer-temperatures")
                    compose.onNodeWithTag("experimental-printer-connection-state").performScrollTo()

                    mode.set(Mode.AUTHORIZATION_REQUIRED)
                    waitFor("Authorization required")
                    compose.onNodeWithText("Showing last known values from", substring = true).assertIsDisplayed()
                    mode.set(Mode.OFFLINE)
                    compose.onNodeWithText("Retry now").performClick()
                    waitFor("Printer offline")
                    compose.onNodeWithText("Showing last known values from", substring = true).assertIsDisplayed()
                    assertEquals(2, compose.onAllNodesWithText("Last known values at", substring = true)
                        .fetchSemanticsNodes().size)
                    capture("printer-offline")
                    assertTrue(requests.isNotEmpty() && requests.all { it.method == "GET" })
                }
            }
        } finally {
            if (previousEndpoint != null && previousKey != null) {
                credentials.put("experimental-printer-dashboard", previousEndpoint, previousKey)
            }
            assertTrue(prefs.edit().putString("endpoint", previousEndpoint).commit())
        }
    }

    private fun makeSnapshot(): ByteArray {
        val image = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(image)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawColor(Color.rgb(28, 34, 40))
            paint.color = Color.rgb(59, 68, 76)
            canvas.drawRoundRect(35f, 87f, 285f, 160f, 8f, 8f, paint)
            paint.color = Color.rgb(117, 219, 169)
            canvas.drawRoundRect(98f, 103f, 214f, 141f, 5f, 5f, paint)
            paint.color = Color.rgb(174, 192, 201)
            canvas.drawRect(0f, 25f, 320f, 39f, paint)
            paint.color = Color.rgb(68, 82, 94)
            canvas.drawRoundRect(112f, 23f, 168f, 72f, 7f, 7f, paint)
            ByteArrayOutputStream().use {
                check(image.compress(Bitmap.CompressFormat.JPEG, 85, it))
                it.toByteArray()
            }
        } finally {
            image.recycle()
        }
    }

    private enum class Mode { PRINTING, AUTHORIZATION_REQUIRED, OFFLINE }

    private companion object {
        const val PRINTING = """{"result":{"status":{
            "webhooks":{"state":"ready"},
            "print_stats":{"state":"printing","filename":"Example bracket.gcode","total_duration":128,
                "info":{"current_layer":28,"total_layer":50}},
            "display_status":{"progress":0.72},
            "virtual_sdcard":{"progress":0.78},
            "heater_bed":{"temperature":64.8,"target":65},
            "extruder":{"temperature":29.4,"target":0},
            "extruder1":{"temperature":31.5,"target":0},
            "extruder2":{"temperature":220.6,"target":220},
            "extruder3":{"temperature":32.7,"target":0},
            "toolhead":{"extruder":"extruder2"}
        }}}"""
    }
}
