package com.u1.slicer.presets

import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal enum class FullPreset3mfArchiveError { INVALID, TOO_LARGE }

internal fun fullPreset3mfArchiveError(file: File): FullPreset3mfArchiveError? = try {
    ZipFile(file).use { archive -> fullPreset3mfArchiveError(archive.entries().asSequence()) }
} catch (_: IOException) {
    FullPreset3mfArchiveError.INVALID
}

internal fun fullPreset3mfArchiveError(entries: Sequence<ZipEntry>): FullPreset3mfArchiveError? {
    val names = mutableSetOf<String>()
    var expandedBytes = 0L
    for (entry in entries) {
        if (names.size == MAX_3MF_ENTRIES) return FullPreset3mfArchiveError.TOO_LARGE
        val path = Normalizer.normalize(entry.name.replace('\\', '/').removeSuffix("/"), Normalizer.Form.NFC)
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." } || '\u0000' in path ||
            !names.add(path.lowercase(Locale.ROOT)) || entry.size < 0L || entry.compressedSize < 0L
        ) {
            return FullPreset3mfArchiveError.INVALID
        }
        // The native ZIP importer allocates metadata buffers from the declared expanded size.
        if (entry.size > MAX_EXPANDED_3MF_BYTES - expandedBytes) return FullPreset3mfArchiveError.TOO_LARGE
        expandedBytes += entry.size
    }
    return FullPreset3mfArchiveError.INVALID.takeIf { names.isEmpty() }
}

private const val MAX_3MF_ENTRIES = 2_048
private const val MAX_EXPANDED_3MF_BYTES = 128L * 1024 * 1024
