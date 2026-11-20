package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import com.u1.slicer.gcode.FeatureType
import com.u1.slicer.gcode.GcodeParser
import com.u1.slicer.gcode.MoveType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Runs only with the experimental native engine and pinned preset assets supplied by CI. */
@RunWith(AndroidJUnit4::class)
class NativePresetInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val native = NativeLibrary()
    private lateinit var bundle: File
    private lateinit var cube: File
    private lateinit var summary: JSONObject
    private lateinit var diagnostics: File

    @Before fun setUp() {
        assertTrue("Experimental native library is required", NativeLibrary.isLoaded)
        native.clearModel()
        assertTrue(native.loadProfile(""))
        bundle = File(target.cacheDir, "native-preset-${System.nanoTime()}")
        assertTrue(bundle.mkdirs())
        copyAsset("full-presets/summary.json", File(bundle, "summary.json"))
        summary = JSONObject(File(bundle, "summary.json").readText())
        val files = summary.getJSONObject("variants").getJSONObject("diagnostic")
            .getJSONObject("presetFilesSha256")
        val paths = mutableSetOf("actual.json", "diagnostic.json")
        for (key in files.keys()) paths.add(key)
        for (path in paths) copyAsset("full-presets/$path", File(bundle, path))
        cube = File(bundle, "cube_10mm.stl")
        copyAsset("cube_10mm.stl", cube)
        assertEquals(CUBE_SHA, sha256(cube))
        diagnostics = File(bundle, "native-events.jsonl")
        native.configureDiagnostics(diagnostics.absolutePath)
        assertTrue("Cube could not be loaded", native.loadModel(cube.absolutePath))
        // Host reference centers the 10 mm cube at (135,135). JNI placement
        // accepts lower-left XY, so its requested minimum is (130,130).
        assertTrue("Cube could not be centered", native.setModelInstances(floatArrayOf(130f, 130f)))
        val minima = native.nativeGetObjectWorldAABBMins()
        assertEquals(130f, minima[0], 0.01f)
        assertEquals(130f, minima[1], 0.01f)
    }

    @After fun tearDown() {
        native.clearModel()
        native.loadProfile("")
        native.configureDiagnostics("")
        if (::bundle.isInitialized) bundle.deleteRecursively()
    }

    @Test fun captureActualPinnedPreset() = capture("actual")

    @Test fun captureDiagnosticPinnedPreset() = capture("diagnostic")

    private fun capture(variant: String) {
        val bundleInfo = summary.getJSONObject("variants").getJSONObject(variant)
        val manifest = File(bundle, bundleInfo.getString("manifest"))
        assertEquals(bundleInfo.getString("presetManifestSha256"), sha256(manifest))
        val hashes = bundleInfo.getJSONObject("presetFilesSha256")
        for (path in hashes.keys()) assertEquals(path, hashes.getString(path), sha256(File(bundle, path)))
        assertTrue("Native full preset load failed for $variant", native.loadProfile(manifest.absolutePath))
        val event = diagnostics.readLines().mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .lastOrNull { it.optString("type") == "full_preset_loaded" }
            ?: throw AssertionError("Native full_preset_loaded event missing")
        val payload = event.getJSONObject("payload")
        assertEquals("orca-native-presets-v1", payload.getString("schema"))
        assertEquals(4, payload.getInt("filamentCount"))
        val resolved = payload.getJSONArray("resolvedInputs")
        val resolvedNames = (0 until resolved.length()).map { resolved.getString(it) }.toSet()
        assertEquals("Resolved closure differs from captured hashes", hashes.length(), resolvedNames.size)
        assertEquals(hashes.keys().asSequence().toSet(), resolvedNames)
        assertTrue(payload.getInt("configKeyCount") > 100)

        // Sentinel values must be ignored in full preset mode. The pinned process
        // inherits 0.2 mm layer height and sets bridge_flow=0.8, a key absent
        // from SliceConfig. The filament specifies 0.98 flow in all four slots.
        val result = native.slice(SliceConfig(layerHeight = 0.3f, fillDensity = 0.05f))
            ?: throw AssertionError("Native slice returned null")
        assertTrue("Full preset slice failed: ${result.errorMessage}", result.success)
        val generated = File(result.gcodePath)
        assertTrue("Full preset slice produced no G-code", generated.isFile && generated.length() > 0)
        val parsed = configBlock(generated)
        assertEquals("0.2", parsed.getString("layer_height"))
        assertEquals("0.8", parsed.getString("bridge_flow"))
        assertEquals("15%", parsed.getString("sparse_infill_density"))
        assertEquals(listOf("0.98", "0.98", "0.98", "0.98"),
            parsed.getString("filament_flow_ratio").split(',').map { it.trim() })
        // Orca ConfigOptionStrings serializes with semicolons, unlike numeric vectors.
        val filamentIds = parsed.getString("filament_ids").split(';').map { it.trim() }
        assertEquals(4, filamentIds.size)
        assertTrue("Full preset lost filament identity", filamentIds.all { it.isNotEmpty() })
        assertEquals("Repeated selected filament must keep one identity", 1, filamentIds.toSet().size)
        assertEquals(4, parsed.getString("nozzle_diameter").split(',').size)
        if (variant == "diagnostic") {
            assertEquals("G28\\nG92 E0", parsed.getString("machine_start_gcode"))
        }

        val output = File(target.filesDir, "full-preset-capture")
        assertTrue(output.isDirectory || output.mkdirs())
        val gcode = File(output, "$variant.gcode")
        val metadata = File(output, "$variant.json")
        if (gcode.exists()) assertTrue(gcode.delete())
        if (metadata.exists()) assertTrue(metadata.delete())
        generated.copyTo(gcode)
        val pin = instrumentation.context.assets.open("full-presets/engine-source.txt")
            .bufferedReader().use { it.readText().trim() }
        assertTrue("Invalid engine source pin", Regex("[0-9a-f]{40}").matches(pin))
        val parity = JSONObject()
            .put("schemaVersion", 1)
            .put("engineSourcePin", pin)
            .put("inputSha256", sha256(cube))
            .put("presetManifest", bundleInfo.getString("manifest"))
            .put("presetManifestSchema", bundleInfo.getString("presetManifestSchema"))
            .put("presetManifestSha256", sha256(manifest))
            .put("presetFilesSha256", hashes)
            .put("sanitization", bundleInfo.getJSONObject("sanitization"))
            .put("gcodeSha256", sha256(gcode))
        val capture = JSONObject()
            .put("schemaVersion", 1)
            .put("variant", variant)
            .put("engineVersion", native.getCoreVersion())
            .put("deviceAbis", JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            .put("inputFixture", "tools/parity/fixtures/cube_10mm.stl")
            .put("gcodeSha256", sha256(gcode))
            .put("gcodeBytes", gcode.length())
            .put("config", parsed)
            .put("config_block_valid", true)
            .put("parityProvenance", parity)
        metadata.writeText(capture.toString(2))
        assertTrue(gcode.length() > 0 && metadata.length() > 0)
        if (variant == "actual") {
            val oneFilament = JSONObject(manifest.readText())
            oneFilament.put("filaments", JSONArray().put(oneFilament.getJSONArray("filaments").getString(0)))
            val singleManifest = File(bundle, "single-filament.json")
            singleManifest.writeText(oneFilament.toString())
            assertTrue("One selected filament for four nozzles was rejected",
                native.loadProfile(singleManifest.absolutePath))
            val single = native.slice(SliceConfig()) ?: throw AssertionError("Single filament slice returned null")
            assertTrue("Single filament slice failed: ${single.errorMessage}", single.success)
            val singleConfig = configBlock(File(single.gcodePath))
            assertEquals(4, singleConfig.getString("nozzle_diameter").split(',').size)
            assertEquals(1, singleConfig.getString("filament_flow_ratio").split(',').size)
            assertEquals(1, singleConfig.getString("filament_ids").split(';').size)
        }
    }

    @Test fun invalidManifestBlocksSliceUntilReset() {
        assertTrue("Initial full preset failed", native.loadProfile(File(bundle, "actual.json").absolutePath))
        val actual = JSONObject(File(bundle, "actual.json").readText())
        actual.put("printer", "machine/missing.json")
        val bad = File(bundle, "bad.json")
        bad.writeText(actual.toString())
        assertFalse(native.loadProfile(bad.absolutePath))
        assertFalse(native.slice(SliceConfig())?.success ?: true)
        assertTrue(native.loadProfile(""))
        val legacy = native.slice(SliceConfig())
        assertTrue("Legacy path did not recover after reset", legacy?.success == true)
    }

    @Test fun childOverridesParentAndSliceConfigCannotOverrideIt() {
        val child = File(bundle, "process/precedence_child.json")
        val base = JSONObject(File(bundle, "actual.json").readText())
        val parent = JSONObject(File(bundle, base.getString("process")).readText())
        child.writeText(JSONObject().put("name", "Precedence child")
            .put("type", "process").put("inherits", parent.getString("name"))
            .put("layer_height", "0.12").toString())
        base.put("catalog", JSONArray(base.getJSONArray("catalog").toString())
            .put(base.getString("process")))
        base.put("process", "process/precedence_child.json")
        val manifest = File(bundle, "precedence.json")
        manifest.writeText(base.toString())
        assertTrue("Inherited child preset failed", native.loadProfile(manifest.absolutePath))
        val result = native.slice(SliceConfig(layerHeight = 0.3f))
            ?: throw AssertionError("Child slice returned null")
        assertTrue("Child slice failed: ${result.errorMessage}", result.success)
        val config = configBlock(File(result.gcodePath))
        assertEquals("0.12", config.getString("layer_height"))
        assertEquals("0.8", config.getString("bridge_flow"))
        assertTrue("Loading another valid manifest failed", native.loadProfile(File(bundle, "actual.json").absolutePath))
        val restored = native.slice(SliceConfig(layerHeight = 0.3f))
            ?: throw AssertionError("Restored slice returned null")
        assertTrue("Restored slice failed: ${restored.errorMessage}", restored.success)
        assertEquals("0.2", configBlock(File(restored.gcodePath)).getString("layer_height"))
    }

    @Test fun distinctFilamentChildrenPreserveSlotOrder() {
        assertTrue("Second raw cube could not be added", native.addModel(cube.absolutePath))
        assertEquals(2, native.nativeGetObjectCount())
        assertTrue("Two cubes could not be positioned", native.setObjectPositions(
            floatArrayOf(115f, 130f, 145f, 130f)))
        assertTrue("First cube could not select filament A", native.nativeSetVolumeExtruder(0, 0, 1))
        assertTrue("Second cube could not select filament B", native.nativeSetVolumeExtruder(1, 0, 2))
        val actual = JSONObject(File(bundle, "actual.json").readText())
        val parentPath = actual.getJSONArray("filaments").getString(0)
        val parentName = JSONObject(File(bundle, parentPath).readText()).getString("name")
        val children = listOf("A" to Pair("0.91", "223"), "B" to Pair("0.87", "215"))
        for ((name, values) in children) {
            File(bundle, "filament/flow_$name.json").writeText(JSONObject()
                .put("type", "filament").put("name", "Flow $name")
                .put("inherits", parentName)
                .put("filament_flow_ratio", JSONArray().put(values.first))
                .put("nozzle_temperature", JSONArray().put(values.second)).toString())
        }
        actual.put("filaments", JSONArray(listOf("filament/flow_A.json", "filament/flow_B.json",
            "filament/flow_A.json", "filament/flow_B.json")))
        actual.put("catalog", JSONArray(actual.getJSONArray("catalog").toString()).put(parentPath))
        val manifest = File(bundle, "ordered-filaments.json")
        manifest.writeText(actual.toString())
        assertTrue("Distinct filament preset load failed", native.loadProfile(manifest.absolutePath))
        val result = native.slice(SliceConfig(filamentFlowRatios = floatArrayOf(1f, 1f, 1f, 1f)))
            ?: throw AssertionError("Distinct filament slice returned null")
        assertTrue("Distinct filament slice failed: ${result.errorMessage}", result.success)
        val config = configBlock(File(result.gcodePath))
        assertEquals(listOf("0.91", "0.87", "0.91", "0.87"),
            config.getString("filament_flow_ratio").split(',').map { it.trim() })
        assertEquals(listOf("223", "215", "223", "215"),
            config.getString("nozzle_temperature").split(',').map { it.trim() })
        val parsed = GcodeParser.parse(File(result.gcodePath))
        fun printedCube(xMin: Float, tool: Int): Boolean = parsed.layers.any { layer ->
            layer.moves.any { move ->
                move.type == MoveType.EXTRUDE && move.extruder == tool &&
                    move.featureType != FeatureType.PRIME_TOWER &&
                    move.x0 in (xMin - 0.5f)..(xMin + 10.5f) &&
                    move.x1 in (xMin - 0.5f)..(xMin + 10.5f) &&
                    move.y0 in 129.5f..140.5f && move.y1 in 129.5f..140.5f
            }
        }
        assertTrue("Filament A did not print the first cube", printedCube(115f, 0))
        assertTrue("Filament B did not print the second cube", printedCube(145f, 1))
        assertTrue("Out-of-range model tool could not be set for validation",
            native.nativeSetVolumeExtruder(1, 0, 5))
        assertFalse("Full preset accepted a model tool beyond its four filaments",
            native.slice(SliceConfig())?.success ?: true)
    }

    @Test fun invalidValuesAndTopologyFailClosed() {
        val original = JSONObject(File(bundle, "actual.json").readText())
        val machineName = JSONObject(File(bundle, original.getString("printer")).readText()).getString("name")
        val processName = JSONObject(File(bundle, original.getString("process")).readText()).getString("name")
        val cases = listOf(
            Triple("negative-layer", "process", JSONObject().put("layer_height", "-0.1")),
            Triple("unknown-option", "process", JSONObject().put("definitely_unknown_native_option", "1")),
            Triple("zero-nozzle", "machine", JSONObject().put("nozzle_diameter", JSONArray().put("0"))),
            Triple("nan-nozzle", "machine", JSONObject().put("nozzle_diameter", JSONArray().put("nan"))),
            Triple("sla", "machine", JSONObject().put("printer_technology", "SLA")),
            Triple("one-nozzle-four-filaments", "machine", JSONObject()
                .put("nozzle_diameter", JSONArray().put("0.4"))
                .put("single_extruder_multi_material", "0")),
        )
        for ((case, kind, overrides) in cases) {
            val childPath = "$kind/invalid_$case.json"
            val child = JSONObject().put("type", kind)
                .put("name", "Invalid $case")
                .put("inherits", if (kind == "machine") machineName else processName)
            for (key in overrides.keys()) child.put(key, overrides.get(key))
            File(bundle, childPath).writeText(child.toString())
            val manifest = JSONObject(original.toString())
            val field = if (kind == "machine") "printer" else "process"
            manifest.put("catalog", JSONArray(manifest.getJSONArray("catalog").toString())
                .put(manifest.getString(field)))
            manifest.put(field, childPath)
            val file = File(bundle, "$case.json")
            file.writeText(manifest.toString())
            assertFalse("$case was accepted", native.loadProfile(file.absolutePath))
            assertFalse("$case did not block slicing", native.slice(SliceConfig())?.success ?: true)
        }
    }

    @Test fun fullPresetRejectsProjectInput() {
        native.clearModel()
        val project = File(bundle, "project.3mf")
        copyAsset("die-single-colour.3mf", project)
        assertTrue("Project fixture could not load", native.loadModel(project.absolutePath))
        assertTrue(native.loadProfile(File(bundle, "actual.json").absolutePath))
        val result = native.slice(SliceConfig())
        assertFalse("Full presets must not silently discard embedded project settings",
            result?.success ?: true)
    }

    @Test fun wrongTypeTraversalAndCycleFailClosed() {
        val actual = JSONObject(File(bundle, "actual.json").readText())
        val wrongType = JSONObject(actual.toString()).put("printer", actual.getJSONArray("filaments").getString(0))
        val traversal = JSONObject(actual.toString()).put("printer", "../outside.json")
        val childA = "process/cycle_a.json"
        val childB = "process/cycle_b.json"
        File(bundle, childA).writeText(JSONObject().put("name", "cycle A").put("type", "process")
            .put("inherits", "cycle B").toString())
        File(bundle, childB).writeText(JSONObject().put("name", "cycle B").put("type", "process")
            .put("inherits", "cycle A").toString())
        val cycle = JSONObject(actual.toString()).put("process", childA)
            .put("catalog", JSONArray(actual.getJSONArray("catalog").toString()).put(childB))
        for ((name, content) in listOf("wrong-type" to wrongType, "traversal" to traversal, "cycle" to cycle)) {
            val manifest = File(bundle, "$name.json")
            manifest.writeText(content.toString())
            assertFalse("$name was accepted", native.loadProfile(manifest.absolutePath))
            assertFalse("$name did not block slicing", native.slice(SliceConfig())?.success ?: true)
        }
    }

    private fun copyAsset(asset: String, destination: File) {
        assertTrue(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        instrumentation.context.assets.open(asset).use { input ->
            destination.outputStream().use { input.copyTo(it) }
        }
    }

    private fun configBlock(file: File): JSONObject {
        var inside = false
        var starts = 0
        var ends = 0
        val config = JSONObject()
        file.useLines { lines -> lines.forEach { raw ->
            val line = raw.trim()
            if (line.equals("; CONFIG_BLOCK_START", true)) { starts++; inside = true }
            else if (line.equals("; CONFIG_BLOCK_END", true)) { ends++; inside = false }
            else if (inside && line.startsWith(';') && '=' in line) {
                val pair = line.drop(1).split('=', limit = 2)
                config.put(pair[0].trim(), pair[1].trim())
            }
        } }
        assertEquals("CONFIG_BLOCK start", 1, starts)
        assertEquals("CONFIG_BLOCK end", 1, ends)
        assertFalse("CONFIG_BLOCK remains open", inside)
        return config
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CUBE_SHA = "3bc24cffc43194b17e30507fc6fb06fb6c39bf239087f5c960c1dc9d9dd96e1a"
    }
}
