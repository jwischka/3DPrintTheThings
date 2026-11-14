package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Process
import android.util.Log
import com.u1.slicer.NativeLibrary
import com.u1.slicer.bambu.BambuSanitizer
import com.u1.slicer.bambu.ProfileEmbedder
import com.u1.slicer.bambu.ThreeMfParser
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.GcodeToolRemapper
import com.u1.slicer.gcode.GcodeValidator
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Contract checks against the real experimental engine, with no printer connection. */
@RunWith(AndroidJUnit4::class)
class NativeContractInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private lateinit var dir: File
    private lateinit var native: NativeLibrary
    private var sliceThreadRunning = false

    @Before fun setup() {
        assertTrue("Native engine is required", NativeLibrary.isLoaded)
        dir = File(context.cacheDir, "native-contract").also { it.mkdirs() }
        native = NativeLibrary().also { it.clearModel() }
    }

    @After fun cleanup() {
        if (!sliceThreadRunning) {
            native.clearModel()
            dir.deleteRecursively()
        }
    }

    private fun asset(name: String): File = File(dir, name).also { file ->
        assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    private fun slice(file: File, config: SliceConfig): File {
        assertTrue("Model must load", native.loadModel(file.absolutePath))
        val result = native.slice(config) ?: throw AssertionError("Native slice returned null")
        assertTrue("Slice failed: ${result.errorMessage}", result.success)
        return File(result.gcodePath).also { assertTrue("G-code must exist", it.isFile && it.length() > 0) }
    }

    @Test fun temperatureCommandsReflectConfiguredValues() {
        val gcode = slice(asset("cube_10mm.stl"), SliceConfig(nozzleTemp = 227, bedTemp = 67)).readText()
        val commands = gcode.lineSequence().map { it.substringBefore(';').trim() }.toList()
        assertTrue("Executable nozzle command must use 227 C", commands.any {
            Regex("""^M10[49]\b.*\bS227(?:\.0+)?(?:\s|$)""").containsMatchIn(it)
        })
        assertTrue("Executable bed command must use 67 C", commands.any {
            Regex("""^M1(?:40|90)\b.*\bS67(?:\.0+)?(?:\s|$)""").containsMatchIn(it)
        })
    }

    @Test fun singleToolRetractionAndBedBounds() {
        val generated = slice(asset("cube_10mm.stl"), SliceConfig(retractLength = 0.8f))
        val gcode = generated.readText()
        val summary = GcodeValidator.summarizeParsedOutput(GcodeParser.parse(generated))
        assertTrue("Fixture must contain model extrusion", !GcodeValidator.isEffectivelyEmpty(summary))
        val retract = GcodeValidator.maxRetractionMm(gcode)
        assertTrue("Expected real bounded retraction, got $retract", retract > 0.0 && retract <= 2.0)
        assertTrue("Single tool must not select other tools", GcodeValidator.lacksToolChanges(gcode, "T1", "T2", "T3"))
        val bounds = GcodeValidator.checkBedBounds(gcode)
        assertTrue("Moves outside the bed: ${bounds.violatingLines}", bounds.withinBounds)
        assertTrue("Fixture must have nonzero XY extent", bounds.maxX > bounds.minX && bounds.maxY > bounds.minY)
    }

    @Test fun dualColorToolMappingAndRetraction() {
        val input = asset("calib-cube-10-dual-colour-merged.3mf")
        val sanitized = BambuSanitizer.process(input, dir)
        val info = ThreeMfParser.parse(input)
        val embedder = ProfileEmbedder(context)
        val embedded = embedder.embed(sanitized, embedder.buildConfig(info = info, targetExtruderCount = 2), dir, info)
        val output = slice(embedded, BambuPipelineIntegrationTest.dualConfig())
        val original = output.readText()
        assertTrue("Both compact tools must occur", GcodeValidator.hasToolChanges(original, "T0", "T1"))
        val retract = GcodeValidator.maxRetractionMm(original)
        assertTrue("Expected real bounded retraction, got $retract", retract > 0.0 && retract <= 5.0)
        assertFalse("Bowden unload must not be emitted", GcodeValidator.hasBowdenUnloadSequence(original))
        GcodeToolRemapper.remap(output.absolutePath, listOf(2, 3))
        val mapped = output.readText()
        assertTrue("Both assigned physical tools must occur", GcodeValidator.hasToolChanges(mapped, "T2", "T3"))
        assertTrue("Compact tool commands must be gone", GcodeValidator.lacksToolChanges(mapped, "T0", "T1"))
        assertEquals("Remapping must preserve actual retraction", retract, GcodeValidator.maxRetractionMm(mapped), 0.00001)
    }

    @Test fun cancellationDuringProcessingAllowsNextSlice() {
        val input = asset("cube_10mm.stl")
        assertTrue("Model must load", native.loadModel(input.absolutePath))
        var requested = false
        native.progressListener = { percent, stage ->
            if (!requested && percent in 15..89 && stage != "Starting slicing...") {
                requested = true
                native.cancelSlice()
            }
        }
        val cancelled = try {
            native.slice(SliceConfig()) ?: throw AssertionError("Cancelled slice returned null")
        } finally {
            native.progressListener = null
        }
        assertTrue("Processing callback must request cancellation", requested)
        assertTrue("In-flight slice must report cancellation", cancelled.cancelled && !cancelled.success)
        assertTrue("Cancelled slice must not expose G-code", cancelled.gcodePath.isBlank())
        native.clearModel()
        val recovered = slice(input, SliceConfig())
        assertTrue("A later slice must produce model extrusion", !GcodeValidator.isEffectivelyEmpty(
            GcodeValidator.summarizeParsedOutput(GcodeParser.parse(recovered))
        ))
    }

    @Test fun cancellationAtExportTransitionAllowsNextSlice() = cancelAtStageAndRecover("Generating G-code")

    @Test fun cancellationAtResultReadingAllowsNextSlice() = cancelAtStageAndRecover("Reading results")

    private fun cancelAtStageAndRecover(targetStage: String) {
        val input = asset("cube_10mm.stl")
        assertTrue("Model must load", native.loadModel(input.absolutePath))
        var requested = false
        native.progressListener = { _, stage ->
            if (!requested && stage == targetStage) {
                requested = true
                native.cancelSlice()
            }
        }
        val cancelled = try {
            native.slice(SliceConfig()) ?: throw AssertionError("Cancelled slice returned null")
        } finally {
            native.progressListener = null
        }
        assertTrue("$targetStage callback must request cancellation", requested)
        assertTrue("Slice must report cancellation at $targetStage", cancelled.cancelled && !cancelled.success)
        assertTrue("Cancelled slice must not expose G-code", cancelled.gcodePath.isBlank())
        native.clearModel()
        val recovered = slice(input, SliceConfig())
        assertTrue("A later slice must produce model extrusion", !GcodeValidator.isEffectivelyEmpty(
            GcodeValidator.summarizeParsedOutput(GcodeParser.parse(recovered))
        ))
    }

    @Test fun crossThreadCancellationDuringProcessingAllowsNextSlice() {
        val input = asset("cube_10mm.stl")
        assertTrue("Model must load", native.loadModel(input.absolutePath))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        native.progressListener = { percent, stage ->
            if (percent in 15..89 && stage != "Starting slicing..." && entered.count > 0) {
                entered.countDown()
                check(release.await(30, TimeUnit.SECONDS)) { "Cancellation test was not released" }
            }
        }
        sliceThreadRunning = true
        val future = executor.submit<com.u1.slicer.data.SliceResult?> { native.slice(SliceConfig()) }
        try {
            assertTrue("Processing callback must be reached", entered.await(60, TimeUnit.SECONDS))
            native.cancelSlice()
            release.countDown()
            val cancelled = future.get(90, TimeUnit.SECONDS) ?: throw AssertionError("Cancelled slice returned null")
            assertTrue("Concurrent cancellation must be reported", cancelled.cancelled && !cancelled.success)
            assertTrue("Cancelled slice must not expose G-code", cancelled.gcodePath.isBlank())
        } finally {
            release.countDown()
            executor.shutdown()
            val terminated = executor.awaitTermination(120, TimeUnit.SECONDS)
            sliceThreadRunning = !terminated
            if (!terminated) {
                Log.e("NativeContractTest", "Native slice did not terminate; stopping instrumentation process")
                Process.killProcess(Process.myPid())
                throw AssertionError("Native slice did not terminate")
            }
            native.progressListener = null
        }
        native.clearModel()
        val recovered = slice(input, SliceConfig())
        assertTrue("A later slice must produce model extrusion", !GcodeValidator.isEffectivelyEmpty(
            GcodeValidator.summarizeParsedOutput(GcodeParser.parse(recovered))
        ))
    }
}
