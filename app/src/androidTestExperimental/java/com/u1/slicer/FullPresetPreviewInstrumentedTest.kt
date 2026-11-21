package com.u1.slicer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.presets.preview.FullPresetPreview
import com.u1.slicer.presets.preview.PREVIEW_MODEL_SURFACE
import com.u1.slicer.viewer.MeshData
import com.u1.slicer.viewer.ModelCameraPose
import com.u1.slicer.viewer.ModelViewerView
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullPresetPreviewInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun rendersImportedStlAfterEglContextRecreation() = withDocuments { directory ->
        val cube = asset(directory, "cube_10mm.stl")
        ActivityScenario.launch(FullPresetActivity::class.java).use { scenario ->
            val model = viewModel(scenario)
            import(scenario, model, cube)

            val mesh = requireReady(model).mesh
            assertEquals(10f, mesh.sizeX, 0.001f)
            assertEquals(10f, mesh.sizeY, 0.001f)
            assertEquals(10f, mesh.sizeZ, 0.001f)
            assertNativeModelCleared()
            assertModelRendered(scenario, "preview-stl", ::isStlColor)
            captureScreen("preview-stl")

            scenario.onActivity {
                requireNotNull(findViewer(it.window.decorView)).preserveEGLContextOnPause = false
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertModelRendered(scenario, "preview-restored", ::isStlColor)
            captureScreen("preview-restored")

            assertCameraSurvivesNavigationAndRotation(scenario)
        }
    }

    @Test
    fun rendersModelWithProjectColorsAndKeepsItOnRejectedImport() = withDocuments { directory ->
        val dualColor = asset(directory, "calib-cube-10-dual-colour-merged.3mf")
        val malformedStl = File(directory, "malformed.stl").apply { writeText("not an STL") }
        ActivityScenario.launch(FullPresetActivity::class.java).use { scenario ->
            val model = viewModel(scenario)
            import(scenario, model, dualColor)

            val preview = requireReady(model)
            val imported = requireNotNull(model.state.value.model)
            assertTrue("3MF volume translations should be preserved", preview.mesh.sizeX > 20f)
            val painted = mutableMapOf<Int, MutableSet<List<Float>>>()
            for (batch in preview.mesh.batches) {
                val materials = requireNotNull(batch.materialIndices)
                val geometry = batch.geometry
                for (triangle in 0 until batch.triangleCount) {
                    val slot = materials.get(triangle).toInt() and 255
                    val colors = painted.getOrPut(slot) { mutableSetOf() }
                    for (vertex in 0 until 3) {
                        val base = (triangle * 3 + vertex) * MeshData.FLOATS_PER_VERTEX + 6
                        colors += listOf(geometry.get(base), geometry.get(base + 1), geometry.get(base + 2))
                    }
                }
            }
            val expectedColors = mapOf(
                0 to setOf(listOf(157 / 255f, 34 / 255f, 53 / 255f)),
                1 to setOf(listOf(0f, 0f, 0f)),
            )
            assertEquals("3MF render colors by original material slot", expectedColors, painted)
            assertNativeModelCleared()
            assertModelRendered(scenario, "preview-3mf") { pixel ->
                Color.red(pixel) > 65 && Color.red(pixel) > Color.green(pixel) * 1.4f
            }
            captureScreen("preview-3mf")

            import(scenario, model, malformedStl)
            assertEquals(R.string.full_presets_model_rejected, model.state.value.error?.message)
            assertSame(preview, model.state.value.preview)
            assertSame(imported, model.state.value.model)
            assertTrue(imported.file.isFile)
            assertNativeModelCleared()

            verifyFileImport(scenario, model, directory, dualColor)
        }
    }

    private fun verifyFileImport(
        scenario: ActivityScenario<FullPresetActivity>,
        model: FullPresetViewModel,
        directory: File,
        previousSource: File,
    ) {
        val previous = requireNotNull(model.state.value.model)
        val cube = asset(directory, "cube_10mm.stl")
        val modelName = "Library cube.STL"
        val sourcePage = "https://www.printables.com/model/42"
        scenario.onActivity {
            assertTrue(model.importModelFile(cube, modelName, "$sourcePage?session=secret#download"))
            assertFalse(
                "A second handoff must wait until the active import finishes",
                model.importModelFile(previousSource, previousSource.name),
            )
        }
        compose.waitUntil(60_000) { model.state.value.operation == null }
        val preview = requireReady(model)
        val imported = requireNotNull(model.state.value.model)
        assertEquals(modelName, imported.name)
        assertEquals(sourcePage, imported.sourceUrl)
        assertEquals(10f, preview.mesh.sizeX, 0.001f)
        assertTrue(imported.file.canonicalFile != cube.canonicalFile)
        assertArrayEquals(cube.readBytes(), imported.file.readBytes())
        assertFalse(previous.file.exists())
        assertTrue(previousSource.isFile)
        compose.onNodeWithTag("full-presets-model-source").assertHasClickAction()
        assertNativeModelCleared()

        val oversized = File(directory, "oversized.stl")
        val oversizedLength = 64L * 1024 * 1024 + 1
        RandomAccessFile(oversized, "rw").use { it.setLength(oversizedLength) }
        scenario.onActivity { assertTrue(model.importModelFile(oversized, oversized.name)) }
        compose.waitUntil(60_000) { model.state.value.operation == null }
        assertEquals(R.string.full_presets_model_too_large, model.state.value.error?.message)
        assertSame(imported, model.state.value.model)
        assertSame(preview, model.state.value.preview)
        assertArrayEquals(cube.readBytes(), imported.file.readBytes())
        assertEquals(oversizedLength, oversized.length())
        assertEquals(listOf(imported.file.parentFile), imported.file.parentFile?.parentFile?.listFiles()?.toList())

        val invalidArchive = File(directory, "invalid.3mf").apply { writeText("not a 3MF archive") }
        scenario.onActivity { assertTrue(model.importModelFile(invalidArchive, invalidArchive.name)) }
        compose.waitUntil(60_000) { model.state.value.operation == null }
        assertEquals(R.string.full_presets_3mf_invalid, model.state.value.error?.message)
        assertSame(imported, model.state.value.model)
        assertSame(preview, model.state.value.preview)
        assertTrue(invalidArchive.isFile)
        assertTrue(cube.isFile)
        assertNativeModelCleared()
    }

    private fun requireReady(model: FullPresetViewModel): FullPresetPreview.Ready {
        val state = model.state.value
        assertNull("Model import failed", state.error)
        return checkNotNull(state.preview as? FullPresetPreview.Ready) {
            "Preview was ${state.preview} after import"
        }
    }

    private fun assertNativeModelCleared() = runBlocking {
        NativeLibrary.previewMutex.withLock {
            assertEquals(0, NativeLibrary().nativeGetObjectCount())
        }
    }

    private fun assertCameraSurvivesNavigationAndRotation(scenario: ActivityScenario<FullPresetActivity>) {
        val originalPose = awaitCameraPose(scenario)
        compose.onNodeWithTag(PREVIEW_MODEL_SURFACE).performTouchInput {
            swipe(start = center, end = center + Offset(-width * 0.18f, height * 0.08f))
        }
        val orbited = awaitCameraPose(scenario) {
            abs(it.view.azimuth - originalPose.view.azimuth) > 5
        }

        compose.onNodeWithTag("full-presets-tab-printer").performClick()
        compose.onNodeWithTag("full-presets-tab-prepare").performClick()
        assertEquivalentPose(orbited, awaitCameraPose(scenario))

        var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        var targetOrientation = Configuration.ORIENTATION_LANDSCAPE
        try {
            scenario.onActivity {
                savedOrientation = it.requestedOrientation
                if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    targetOrientation = Configuration.ORIENTATION_PORTRAIT
                }
                it.requestedOrientation = if (targetOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            compose.waitUntil(20_000) {
                var matched = false
                if (scenario.state == Lifecycle.State.RESUMED) {
                    scenario.onActivity { matched = it.resources.configuration.orientation == targetOrientation }
                }
                matched
            }
            assertEquivalentPose(orbited, awaitCameraPose(scenario))

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onNodeWithContentDescription(context.getString(R.string.full_presets_preview_fit)).performClick()
            val fitted = awaitCameraPose(scenario) { abs(it.view.azimuth - originalPose.view.azimuth) < 0.001 }
            assertEquivalentPose(originalPose, fitted)
        } finally {
            if (scenario.state != Lifecycle.State.DESTROYED) {
                scenario.onActivity { it.requestedOrientation = savedOrientation }
            }
        }
    }

    private fun awaitCameraPose(
        scenario: ActivityScenario<FullPresetActivity>,
        predicate: (ModelCameraPose) -> Boolean = { true },
    ): ModelCameraPose {
        var latest: ModelCameraPose? = null
        compose.waitUntil(20_000) {
            scenario.onActivity { latest = findViewer(it.window.decorView)?.camera?.modelPose() }
            latest?.let(predicate) == true
        }
        return requireNotNull(latest)
    }

    private fun assertEquivalentPose(expected: ModelCameraPose, actual: ModelCameraPose) {
        val expectedZoom = expected.view.distance / expected.fitDistance
        val actualZoom = actual.view.distance / actual.fitDistance
        assertEquals(expectedZoom, actualZoom, 0.001)
        assertEquals(expected.view.copy(distance = actual.view.distance), actual.view)
    }

    private fun assertModelRendered(
        scenario: ActivityScenario<FullPresetActivity>,
        name: String,
        isModelColor: (Int) -> Boolean,
    ) {
        // A valid surface might still be presenting its previous frame just after the GL upload.
        var lastFrame: Bitmap? = null
        var lastViewerState = "GL viewer unavailable"
        var modelPixels = 0
        try {
            compose.waitUntil(20_000) {
                val captured = CountDownLatch(1)
                var frame: Bitmap? = null
                scenario.onActivity {
                    val viewer = findViewer(it.window.decorView)
                    val mesh = viewer?.renderer?.meshData
                    lastViewerState = if (viewer == null) "GL viewer unavailable" else
                        "GL viewer ${viewer.width}x${viewer.height}, attached=${viewer.isAttachedToWindow}, " +
                            "surface=${viewer.holder.surface.isValid}, camera=${viewer.camera.snapshot()}, ${describeMesh(mesh)}"
                    if (viewer == null || !viewer.holder.surface.isValid || mesh == null) {
                        captured.countDown()
                    } else {
                        viewer.captureBitmap { bitmap ->
                            frame = bitmap
                            captured.countDown()
                        }
                    }
                }
                assertTrue("GL surface screenshot timed out", captured.await(5, TimeUnit.SECONDS))
                val bitmap = frame ?: return@waitUntil false
                lastFrame?.recycle()
                lastFrame = bitmap
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                modelPixels = pixels.count(isModelColor)
                modelPixels > pixels.size / 50
            }
        } catch (timeout: ComposeTimeoutException) {
            val bitmap = lastFrame
            runCatching { bitmap?.let { savePng(it, "$name-failed-surface") } }
                .exceptionOrNull()?.let(timeout::addSuppressed)
            runCatching { captureScreen("$name-failed-screen") }.exceptionOrNull()?.let(timeout::addSuppressed)
            val frameDetails = if (bitmap == null) "PixelCopy supplied no frame"
            else "PixelCopy ${bitmap.width}x${bitmap.height}, $modelPixels model-color pixels, " +
                "common colors=${sampleColors(bitmap)}"
            throw AssertionError("$name did not display model pixels. $lastViewerState. $frameDetails", timeout)
        } finally {
            lastFrame?.recycle()
        }
    }

    private fun describeMesh(mesh: MeshData?): String {
        if (mesh == null) return "no uploaded mesh"
        val first = mesh.batches.first()
        val vertex = (0 until 3).map(first.geometry::get)
        val color = (6 until MeshData.FLOATS_PER_VERTEX).map(first.geometry::get)
        return "${mesh.vertexCount} vertices, bounds=" +
            "[${mesh.minX}, ${mesh.minY}, ${mesh.minZ}]-[${mesh.maxX}, ${mesh.maxY}, ${mesh.maxZ}], " +
            "first vertex=$vertex, material=${first.materialIndices?.get(0)?.toInt()?.and(255)}, color=$color"
    }

    private fun sampleColors(bitmap: Bitmap): List<String> {
        val colors = mutableMapOf<Int, Int>()
        for (y in 0 until bitmap.height step 8) {
            for (x in 0 until bitmap.width step 8) {
                val value = bitmap.getPixel(x, y)
                colors[value] = colors.getOrDefault(value, 0) + 1
            }
        }
        return colors.entries.sortedByDescending { it.value }.take(5).map { "%08x=%d".format(it.key, it.value) }
    }

    private fun isStlColor(pixel: Int): Boolean {
        val red = Color.red(pixel)
        return Color.green(pixel) > 70 && Color.green(pixel) > red * 1.4f &&
            Color.blue(pixel) > red * 1.4f
    }

    private fun captureScreen(name: String) {
        compose.waitForIdle()
        saveFullPresetScreenshot(name)
    }

    private fun savePng(bitmap: Bitmap, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "preset-workflow-capture")
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun findViewer(view: View): ModelViewerView? {
        if (view is ModelViewerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findViewer(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun viewModel(scenario: ActivityScenario<FullPresetActivity>): FullPresetViewModel {
        lateinit var model: FullPresetViewModel
        scenario.onActivity { model = ViewModelProvider(it)[FullPresetViewModel::class.java] }
        compose.waitUntil(20_000) { model.state.value.operation == null }
        return model
    }

    private fun import(scenario: ActivityScenario<FullPresetActivity>, model: FullPresetViewModel, file: File) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        scenario.onActivity { model.importModel(uri) }
        compose.waitUntil(60_000) { model.state.value.operation == null }
    }

    private fun withDocuments(test: (File) -> Unit) {
        assertTrue(BuildConfig.FULL_PRESET_EXPERIMENTAL)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "preset-preview-${java.util.UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        try {
            test(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun asset(directory: File, name: String): File = File(directory, name).apply {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            outputStream().use(input::copyTo)
        }
    }
}
