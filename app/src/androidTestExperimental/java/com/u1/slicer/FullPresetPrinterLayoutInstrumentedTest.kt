package com.u1.slicer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.u1.slicer.data.MoonrakerApiKeyStore
import com.u1.slicer.ui.printer.experimental.ExperimentalPrinterConnection
import java.net.InetAddress
import kotlin.math.abs
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullPresetPrinterLayoutInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun connectionEditorScrollsAboveKeyboardInLandscapeAtLargeText() {
        assertTrue(BuildConfig.FULL_PRESET_EXPERIMENTAL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val fontScale = shell(automation, "settings --user current get system font_scale").trim()
        val keyboardSetting = shell(automation, "settings --user current get secure show_ime_with_hard_keyboard").trim()
        require(fontScale == "null" || fontScale.toFloatOrNull()?.isFinite() == true)
        require(keyboardSetting in setOf("null", "0", "1"))
        val service = requireNotNull(automation.serviceInfo)
        val serviceFlags = service.flags

        val prefs = context.getSharedPreferences("experimental-printer", Context.MODE_PRIVATE)
        val previousEndpoint = prefs.getString("endpoint", null)
        val credentials = MoonrakerApiKeyStore(context.noBackupFilesDir)
        val previousKey = previousEndpoint?.let { credentials.get(PRINTER_ID, it) }
        val settings = ExperimentalPrinterConnection(context)

        try {
            shell(automation, "settings --user current put system font_scale 1.5")
            shell(automation, "settings --user current put secure show_ime_with_hard_keyboard 1")
            service.flags = serviceFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service

            MockWebServer().use { server ->
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.method == "GET" && request.path?.startsWith("/printer/objects/query?") == true) {
                            MockResponse().setBody(
                                """{"result":{"status":{"webhooks":{"state":"ready"},"print_stats":{"state":"standby"}}}}""",
                            )
                        } else {
                            MockResponse().setResponseCode(404)
                        }
                }
                settings.save("http://127.0.0.1:${server.port}", "test-fixture-key", clearApiKey = false)
                ActivityScenario.launch(FullPresetActivity::class.java).use { scenario ->
                    var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    try {
                        scenario.onActivity {
                            previousOrientation = it.requestedOrientation
                            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                        compose.waitUntil(20_000) {
                            var configured = false
                            if (scenario.state == Lifecycle.State.RESUMED) {
                                scenario.onActivity {
                                    val config = it.resources.configuration
                                    configured = config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                                        abs(config.fontScale - 1.5f) < 0.01f
                                }
                            }
                            configured
                        }
                        compose.onNodeWithTag("full-presets-tab-printer").assertIsDisplayed().performClick()
                        compose.waitUntil(20_000) {
                            compose.onAllNodesWithTag("experimental-printer-edit").fetchSemanticsNodes().isNotEmpty()
                        }
                        compose.onNodeWithTag("experimental-printer-edit").performScrollTo().performClick()

                        field("experimental-printer-endpoint").performTouchInput { click() }
                        compose.waitUntil(20_000) { imeVisible(automation) }
                        field("experimental-printer-api-key")
                        assertTrue("Software keyboard is absent", imeVisible(automation))
                        capture("printer-editor-keyboard")

                        val remove = field("experimental-printer-clear-key")
                        remove.performTouchInput { click() }.assertIsOn()
                        field("experimental-printer-cancel")
                        val save = field("experimental-printer-save")
                        assertTrue("Software keyboard is absent", imeVisible(automation))
                        capture("printer-editor-actions-keyboard")
                        save.performTouchInput { click() }
                        compose.waitUntil(20_000) {
                            compose.onAllNodesWithTag("experimental-printer-save").fetchSemanticsNodes().isEmpty()
                        }
                        assertFalse(settings.load().hasApiKey)
                    } finally {
                        scenario.onActivity { it.requestedOrientation = previousOrientation }
                    }
                }
            }
        } finally {
            try {
                credentials.remove(PRINTER_ID)
                if (previousEndpoint != null && previousKey != null) {
                    credentials.put(PRINTER_ID, previousEndpoint, previousKey)
                }
            } finally {
                try {
                    assertTrue(prefs.edit().putString("endpoint", previousEndpoint).commit())
                } finally {
                    try {
                        service.flags = serviceFlags
                        automation.serviceInfo = service
                    } finally {
                        try {
                            restoreSetting(automation, "secure", "show_ime_with_hard_keyboard", keyboardSetting)
                        } finally {
                            restoreSetting(automation, "system", "font_scale", fontScale)
                        }
                    }
                }
            }
        }
    }

    private fun field(tag: String) =
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()

    private fun imeVisible(automation: UiAutomation): Boolean = automation.windows.any { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
        val bounds = Rect()
        window.getBoundsInScreen(bounds)
        !bounds.isEmpty
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        saveFullPresetScreenshot(name)
    }

    private fun restoreSetting(automation: UiAutomation, group: String, key: String, value: String) {
        val operation = if (value == "null") "delete $group $key" else "put $group $key $value"
        shell(automation, "settings --user current $operation")
    }

    private fun shell(automation: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use {
            it.readText()
        }

    private companion object {
        const val PRINTER_ID = "experimental-printer-dashboard"
    }
}
