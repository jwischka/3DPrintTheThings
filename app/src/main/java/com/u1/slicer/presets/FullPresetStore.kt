package com.u1.slicer.presets

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Stores portable full-preset bundles under an app-private directory. */
class FullPresetStore(private val root: File) {
    enum class Category(internal val jsonType: String) {
        PRINTER("machine"), PROCESS("process"), FILAMENT("filament")
    }

    data class Preset(val path: String, val name: String, val category: Category, val isSelectable: Boolean)

    data class Bundle(
        val id: String,
        val manifest: File,
        val printer: Preset,
        val process: Preset,
        val filaments: List<Preset>,
        val catalog: List<Preset>,
    ) {
        val printerName: String get() = printer.name
        val processName: String get() = process.name
        val filamentNames: List<String> get() = filaments.map(Preset::name)
        val displayName: String get() = "$printerName / $processName"
    }

    private data class Entry(val preset: Preset, val parent: String)
    private data class Manifest(
        val printer: String,
        val process: String,
        val filaments: List<String>,
        val catalog: List<String>,
    ) {
        val paths: Set<String> get() = (listOf(printer, process) + filaments + catalog).toSet()
    }

    internal class InvalidBundle(message: String) : IOException(message)

    fun list(): List<Bundle> = root.listFiles().orEmpty()
        .filter { it.isDirectory && idPattern.matches(it.name) }
        .mapNotNull { get(it.name) }
        .sortedWith(compareBy<Bundle> { it.displayName.lowercase(Locale.ROOT) }.thenBy { it.id })

