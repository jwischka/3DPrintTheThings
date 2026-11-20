package com.u1.slicer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.presets.FullPresetStore
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.presets.preview.PREVIEW_MODEL_SURFACE
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullPresetLayoutInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun landscapeKeepsPreviewAndActionsReachableAtLargeText() {
        assertTrue(BuildConfig.FULL_PRESET_EXPERIMENTAL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val savedFontScale = shell("settings --user current get system font_scale").trim()
        require(savedFontScale == "null" || savedFontScale.toFloatOrNull()?.isFinite() == true)
        val store = FullPresetStore(File(context.noBackupFilesDir, "full-presets"))
        val existingBundles = store.list().map { it.id }.toSet()
        val documents = File(context.cacheDir, "preset-layout-${UUID.randomUUID()}")
        assertTrue(documents.mkdirs())
        try {
            shell("settings --user current put system font_scale 1.0")
            ActivityScenario.launch(FullPresetActivity::class.java).use { scenario ->
                var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                var stage = "layout-landscape"
                try {
                    scenario.onActivity {
                        savedOrientation = it.requestedOrientation
                        assertTrue("Layout test requires a phone-sized screen", it.resources.configuration.smallestScreenWidthDp < 600)
                        it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    waitForConfiguration(scenario, Configuration.ORIENTATION_LANDSCAPE, 1f)
                    lateinit var model: FullPresetViewModel
                    scenario.onActivity { model = ViewModelProvider(it)[FullPresetViewModel::class.java] }
                    idle(model)
                    control("full-presets-import-model")

                    val bundle = assetUri(documents, "full-presets-actual.zip")
                    scenario.onActivity { model.importBundle(bundle) }
                    idle(model)
                    assertNull(model.state.value.error)

                    val cube = assetUri(documents, "cube_10mm.stl")
                    scenario.onActivity { model.importModel(cube) }
                    idle(model)
                    assertNull(model.state.value.error)
                    assertMainActions()
                    captureRenderedPreview(stage)

                    stage = "layout-landscape-large-text"
                    shell("settings --user current put system font_scale 1.5")
                    waitForConfiguration(scenario, Configuration.ORIENTATION_LANDSCAPE, 1.5f)
                    assertMainActions()
                    captureRenderedPreview(stage)
                    checkSettings()

                    stage = "layout-portrait-large-text"
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    waitForConfiguration(scenario, Configuration.ORIENTATION_PORTRAIT, 1.5f)
                    assertMainActions()
                    captureRenderedPreview(stage)
                    checkSettings()
                    control("full-presets-slice").performClick()
                    idle(model)
                    assertNull(model.state.value.error)
                    assertNotNull(model.state.value.output)
                    control("full-presets-export")
                } catch (failure: Throwable) {
                    runCatching {
                        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        try {
                            saveScreenshot(bitmap, "$stage-failed-screen")
                        } finally {
                            bitmap.recycle()
                        }
                    }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                } finally {
                    if (scenario.state != Lifecycle.State.DESTROYED) {
                        scenario.onActivity { it.requestedOrientation = savedOrientation }
                    }
                }
            }
        } finally {
            try {
                if (savedFontScale == "null") {
                    shell("settings --user current delete system font_scale")
                } else {
                    shell("settings --user current put system font_scale $savedFontScale")
                }
            } finally {
                store.list().filter { it.id !in existingBundles }.forEach { store.delete(it.id) }
                documents.deleteRecursively()
            }
        }
    }

    private fun idle(model: FullPresetViewModel) =
        compose.waitUntil(120_000) { model.state.value.operation == null }

    private fun waitForConfiguration(
        scenario: ActivityScenario<FullPresetActivity>,
        orientation: Int,
        fontScale: Float,
    ) {
        compose.waitUntil(20_000) {
            var matches = false
            if (scenario.state == Lifecycle.State.RESUMED) {
                scenario.onActivity {
                    val actual = it.resources.configuration
                    matches = actual.orientation == orientation && abs(actual.fontScale - fontScale) < 0.01f
                }
            }
            matches
        }
    }

    private fun assertMainActions() {
        control("full-presets-tab-prepare")
        control("full-presets-tab-printer")
        control("full-presets-slice")
    }

    private fun checkSettings() {
        compose.onNodeWithTag("full-presets-settings")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithTag("full-presets-printer")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        control("full-presets-settings-done").performClick()
    }

    private fun captureRenderedPreview(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val viewport = compose.onNodeWithTag(PREVIEW_MODEL_SURFACE).assertIsDisplayed()
        // Use composited screen pixels so a clipped or blank GL surface cannot satisfy the check.
        compose.waitUntil(20_000) {
            val bounds = viewport.fetchSemanticsNode().boundsInWindow
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val left = bounds.left.roundToInt().coerceIn(0, bitmap.width)
                val top = bounds.top.roundToInt().coerceIn(0, bitmap.height)
                val width = bounds.right.roundToInt().coerceIn(left, bitmap.width) - left
                val height = bounds.bottom.roundToInt().coerceIn(top, bitmap.height) - top
                if (width == 0 || height == 0) return@waitUntil false
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, left, top, width, height)
                val modelPixels = pixels.count {
                    val red = Color.red(it)
                    Color.green(it) > 70 && Color.green(it) > red * 1.4f && Color.blue(it) > red * 1.4f
                }
                if (modelPixels <= pixels.size / 50) return@waitUntil false
                saveScreenshot(bitmap, name)
                true
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun saveScreenshot(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "preset-workflow-capture")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun control(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled()

    private fun assetUri(directory: File, name: String): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(directory, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun shell(command: String): String {
        val shellOutput = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(shellOutput).bufferedReader().use { it.readText() }
    }
}
