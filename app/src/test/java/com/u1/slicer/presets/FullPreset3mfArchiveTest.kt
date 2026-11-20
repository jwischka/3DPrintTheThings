package com.u1.slicer.presets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FullPreset3mfArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsArchiveWithRegularAndDirectoryEntries() {
        val file = archive("3D/", "3D/3dmodel.model", "[Content_Types].xml", "_rels/.rels")
        assertNull(fullPreset3mfArchiveError(file))
    }

    @Test fun rejectsMalformedZip() {
        val file = temporary.newFile().apply { writeText("Not a ZIP") }
        assertEquals(FullPreset3mfArchiveError.INVALID, fullPreset3mfArchiveError(file))
    }

    @Test fun rejectsDuplicateNormalizedPaths() {
        val file = archive("3D/caf\u00E9.model", "3d/cafe\u0301.model")
        assertEquals(FullPreset3mfArchiveError.INVALID, fullPreset3mfArchiveError(file))
    }

    @Test fun rejectsUnknownSizes() {
        val entry = ZipEntry("3D/3dmodel.model").apply { compressedSize = 1L }
        assertEquals(FullPreset3mfArchiveError.INVALID, fullPreset3mfArchiveError(sequenceOf(entry)))
    }

    @Test fun limitsTotalExpandedSize() {
        val first = entry("3D/3dmodel.model", 64 * MIB)
        val second = entry("3D/Objects/part.model", 64 * MIB)
        val properties = entry("[Content_Types].xml", 1)
        assertNull(fullPreset3mfArchiveError(sequenceOf(first, second)))
        assertEquals(FullPreset3mfArchiveError.TOO_LARGE, fullPreset3mfArchiveError(sequenceOf(first, second, properties)))
    }

    @Test fun limitsNumberOfEntries() {
        val entries = (0 until 2_049).asSequence().map { entry("3D/Objects/part-$it.model", 1) }
        assertNull(fullPreset3mfArchiveError(entries.take(2_048)))
        assertEquals(FullPreset3mfArchiveError.TOO_LARGE, fullPreset3mfArchiveError(entries))
    }

    private fun archive(vararg paths: String): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            paths.forEach { path ->
                zip.putNextEntry(ZipEntry(path))
                if (!path.endsWith('/')) zip.write(1)
                zip.closeEntry()
            }
        }
    }

    private fun entry(path: String, expandedBytes: Long): ZipEntry = ZipEntry(path).apply {
        size = expandedBytes
        compressedSize = 1L
    }

    companion object {
        private const val MIB = 1024L * 1024L
    }
}
