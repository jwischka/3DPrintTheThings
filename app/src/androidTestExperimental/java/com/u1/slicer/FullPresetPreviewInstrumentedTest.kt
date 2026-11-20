package com.u1.slicer

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.presets.FullPresetViewModel
import com.u1.slicer.presets.preview.FullPresetPreview
import com.u1.slicer.viewer.ModelViewerView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
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
            assertModelRendered(scenario, ::isStlColor)
            captureScreen("preview-stl")

            scenario.onActivity {
                requireNotNull(findViewer(it.window.decorView)).preserveEGLContextOnPause = false
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            assertModelRendered(scenario, ::isStlColor)
            captureScreen("preview-restored")
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
            val painted = preview.mesh.batches.associate { batch ->
                val slot = requireNotNull(batch.materialIndices).get(0).toInt() and 255
                val rgba = requireNotNull(batch.colorBuffer)
                slot to listOf(rgba.get(0), rgba.get(1), rgba.get(2))
            }
            assertEquals(listOf(157 / 255f, 34 / 255f, 53 / 255f), painted[0])
            assertEquals(listOf(0f, 0f, 0f), painted[1])
            assertNativeModelCleared()
            assertModelRendered(scenario) { pixel ->
                Color.red(pixel) > 65 && Color.red(pixel) > Color.green(pixel) * 1.4f
            }
            captureScreen("preview-3mf")

            import(scenario, model, malformedStl)
            assertEquals(R.string.full_presets_model_rejected, model.state.value.error?.message)
            assertSame(preview, model.state.value.preview)
            assertSame(imported, model.state.value.model)
            assertTrue(imported.file.isFile)
            assertNativeModelCleared()
        }
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

    private fun assertModelRendered(scenario: ActivityScenario<FullPresetActivity>, isModelColor: (Int) -> Boolean) {
        // A valid surface might still be presenting its previous frame just after the GL upload.
        compose.waitUntil(20_000) {
            val captured = CountDownLatch(1)
            var frame: Bitmap? = null
            scenario.onActivity {
                val viewer = findViewer(it.window.decorView)
                if (viewer == null || !viewer.holder.surface.isValid || viewer.renderer.meshData == null) {
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
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                pixels.count(isModelColor) > pixels.size / 50
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun isStlColor(pixel: Int): Boolean {
        val red = Color.red(pixel)
        return Color.green(pixel) > 70 && Color.green(pixel) > red * 1.4f &&
            Color.blue(pixel) > red * 1.4f
    }

    private fun captureScreen(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "preset-workflow-capture")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory, "$name.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
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