    fun get(bundleId: String): Bundle? {
        if (!idPattern.matches(bundleId)) return null
        val directory = File(root, bundleId)
        if (!directory.isDirectory) return null
        return try {
            readBundle(directory, bundleId)
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    /** Consumes and closes the input, and publishes only a fully validated bundle. */
    fun importZip(input: InputStream): Bundle = safeOperation("Could not read or store the preset ZIP") {
        var staging: File? = null
        try {
            val archive = input.use {
                Files.createDirectories(root.toPath())
                val directory = Files.createTempDirectory(root.toPath(), ".import-").toFile()
                staging = directory
                val archive = File(directory, "archive.zip")
                archive.outputStream().buffered().use { output ->
                    copyBounded(input, output, maxArchiveBytes, "Preset ZIP exceeds the archive size limit")
                }
                archive
            }
            val content = File(archive.parentFile, "content")
            Files.createDirectory(content.toPath())
            val files = extract(archive, content)
            val id = UUID.randomUUID().toString()
            val bundle = readBundle(content, id, files)
            val destination = File(root, id)
            Files.move(content.toPath(), destination.toPath(), ATOMIC_MOVE)
            bundle.copy(manifest = File(destination, manifestName))
        } finally {
            staging?.deleteRecursively()
        }
    }

    fun writeSelection(
        bundleId: String,
        printerPath: String,
        processPath: String,
        filamentPaths: List<String>,
    ): File = safeOperation("Could not save the full preset selection") {
        checkBundle(idPattern.matches(bundleId), "Preset bundle is unavailable")
        val directory = File(root, bundleId)
        val source = readManifest(File(directory, manifestName))
        val entries = readEntries(directory, source)
        val selection = Manifest(printerPath, processPath, filamentPaths.toList(), emptyList())
        val selected = selectedPresets(selection, entries)
        val byName = entries.values.associateBy { it.preset.name }
        val parents = linkedSetOf<String>()
        for (preset in selected) {
            var parent = byName[entries.getValue(preset.path).parent]
            while (parent != null) {
                parents.add(parent.preset.path)
                parent = byName[parent.parent]
            }
        }
        parents.removeAll(selection.paths)
        checkBundle(parents.size <= maxCatalog, "Preset selection has too many inherited files")
        val json = JSONObject()
            .put("schema", schemaV2)
            .put("project_settings", geometryOnly)
            .put("printer", printerPath)
            .put("process", processPath)
            .put("filaments", JSONArray(selection.filaments))
            .put("catalog", JSONArray(parents))
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        checkBundle(bytes.size <= maxManifestBytes, "Preset selection manifest exceeds the size limit")
        val manifest = File(directory, selectionName)
        val temporary = Files.createTempFile(root.toPath(), ".selection-", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, manifest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        manifest
    }

    fun delete(bundleId: String): Boolean {
        if (!idPattern.matches(bundleId)) return false
        val directory = File(root, bundleId)
        if (!directory.isDirectory) return false
        val trash = File(root, ".deleted-${UUID.randomUUID()}")
        return try {
            Files.move(directory.toPath(), trash.toPath(), ATOMIC_MOVE)
            trash.deleteRecursively()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun extract(archive: File, directory: File): Set<String> {
        val expected = linkedMapOf<String, ZipEntry>()
        val names = mutableSetOf<String>()
        val files = mutableSetOf<String>()
        var total = 0L
        ZipFile(archive).use { zip ->
            checkBundle(zip.size() <= maxEntries, "Preset ZIP has too many entries")
            for (entry in zip.entries()) {
                val path = safePath(entry.name.removeDirectorySuffix(entry.isDirectory))
                checkBundle(names.add(path.lowercase(Locale.ROOT)), "Preset ZIP contains duplicate paths")
                expected[entry.name] = entry
                checkBundle(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED, "Unsupported ZIP compression")
                val limit = when {
                    entry.isDirectory -> 0L
                    path == manifestName -> maxManifestBytes
                    else -> maxPresetBytes
                }
                checkBundle(entry.size in 0..limit, "Preset ZIP entry exceeds its size limit")
                if (!entry.isDirectory) {
                    checkBundle(path.endsWith(".json", ignoreCase = true), "Preset ZIP contains an unsupported file")
                    files.add(path)
                    checkBundle(files.size <= maxFiles, "Preset ZIP has too many files")
                }
                total += entry.size
                checkBundle(total <= maxExpandedBytes, "Preset ZIP exceeds the expanded size limit")
            }
        }
        checkBundle(manifestName in files, "Preset ZIP must contain manifest.json at its root")

        // ZipFile checks the directory. The stream also checks local headers, sizes and CRCs.
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val record = expected.remove(entry.name) ?: throw InvalidBundle("ZIP headers disagree")
                checkBundle(entry.method == record.method, "ZIP headers disagree")
                if (record.isDirectory) {
                    checkBundle(zip.read() == -1, "Preset ZIP directory contains data")
                } else {
                    val target = confinedFile(directory, entry.name)
                    Files.createDirectories(target.parentFile!!.toPath())
                    Files.newOutputStream(Files.createFile(target.toPath())).buffered().use { output ->
                        val written = copyBounded(zip, output, record.size, "Preset ZIP entry exceeds its declared size")
                        checkBundle(written == record.size, "Preset ZIP entry size does not match")
                    }
                }
                checkBundle(entry.size == record.size && entry.crc == record.crc, "ZIP headers disagree")
                zip.closeEntry()
            }
        }
        checkBundle(expected.isEmpty(), "Preset ZIP is incomplete")
        return files
    }

    private fun readBundle(directory: File, id: String, archiveFiles: Set<String>? = null): Bundle {
        val source = File(directory, manifestName)
        val savedSelection = File(directory, selectionName)
        val manifestFile = if (savedSelection.isFile) savedSelection else source
        val original = readManifest(source)
        if (archiveFiles != null) {
            checkBundle(original.paths + manifestName == archiveFiles, "Preset ZIP contains missing or unlisted files")
        }
        val entries = readEntries(directory, original)
        val manifest = if (manifestFile == source) original else readManifest(manifestFile)
        val selected = selectedPresets(manifest, entries)
        return Bundle(id, manifestFile, selected[0], selected[1], selected.drop(2), entries.values.map(Entry::preset))
    }

    private fun readManifest(file: File): Manifest {
        val json = readObject(file, maxManifestBytes)
        val keys = json.keys().asSequence().toSet()
        val validSchema = when (json.opt("schema")) {
            schemaV1 -> keys == manifestKeys
            schemaV2 -> keys == manifestKeys + "project_settings" && json.opt("project_settings") == geometryOnly
            else -> false
        }
        checkBundle(validSchema, "Unsupported full preset manifest")
        val manifest = Manifest(
            safePath(string(json, "printer")),
            safePath(string(json, "process")),
            paths(json, "filaments", 1..maxFilaments),
            paths(json, "catalog", 0..maxCatalog),
        )
        checkBundle(manifestName !in manifest.paths, "Manifest cannot also be a preset")
        return manifest
    }

    private fun paths(json: JSONObject, field: String, count: IntRange): List<String> {
        val array = json.optJSONArray(field) ?: throw InvalidBundle("Invalid full preset manifest field")
        checkBundle(array.length() in count, "Invalid full preset manifest entry count")
        return List(array.length()) { index ->
            safePath(array.opt(index) as? String ?: throw InvalidBundle("Invalid preset path"))
        }
    }

    private fun selectedPresets(manifest: Manifest, entries: Map<String, Entry>): List<Preset> {
        checkBundle(manifest.filaments.size in 1..maxFilaments, "Choose between 1 and 16 filament slots")
        fun selected(path: String, category: Category): Preset {
            val preset = entries[path]?.preset ?: throw InvalidBundle("Selected preset is missing")
            checkBundle(preset.isSelectable && preset.category == category, "Selected preset has the wrong category or cannot be selected")
            return preset
        }
        return listOf(selected(manifest.printer, Category.PRINTER), selected(manifest.process, Category.PROCESS)) +
            manifest.filaments.map { selected(it, Category.FILAMENT) }
    }

    private fun readEntries(directory: File, manifest: Manifest): Map<String, Entry> {
        val byName = mutableMapOf<String, Entry>()
        val entries = manifest.paths.associateWith { path ->
            val json = readObject(confinedFile(directory, path), maxPresetBytes)
            val type = string(json, "type")
            val category = Category.entries.firstOrNull { it.jsonType == type }
                ?: throw InvalidBundle("Invalid full preset category")
            val name = string(json, "name")
            checkBundle(name.isNotBlank() && name.none { it.isISOControl() }, "Preset has no valid name")
            val instantiation = if (json.has("instantiation")) string(json, "instantiation") else ""
            checkBundle(instantiation in listOf("", "true", "false"), "Invalid preset instantiation")
            val parent = if (json.has("inherits")) string(json, "inherits") else ""
            val entry = Entry(Preset(path, name, category, instantiation != "false"), parent)
            checkBundle(byName.put(name, entry) == null, "Duplicate preset name")
            entry
        }
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(entry: Entry) {
            val name = entry.preset.name
            if (name in visited) return
            checkBundle(visiting.add(name), "Preset inheritance contains a cycle")
            if (entry.parent.isNotEmpty()) {
                val parent = byName[entry.parent] ?: throw InvalidBundle("Preset inheritance parent is missing")
                checkBundle(parent.preset.category == entry.preset.category, "Preset parent belongs to a different category")
                visit(parent)
            }
            visiting.remove(name)
            visited.add(name)
        }
        entries.values.forEach(::visit)
        return entries
    }

    private fun readObject(file: File, limit: Long): JSONObject {
        checkBundle(file.isFile && file.length() in 1..limit, "Preset JSON is missing or exceeds its size limit")
        val data = ByteArrayOutputStream(file.length().toInt())
        file.inputStream().use { copyBounded(it, data, limit, "Preset JSON exceeds its size limit") }
        val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data.toByteArray())).toString().removePrefix("\uFEFF")
        checkJsonDepth(json)
        val tokenizer = JSONTokener(json)
        val value = tokenizer.nextValue() as? JSONObject ?: throw InvalidBundle("Expected a JSON object")
        checkBundle(tokenizer.nextClean() == '\u0000', "Unexpected text after preset JSON")
        return value
    }

    // Bound parser recursion before handing an untrusted document to org.json.
    private fun checkJsonDepth(json: String) {
        var depth = 0
        var inString = false
        var escaped = false
        for (character in json) {
            checkBundle(character >= ' ' || !inString && character in "\t\r\n", "Invalid control character in preset JSON")
            if (inString) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') inString = false
            } else {
                when (character) {
                    '"' -> inString = true
                    '{', '[' -> checkBundle(++depth <= 64, "Preset JSON is nested too deeply")
                    '}', ']' -> checkBundle(--depth >= 0, "Malformed preset JSON")
                    '\'', '/', '#', ';', '=' -> throw InvalidBundle("Malformed preset JSON")
                }
            }
        }
        checkBundle(depth == 0 && !inString, "Malformed preset JSON")
    }

