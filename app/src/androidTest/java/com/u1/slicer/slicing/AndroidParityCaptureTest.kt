package com.u1.slicer.slicing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.NativeLibrary
import com.u1.slicer.data.SliceConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

/** Captures Android G-code as evidence; it makes no desktop parity claim. */
@RunWith(AndroidJUnit4::class)
class AndroidParityCaptureTest {
    private val fixture = "cube_10mm.stl"
    private val fixtureSha256 = "3bc24cffc43194b17e30507fc6fb06fb6c39bf239087f5c960c1dc9d9dd96e1a"
    private val profileAssets = listOf(
        "orca_profiles/printer/snapmaker_u1.json",
        "orca_profiles/process/standard_0.20mm.json",
        "orca_profiles/filament/pla.json",
    )

    @Test fun captureCube10mmGcode() {
        assertTrue("Experimental native library is required", NativeLibrary.isLoaded)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val outputDir = File(context.filesDir, "parity-capture")
        assertTrue("Cannot create capture directory", outputDir.isDirectory || outputDir.mkdirs())
        val outputGcode = File(outputDir, "cube_10mm.android.gcode")
        val outputProvenance = File(outputDir, "cube_10mm.android.json")
        // A failed rerun must not leave an earlier capture looking current.
        assertTrue("Cannot clear previous G-code", !outputGcode.exists() || outputGcode.delete())
        assertTrue("Cannot clear previous provenance", !outputProvenance.exists() || outputProvenance.delete())

        val input = File(context.cacheDir, fixture)
        testAssets.open(fixture).use { source -> input.outputStream().use { source.copyTo(it) } }
        val inputHash = sha256(input)
        assertEquals("Fixture differs from tools/parity/fixtures/cube_10mm.stl", fixtureSha256, inputHash)

        val printer = context.assets.open(profileAssets.first()).bufferedReader().use { JSONObject(it.readText()) }
        // Raw STL follows the app's direct SliceConfig path. ProfileEmbedder only embeds 3MF.
        val config = SliceConfig(
            machineStartGcode = printer.getString("machine_start_gcode"),
            machineEndGcode = printer.getString("machine_end_gcode"),
        )
        val serializedConfig = serializeConfig(config)
        val profileHashes = JSONObject()
        for (asset in profileAssets) {
            val hash = context.assets.open(asset).use { sha256(it) }
            profileHashes.put(asset, hash)
        }

        val native = NativeLibrary()
        try {
            native.clearModel()
            assertTrue("Cube fixture could not be loaded", native.loadModel(input.absolutePath))
            val engineVersion = native.getCoreVersion()
            assertTrue("Native engine version is empty", engineVersion.isNotBlank())
            val result = native.slice(config) ?: throw AssertionError("Native slice returned no result")
            assertTrue("Native slice failed: ${result.errorMessage}", result.success)
            assertTrue("Native slice returned no G-code path", result.gcodePath.isNotBlank())
            val generated = File(result.gcodePath)
            assertTrue("Native G-code is missing or empty", generated.isFile && generated.length() > 0)
            generated.copyTo(outputGcode)
            assertTrue("Captured G-code is empty", outputGcode.length() > 0)

            val provenance = JSONObject()
                .put("schemaVersion", 1)
                .put("engineVersion", engineVersion)
                .put("deviceAbis", JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
                .put("inputFixture", "tools/parity/fixtures/$fixture")
                .put("inputSha256", inputHash)
                .put("gcodeSha256", sha256(outputGcode))
                .put("gcodeBytes", outputGcode.length())
                .put("sliceConfig", serializedConfig)
                .put("bundledProfileSha256", profileHashes)
                .put("profileApplication", "raw STL; printer machine G-code in SliceConfig; no 3MF profile embed")
            outputProvenance.writeText(provenance.toString(2))
            assertTrue("Provenance was not saved", outputProvenance.isFile && outputProvenance.length() > 0)
        } finally {
            native.clearModel()
            input.delete()
        }
    }

    private fun serializeConfig(config: SliceConfig): JSONObject = JSONObject().apply {
        for (field in SliceConfig::class.java.declaredFields.sortedBy { it.name }) {
            if (Modifier.isStatic(field.modifiers)) continue
            val value = field.get(config)
            val encoded = if (value != null && value.javaClass.isArray) JSONArray().apply {
                for (index in 0 until java.lang.reflect.Array.getLength(value)) {
                    put(java.lang.reflect.Array.get(value, index))
                }
            } else value
            put(field.name, encoded)
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
