package com.u1.slicer

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.presets.FullPresetStore
import com.u1.slicer.presets.FullPresetViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises selected document URIs, real controls, native slicing and exact-byte export. */
@RunWith(AndroidJUnit4::class)
class FullPresetWorkflowInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun importSelectSliceAndExport() {
        assertTrue("This test requires the standalone experimental APK", BuildConfig.FULL_PRESET_EXPERIMENTAL)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val store = FullPresetStore(File(context.noBackupFilesDir, "full-presets"))
        val originalIds = store.list().map { it.id }.toSet()
        val documents = File(context.cacheDir, "preset-workflow-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        fun asset(name: String): Uri {
            val file = File(documents, name)
            instrumentation.context.assets.open(name).use { source -> file.outputStream().use(source::copyTo) }
            return uri(file)
        }
        val actual = asset("full-presets-actual.zip")
        val diagnostic = asset("full-presets-diagnostic.zip")
        val cube = asset("cube_10mm.stl")
        val invalid = uri(File(documents, "broken.zip").apply { writeText("not a preset bundle") })
        fun screenshot(name: String) {
            compose.waitForIdle()
            val directory = File(context.filesDir, "preset-workflow-capture").apply { mkdirs() }
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        try {
        ActivityScenario.launch(FullPresetActivity::class.java).use { scenario ->
            lateinit var model: FullPresetViewModel
            scenario.onActivity { model = ViewModelProvider(it)[FullPresetViewModel::class.java] }
            fun idle() = compose.waitUntil(180_000) { model.state.value.operation == null }
            fun noError() = assertNull("Workflow failed: ${model.state.value.error}", model.state.value.error)
            fun act(action: FullPresetViewModel.() -> Unit) {
                scenario.onActivity { model.action() }
                idle()
            }
            fun openSettings() {
                compose.onNodeWithTag("full-presets-settings").performScrollTo().assertIsEnabled().performClick()
            }
            fun closeSettings() {
                compose.onNodeWithTag("full-presets-settings-done").assertIsEnabled().performClick()
            }
            fun slice() {
                compose.onNodeWithTag("full-presets-slice").assertIsEnabled().performClick()
                idle()
                noError()
                assertNotNull("Slice completed without an exportable result", model.state.value.output)
                compose.onNodeWithTag("full-presets-export").assertIsEnabled()
            }
            idle()
            compose.onNodeWithTag("full-presets-import-model").assertIsEnabled()
            screenshot("empty")
            act { importBundle(actual) }
            noError()
            val firstBundle = requireNotNull(model.state.value.bundleId)
            act { importBundle(diagnostic) }
            noError()
            assertEquals(originalIds.size + 2, model.state.value.bundles.size)
            assertNotEquals(firstBundle, model.state.value.bundleId)
            assertEquals(4, model.state.value.selection?.filaments?.size)
            act { importModel(cube) }
            noError()
            screenshot("selection")
            openSettings()
            screenshot("settings")
            compose.onNodeWithTag("full-presets-import-bundle").performScrollTo().assertIsEnabled()
            closeSettings()
            slice()
            compose.onNodeWithTag("full-presets-result").performScrollTo()
            screenshot("result")
            val firstResult = requireNotNull(model.state.value.output).result
            assertEquals(50, firstResult.totalLayers)
            val bytes = File(firstResult.gcodePath).readBytes()
            assertTrue(bytes.toString(Charsets.UTF_8).contains("; layer_height = 0.2"))
            val exported = File(documents, "exported.gcode")
            act { exportGcode(uri(exported)) }
            noError()
            assertArrayEquals("Document export changed the native output", bytes, exported.readBytes())

            act { importBundle(invalid) }
            assertNotNull(model.state.value.error)
            assertNull("Failed import retained an exportable old slice", model.state.value.output)
            assertEquals(originalIds.size + 2, store.list().size)

            openSettings()
            repeat(3) {
                compose.onNodeWithTag("full-presets-remove-filament").performScrollTo().performClick()
            }
            assertEquals(1, model.state.value.selection?.filaments?.size)
            closeSettings()
            slice()
            val oneSlot = requireNotNull(model.state.value.output).result
            assertTrue(File(oneSlot.gcodePath).readText().contains("; filament_flow_ratio = 0.98\n"))
            val selectedId = requireNotNull(model.state.value.bundleId)
            assertEquals(1, FullPresetStore(File(context.noBackupFilesDir, "full-presets")).get(selectedId)?.filaments?.size)
            openSettings()
            compose.onNodeWithTag("full-presets-bundle").performScrollTo().performClick()
            compose.onNodeWithTag("full-presets-bundle-option-$firstBundle").performClick()
            assertNull("Changing presets retained a stale result", model.state.value.output)
            assertEquals(4, model.state.value.selection?.filaments?.size)
        }
        } finally {
            store.list().filter { it.id !in originalIds }.forEach { store.delete(it.id) }
            documents.deleteRecursively()
        }
    }
}
