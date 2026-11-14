package com.u1.slicer.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.CRC32

/** Lists models without extracting the archive; extraction writes only a selected entry.
 * ZipFile does not expose Unix symlink metadata; entry paths are never used as filesystem paths.
 */
class ModelArchive(
    private val maxEntries: Int = 1000,
    private val maxExpandedBytes: Long = 512L * 1024 * 1024,
    private val maxFileBytes: Long = 256L * 1024 * 1024,
    private val maxCompressionRatio: Long = 200,
) {
    data class Candidate internal constructor(val index: Int, val path: String, val sizeBytes: Long, val crc32: Long)

    init {
        require(maxEntries > 0 && maxExpandedBytes > 0 && maxFileBytes > 0 && maxCompressionRatio > 0)
    }

    suspend fun list(file: File): List<Candidate> = withContext(Dispatchers.IO) {
        requireZip(file)
        ZipFile(file).use { scan(it) }
    }

    /** Destination must not exist. On failure or cancellation, removes the partial file. */
    suspend fun extract(file: File, candidate: Candidate, destination: File) = withContext(Dispatchers.IO) {
        requireZip(file)
        if (destination.exists() || !destination.createNewFile()) throw IOException("Archive destination already exists")
        var complete = false
        try {
            ZipFile(file).use { zip ->
                val candidates = scan(zip)
                if (candidate !in candidates) throw IOException("Archive candidate changed")
                val entries = zip.entries()
                var index = 0
                var selected: ZipEntry? = null
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (index++ == candidate.index) { selected = entry; break }
                }
                val entry = selected ?: throw IOException("Archive candidate missing")
                val context = currentCoroutineContext()
                var total = 0L
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxFileBytes || total > candidate.sizeBytes) throw IOException("Archive entry exceeds declared size")
                            crc.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (total != candidate.sizeBytes || total == 0L || crc.value != entry.crc) throw IOException("Archive entry integrity mismatch")
            }
            currentCoroutineContext().ensureActive()
            complete = true
        } finally {
            if (!complete) destination.delete()
        }
    }

    private fun requireZip(file: File) {
        if (!file.name.endsWith(".zip", ignoreCase = true)) throw IOException("Expected a ZIP archive")
    }

    private suspend fun scan(zip: ZipFile): List<Candidate> {
        val names = mutableSetOf<String>()
        val models = mutableListOf<Candidate>()
        val entries = zip.entries()
        var index = 0
        var expanded = 0L
        while (entries.hasMoreElements()) {
            currentCoroutineContext().ensureActive()
            if (index >= maxEntries) throw IOException("Archive has too many entries")
            val entry = entries.nextElement()
            val path = normalizedPath(entry.name, entry.isDirectory)
            if (!names.add(path.lowercase(Locale.ROOT))) throw IOException("Duplicate archive path")
            if (entry.isDirectory && entry.size != 0L) throw IOException("Archive directory contains data")
            if (!entry.isDirectory) {
                val size = entry.size
                val compressed = entry.compressedSize
                if (size < 0 || compressed < 0 || entry.crc < 0 || size > maxFileBytes || size > maxExpandedBytes - expanded) {
                    throw IOException("Archive exceeds expanded size limit")
                }
                if (size > 0 && (compressed == 0L || (size - 1) / compressed >= maxCompressionRatio)) {
                    throw IOException("Archive compression ratio exceeds limit")
                }
                expanded += size
                if (path.endsWith(".stl", true) || path.endsWith(".3mf", true)) {
                    models += Candidate(index, path, size, entry.crc)
                }
            }
            index++
        }
        return models
    }

    private fun normalizedPath(name: String, directory: Boolean): String {
        val path = Normalizer.normalize(name, Normalizer.Form.NFC)
        val trimmed = if (directory) path.removeSuffix("/") else path
        if (trimmed.isEmpty() || trimmed.startsWith('/') || '\\' in trimmed ||
            Regex("^[A-Za-z]:").containsMatchIn(trimmed) ||
            trimmed.split('/').any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c.isISOControl() } }) {
            throw IOException("Unsafe archive path")
        }
        return trimmed
    }
}