    private fun string(json: JSONObject, key: String): String = json.opt(key) as? String
        ?: throw InvalidBundle("Invalid preset JSON field")

    private fun safePath(path: String): String {
        val components = path.split('/')
        checkBundle(
            components.none { it.isEmpty() || it == "." || it == ".." || it.any(Char::isISOControl) } &&
                '\\' !in path && ':' !in path && Normalizer.isNormalized(path, Normalizer.Form.NFC) &&
                !components.first().equals(selectionName, ignoreCase = true),
            "Unsafe preset ZIP path",
        )
        return path
    }

    private fun confinedFile(directory: File, path: String): File {
        val root = directory.canonicalFile.toPath()
        val file = File(directory, safePath(path)).canonicalFile
        checkBundle(file.toPath() == root.resolve(path), "Unsafe preset ZIP path")
        return file
    }

    private fun String.removeDirectorySuffix(directory: Boolean): String = if (directory) removeSuffix("/") else this

    private fun copyBounded(input: InputStream, output: OutputStream, limit: Long, message: String): Long {
        val buffer = ByteArray(8192)
        var written = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return written
            written += count
            checkBundle(written <= limit, message)
            output.write(buffer, 0, count)
        }
    }

    private fun checkBundle(condition: Boolean, message: String) {
        if (!condition) throw InvalidBundle(message)
    }

    private inline fun <T> safeOperation(message: String, block: () -> T): T = try {
        block()
    } catch (invalid: InvalidBundle) {
        throw invalid
    } catch (_: IOException) {
        throw InvalidBundle(message)
    } catch (_: IllegalArgumentException) {
        throw InvalidBundle(message)
    } catch (_: JSONException) {
        throw InvalidBundle("Malformed preset JSON")
    }

    private companion object {
        const val manifestName = "manifest.json"
        const val selectionName = ".selection.json"
        const val schemaV1 = "orca-native-presets-v1"
        const val schemaV2 = "orca-native-presets-v2"
        const val geometryOnly = "geometry-only"
        const val maxManifestBytes = 64L * 1024
        const val maxPresetBytes = 1024L * 1024
        const val maxExpandedBytes = 32L * 1024 * 1024
        const val maxArchiveBytes = 40L * 1024 * 1024
        const val maxFilaments = 16
        const val maxCatalog = 128
        const val maxFiles = 1 + 2 + maxFilaments + maxCatalog
        const val maxEntries = 512
        val manifestKeys = setOf("schema", "printer", "process", "filaments", "catalog")
        val idPattern = Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    }
}
