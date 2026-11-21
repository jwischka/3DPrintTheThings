package com.u1.slicer.presets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FullPresetStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val root get() = temporary.root.resolve("bundles")
    private val store get() = FullPresetStore(root)

    @Test
    fun importAndReselectPersistAcrossRecreation() {
        val originalFiles = files()
        originalFiles["filament/"] = byteArrayOf()
        val imported = store.importZip(archive(originalFiles).inputStream())
        assertEquals("Printer", imported.printerName)
        assertEquals("Process", imported.processName)
        assertEquals(listOf("PLA", "PLA"), imported.filamentNames)
        assertEquals(listOf(imported), store.list())
        assertArrayEquals(originalFiles.getValue("manifest.json"), imported.manifest.readBytes())
        assertArrayEquals(originalFiles.getValue("filament/pla.json"), imported.manifest.resolveSibling("filament/pla.json").readBytes())
        assertFalse(imported.catalog.single { it.name == "Material base" }.isSelectable)

        val alternate = "filament/petg.json"
        val saved = store.writeSelection(imported.id, imported.printer.path, imported.process.path, listOf(alternate, imported.filaments[0].path, alternate))
        val current = store.get(imported.id)!!
        assertEquals(saved, current.manifest)
        assertEquals(listOf("PETG", "PLA", "PETG"), current.filamentNames)
        assertEquals(imported.catalog, current.catalog)
        val selection = JSONObject(saved.readText())
        assertEquals("orca-native-presets-v2", selection.getString("schema"))
        assertEquals("geometry-only", selection.getString("project_settings"))
        assertEquals(1, selection.getJSONArray("catalog").length())
        assertEquals("filament/base.json", selection.getJSONArray("catalog").getString(0))
        assertTrue(store.delete(imported.id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun invalidSelectionPreservesLastSavedManifest() {
        val imported = store.importZip(archive(files()).inputStream())
        val saved = store.writeSelection(imported.id, imported.printer.path, imported.process.path, listOf("filament/petg.json"))
        val previous = saved.readBytes()
        for (filaments in listOf(emptyList(), List(17) { "filament/pla.json" }, listOf("filament/base.json"), listOf("machine.json"), listOf("../filament/pla.json"))) {
            assertThrows(IOException::class.java) {
                store.writeSelection(imported.id, imported.printer.path, imported.process.path, filaments)
            }
            assertArrayEquals(previous, saved.readBytes())
        }
    }

    @Test
    fun mappedSelectionUsesPhysicalPresetOrderWithoutChangingManualSelection() {
        val imported = store.importZip(archive(files()).inputStream())
        val printer = imported.printer.path
        val process = imported.process.path
        val pla = imported.filaments[0].path
        val petg = "filament/petg.json"
        val manual = store.writeSelection(imported.id, printer, process, listOf(pla))
        val physical = listOf(petg, pla, petg, pla)
        val manualBytes = manual.readBytes()

        val mapped = store.writeSelection(imported.id, printer, process, physical, listOf(3, 2, 3))
        val manifest = JSONObject(mapped.readText())
        assertEquals("orca-native-presets-v3", manifest.getString("schema"))
        assertEquals("geometry-only", manifest.getString("project_settings"))
        assertEquals(JSONArray(physical).toString(), manifest.getJSONArray("filaments").toString())
        assertEquals("[3,2,3]", manifest.getJSONArray("model_filament_mapping").toString())
        assertEquals(manual, store.get(imported.id)?.manifest)
        assertArrayEquals(manualBytes, manual.readBytes())

        val mappedBytes = mapped.readBytes()
        for (invalid in listOf(emptyList(), listOf(0), listOf(5), List(5) { 1 })) {
            assertThrows(IOException::class.java) { store.writeSelection(imported.id, printer, process, physical, invalid) }
        }
        assertThrows(IOException::class.java) { store.writeSelection(imported.id, printer, process, physical.drop(1), listOf(2)) }
        assertArrayEquals(mappedBytes, mapped.readBytes())
    }

    @Test
    fun rejectsTraversalDuplicatesAndUnlistedFiles() {
        for (path in listOf("../outside.json", "filament/../outside.json", "/outside.json", "C:/outside.json", "filament\\outside.json", "FILAMENT/PLA.JSON", "unlisted.json", ".selection.json")) {
            val invalid = files().apply { put(path, preset("Spare", "filament")) }
            assertRejected(archive(invalid))
        }
        // ZipOutputStream refuses duplicate names. Replace an equally long filename in both headers.
        val duplicate = archive(files().apply { put("filament/xxx.json", preset("Spare", "filament")) })
        replaceBytes(duplicate, "filament/xxx.json", "filament/pla.json")
        assertRejected(duplicate)
        assertFalse(temporary.root.resolve("outside.json").exists())
    }

    @Test
    fun rejectsInvalidManifestAndMalformedPresets() {
        for (invalid in listOf(
            manifest().put("unexpected", true).toString(),
            manifest().put("schema", "orca-native-presets-v2").toString(),
            manifest().put("filaments", JSONArray(listOf(7))).toString(),
            manifest().put("printer", "process.json").toString(),
            manifest().put("catalog", JSONArray(listOf("absent.json"))).toString(),
            manifest().toString() + "garbage",
            "{\"schema\":",
        )) {
            assertRejected(archive(files().apply { put("manifest.json", invalid.toByteArray()) }))
        }
        val validV2 = manifest().put("schema", "orca-native-presets-v2").put("project_settings", "geometry-only")
        val imported = store.importZip(archive(files().apply { put("manifest.json", validV2.toString().toByteArray()) }).inputStream())
        assertEquals("Process", imported.processName)
        for (invalid in listOf("[]", "{\"name\":7,\"type\":\"machine\"}", "{\"name\":\"Printer\",\"type\":\"unknown\"}", "{\"name\":\"Printer\",\"type\":\"machine\",\"inherits\":null}")) {
            assertRejected(archive(files().apply { put("machine.json", invalid.toByteArray()) }), existing = 1)
        }
    }

    @Test
    fun rejectsMissingCrossCategoryDuplicateAndCyclicInheritance() {
        for (parent in listOf("Missing", "Printer", "PLA")) {
            assertRejected(archive(files().apply { put("filament/base.json", preset("Material base", "filament", parent)) }))
        }
        assertRejected(archive(files().apply { put("filament/base.json", preset("Printer", "filament")) }))
    }

    @Test
    fun enforcesExpandedFileAndAggregateLimitsAndRejectsTruncatedZip() {
        assertRejected(archive(files().apply { put("manifest.json", ByteArray(64 * 1024 + 1)) }))
        assertRejected(archive(files().apply { put("machine.json", ByteArray(1024 * 1024 + 1)) }))
        assertRejected(archive(files().apply {
            val large = ByteArray(1024 * 1024)
            repeat(32) { index -> put("extra$index.json", large) }
        }))
        val complete = archive(files())
        assertRejected(complete.copyOf(complete.size - 22))
    }

    @Test
    fun inputCloseFailureDoesNotPublishBundle() {
        val input = object : ByteArrayInputStream(archive(files())) {
            override fun close() = throw IOException("Private provider failure")
        }
        val error = assertThrows(IOException::class.java) { store.importZip(input) }
        assertFalse(error.message.orEmpty().contains("Private provider failure"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun assertRejected(zip: ByteArray, existing: Int = 0) {
        assertThrows(IOException::class.java) { store.importZip(zip.inputStream()) }
        assertEquals(existing, store.list().size)
        assertEquals(existing, root.listFiles().orEmpty().size)
    }

    private fun files() = linkedMapOf(
        "manifest.json" to manifest().toString().toByteArray(),
        "machine.json" to preset("Printer", "machine"),
        "process.json" to preset("Process", "process"),
        "filament/base.json" to preset("Material base", "filament", selectable = false),
        "filament/pla.json" to preset("PLA", "filament", "Material base"),
        "filament/petg.json" to preset("PETG", "filament", "Material base"),
    )

    private fun manifest() = JSONObject()
        .put("schema", "orca-native-presets-v1")
        .put("printer", "machine.json")
        .put("process", "process.json")
        .put("filaments", JSONArray(listOf("filament/pla.json", "filament/pla.json")))
        .put("catalog", JSONArray(listOf("filament/base.json", "filament/petg.json")))

    private fun preset(name: String, type: String, parent: String = "", selectable: Boolean = true) = JSONObject()
        .put("name", name)
        .put("type", type)
        .put("inherits", parent)
        .put("instantiation", selectable.toString())
        .toString().toByteArray()

    private fun archive(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((path, bytes) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun replaceBytes(bytes: ByteArray, from: String, to: String) {
        val old = from.toByteArray()
        val replacement = to.toByteArray()
        require(old.size == replacement.size)
        for (offset in 0..bytes.size - old.size) {
            if (old.indices.all { bytes[offset + it] == old[it] }) replacement.copyInto(bytes, offset)
        }
    }
}
