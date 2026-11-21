package com.u1.slicer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntRect
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    val baselineLineHeight = waitForHeadingLineHeight(1f)
                    val baselineGlyphHeight = captureRenderedPreview(stage)

                    stage = "layout-landscape-large-text"
                    shell("settings --user current put system font_scale 1.5")
                    waitForConfiguration(scenario, Configuration.ORIENTATION_LANDSCAPE, 1.5f)
                    assertMainActions()
                    val largeLineHeight = waitForHeadingLineHeight(1.5f, baselineLineHeight)
                    val largeGlyphHeight = captureRenderedPreview(stage, baselineGlyphHeight)
                    checkSettings()

                    stage = "layout-landscape-error-max-text"
                    shell("settings --user current put system font_scale 2.0")
                    waitForConfiguration(scenario, Configuration.ORIENTATION_LANDSCAPE, 2f)
                    waitForHeadingLineHeight(2f, largeLineHeight)
                    val invalidBundle = File(documents, "invalid.zip").apply { writeText("not a preset bundle") }
                    val invalidBundleUri = documentUri(invalidBundle)
                    val selection = requireNotNull(model.state.value.selection)
                    scenario.onActivity { model.importBundle(invalidBundleUri) }
                    idle(model)
                    assertNotNull(model.state.value.error)
                    assertEquals(selection, model.state.value.selection)
                    reachControl("full-presets-error-details")
                    captureRenderedPreview(stage, largeGlyphHeight)
                    val dimensions = requireNotNull(
                        compose.onNodeWithTag("full-presets-model-dimensions").assertIsDisplayed().textLayout(),
                    ) { "Expected dimensions for the imported 10 mm cube" }
                    assertFalse(
                        "Cube dimensions are clipped at 200% text size: ${dimensions.layoutInput.text.text}; " +
                            "size=${dimensions.size}, constraints=${dimensions.layoutInput.constraints}, " +
                            "lines=${dimensions.lineCount}, horizontal=${dimensions.didOverflowWidth}, " +
                            "vertical=${dimensions.didOverflowHeight}",
                        dimensions.hasVisualOverflow,
                    )
                    openSettings()
                    compose.onNodeWithTag("full-presets-add-filament")
                        .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                    assertNull(model.state.value.error)
                    compose.onNodeWithTag("full-presets-remove-filament")
                        .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                    assertEquals(selection, model.state.value.selection)
                    control("full-presets-settings-done").performClick()
                    reachControl("full-presets-slice")

                    stage = "layout-portrait-large-text"
                    shell("settings --user current put system font_scale 1.5")
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    waitForConfiguration(scenario, Configuration.ORIENTATION_PORTRAIT, 1.5f)
                    assertMainActions()
                    waitForHeadingLineHeight(1.5f, baselineLineHeight)
                    captureRenderedPreview(stage, baselineGlyphHeight)
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
        reachControl("full-presets-tab-printer")
        reachControl("full-presets-tab-prepare")
        control("full-presets-slice")
    }

    private fun prepareHeading() = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.full_presets_title).let { title ->
            compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        }

    private fun waitForHeadingLineHeight(fontScale: Float, largerThan: Float = 0f): Float {
        val heading = prepareHeading()
        var lineHeight = 0f
        compose.waitUntil(20_000) {
            if (!heading.isDisplayed()) return@waitUntil false
            val layout = heading.textLayout() ?: return@waitUntil false
            lineHeight = layout.getLineBottom(0) - layout.getLineTop(0)
            abs(layout.layoutInput.density.fontScale - fontScale) < 0.01f && lineHeight > largerThan
        }
        return lineHeight
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult? {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.singleOrNull()
    }

    private fun checkSettings() {
        openSettings()
        compose.onNodeWithTag("full-presets-printer")
            .performScrollTo().assertIsDisplayed().assertIsEnabled()
        control("full-presets-settings-done").performClick()
    }

    private fun openSettings() {
        compose.onNodeWithTag("full-presets-settings")
            .performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
    }

    private fun captureRenderedPreview(name: String, largerThanGlyphHeight: Int = 0): Int {
        val viewport = compose.onNodeWithTag(PREVIEW_MODEL_SURFACE).assertIsDisplayed()
        val heading = prepareHeading().assertIsDisplayed()
        var glyphHeight = 0
        var modelPixels = 0
        var viewportPixels = 0
        // Check the composited glyphs and rendered mesh in the same frame we save.
        try {
            compose.waitUntil(20_000) {
                compose.waitForIdle()
                val viewportBounds = viewport.fetchSemanticsNode().boundsInWindow
                val headingBounds = heading.fetchSemanticsNode().boundsInWindow
                val bitmap = takeFullPresetScreenshot()
                try {
                    glyphHeight = bitmap.headingGlyphHeight(headingBounds)
                    val pixels = bitmap.pixelsIn(bitmap.screenRegion(viewportBounds))
                    viewportPixels = pixels.size
                    modelPixels = pixels.count {
                        val red = Color.red(it)
                        Color.green(it) > 70 && Color.green(it) > red * 1.4f && Color.blue(it) > red * 1.4f
                    }
                    if (glyphHeight <= largerThanGlyphHeight || modelPixels <= viewportPixels / 50) {
                        return@waitUntil false
                    }
                    saveScreenshot(bitmap, name)
                    true
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError(
                "$name expected heading glyph height greater than $largerThanGlyphHeight px, " +
                    "last frame measured $glyphHeight px and showed $modelPixels model-color pixels " +
                    "across $viewportPixels viewport pixels.",
                timeout,
            )
        }
        return glyphHeight
    }

    private fun Bitmap.headingGlyphHeight(bounds: Rect): Int {
        val region = screenRegion(bounds)
        val pixels = pixelsIn(region)
        var firstInkRow = -1
        var lastInkRow = -1
        for (row in 0 until region.height) {
            var ink = 0
            for (column in 0 until region.width) {
                val color = pixels[row * region.width + column]
                if (Color.red(color) < 100 && Color.green(color) < 100 && Color.blue(color) < 100) {
                    ink++
                }
            }
            if (ink >= 2) {
                if (firstInkRow < 0) firstInkRow = row
                lastInkRow = row
            }
        }
        return if (firstInkRow < 0) 0 else lastInkRow - firstInkRow + 1
    }

    private fun Bitmap.screenRegion(bounds: Rect): IntRect {
        val left = bounds.left.roundToInt().coerceIn(0, width)
        val top = bounds.top.roundToInt().coerceIn(0, height)
        return IntRect(
            left,
            top,
            bounds.right.roundToInt().coerceIn(left, width),
            bounds.bottom.roundToInt().coerceIn(top, height),
        )
    }

    private fun Bitmap.pixelsIn(region: IntRect): IntArray {
        if (region.width == 0 || region.height == 0) return IntArray(0)
        return IntArray(region.width * region.height).also {
            getPixels(it, 0, region.width, region.left, region.top, region.width, region.height)
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

    private fun reachControl(tag: String) {
        val node = compose.onNodeWithTag(tag)
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().assertIsEnabled()
    }

    private fun assetUri(directory: File, name: String): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(directory, name)
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use(input::copyTo) }
        return documentUri(file)
    }

    private fun documentUri(file: File): Uri {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun shell(command: String): String {
        val shellOutput = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(shellOutput).bufferedReader().use { it.readText() }
    }
}
