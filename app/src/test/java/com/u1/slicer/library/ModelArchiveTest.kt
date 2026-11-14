package com.u1.slicer.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ModelArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private fun archive(vararg files: Pair<String, String>): File {
        val result = temp.newFile("${java.util.UUID.randomUUID()}.zip")
        ZipOutputStream(result.outputStream()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return result
    }

    @Test fun listsNestedModelsAndExtractsOnlySelection() = runBlocking<Unit> {
        val zip = archive("note.txt" to "notes", "nested/one.stl" to "solid one", "two.3mf" to "project")
        val reader = ModelArchive()
        val candidates = reader.list(zip)
        assertEquals(listOf("nested/one.stl", "two.3mf"), candidates.map { it.path })
        val output = File(temp.root, "selected.stl")
        reader.extract(zip, candidates.first(), output)
        assertEquals("solid one", output.readText())
        assertFalse(File(temp.root, "nested").exists())
    }

    @Test fun rejectsUnsafeAndDuplicatePaths() {
        for (name in listOf("../evil.stl", "/evil.stl", "C:/evil.stl", "dir/../evil.stl", "dir\\evil.stl")) {
            val zip = archive(name to "model")
            assertThrows(IOException::class.java) { runBlocking { ModelArchive().list(zip) } }
        }
        val duplicate = archive("models/part.stl" to "first", "models/PART.stl" to "second")
        assertThrows(IOException::class.java) { runBlocking { ModelArchive().list(duplicate) } }
    }

    @Test fun enforcesEntryCountSizeAndCompressionBounds() {
        val zip = archive("one.stl" to "12345", "two.txt" to "12345")
        assertThrows(IOException::class.java) { runBlocking { ModelArchive(maxEntries = 1).list(zip) } }
        assertThrows(IOException::class.java) { runBlocking { ModelArchive(maxExpandedBytes = 9).list(zip) } }
        assertThrows(IOException::class.java) { runBlocking { ModelArchive(maxFileBytes = 4).list(zip) } }
        val compressed = archive("model.stl" to "x".repeat(10_000))
        assertThrows(IOException::class.java) { runBlocking { ModelArchive(maxCompressionRatio = 2).list(compressed) } }
    }

    @Test fun rejectsThreeMfAndCorruptZipAndCleansFailedExtraction() = runBlocking<Unit> {
        val project = temp.newFile("project.3mf")
        project.writeText("not a generic zip")
        assertThrows(IOException::class.java) { runBlocking { ModelArchive().list(project) } }
        val corrupt = temp.newFile("corrupt.zip")
        corrupt.writeText("not zip")
        assertThrows(IOException::class.java) { runBlocking { ModelArchive().list(corrupt) } }
        val zip = archive("one.stl" to "model")
        val reader = ModelArchive()
        val candidate = reader.list(zip).single()
        val output = File(temp.root, "output.stl")
        zip.writeText("broken")
        assertThrows(IOException::class.java) { runBlocking { reader.extract(zip, candidate, output) } }
        assertFalse(output.exists())
        assertTrue(zip.exists())
    }

    @Test fun rejectsMisleadingEntrySizeAndRemovesPartial() = runBlocking<Unit> {
        val zip = archive("one.stl" to "model")
        val bytes = zip.readBytes()
        val central = (0 until bytes.size - 4).first {
            bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() &&
                bytes[it + 2] == 0x01.toByte() && bytes[it + 3] == 0x02.toByte()
        }
        bytes[central + 24] = 10 // Declared expanded size differs from the actual five bytes.
        zip.writeBytes(bytes)
        val reader = ModelArchive()
        val candidate = reader.list(zip).single()
        val output = File(temp.root, "truncated.stl")
        assertThrows(IOException::class.java) { runBlocking { reader.extract(zip, candidate, output) } }
        assertFalse(output.exists())
    }

    @Test fun rejectsCandidateWhenArchiveChanges() = runBlocking<Unit> {
        val zip = archive("one.stl" to "first")
        val reader = ModelArchive()
        val candidate = reader.list(zip).single()
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("one.stl"))
            output.write("other".toByteArray())
            output.closeEntry()
        }
        val destination = File(temp.root, "changed.stl")
        assertThrows(IOException::class.java) { runBlocking { reader.extract(zip, candidate, destination) } }
        assertFalse(destination.exists())
    }
}
